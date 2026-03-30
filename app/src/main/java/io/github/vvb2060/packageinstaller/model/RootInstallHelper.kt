package io.github.vvb2060.packageinstaller.model

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException

object RootInstallHelper {
    private data class PackageDetails(
        val versionCode: Long?,
        val versionName: String?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val enabled: Boolean,
    )

    private data class ShellResult(
        val exitCode: Int,
        val output: String,
    )

    fun isAvailable(): Boolean {
        return try {
            execute("id -u").let { result ->
                result.exitCode == 0 && result.output.trim() == "0"
            }
        } catch (_: Exception) {
            false
        }
    }

    fun createSession(totalSize: Long, installerPackageName: String?, packageName: String, full: Boolean): Int {
        val command = buildString {
            append("pm install-create")
            append(" -r -t -d")
            if (totalSize > 0) {
                append(" -S ").append(totalSize)
            }
            if (!installerPackageName.isNullOrEmpty()) {
                append(" -i ").append(shellQuote(installerPackageName))
            }
            if (full) {
                append(" --pkg ").append(shellQuote(packageName))
            } else {
                append(" -p ").append(shellQuote(packageName))
            }
        }
        val result = execute(command)
        if (result.exitCode != 0) {
            throw IOException("Failed to create root install session: ${result.output}")
        }
        return parseSessionId(result.output)
    }

    fun writeSession(sessionId: Int, files: List<File>) {
        for (file in files) {
            val result = execute(
                "cat ${shellQuote(file.absolutePath)} | pm install-write -S ${file.length()} $sessionId ${shellQuote(file.name.substringBeforeLast(".apk"))} -"
            )
            if (result.exitCode != 0 || result.output.contains("Exception") || result.output.contains("Error")) {
                throw IOException("Failed to write ${file.name}: ${result.output}")
            }
        }
    }

    fun commitSession(sessionId: Int) {
        val result = execute("pm install-commit $sessionId")
        if (result.exitCode != 0 || !result.output.contains("Success", ignoreCase = true)) {
            throw IOException("Failed to commit root install session: ${result.output}")
        }
    }

    fun abandonSession(sessionId: Int) {
        execute("pm install-abandon $sessionId")
    }

    fun getActiveSession(packageName: String): Pair<Int, Boolean>? {
        val result = execute("dumpsys package installs")
        if (result.exitCode != 0) return null

        var currentSessionId: Int? = null
        var foundPackage = false
        var isUserRoot = false
        var isCommitted = false
        var isDestroyed = false
        var isFull = true
        var isFinalizedSection = false

        for (line in result.output.lineSequence()) {
            if (line.contains("Finalized install sessions:")) {
                isFinalizedSection = true
            }
            val lineTrimmed = line.trim()
            val sessionMatch = Regex("""Active Session (\d+):""").find(lineTrimmed)
            if (sessionMatch != null && !isFinalizedSection) {
                if (foundPackage && isUserRoot && !isCommitted && !isDestroyed && currentSessionId != null) {
                    return Pair(currentSessionId, isFull)
                }
                currentSessionId = sessionMatch.groupValues[1].toInt()
                foundPackage = false
                isUserRoot = false
                isCommitted = false
                isDestroyed = false
                isFull = true
                continue
            }
            if (currentSessionId != null && !isFinalizedSection) {
                if (lineTrimmed.contains("appPackageName=$packageName ") || lineTrimmed.endsWith("appPackageName=$packageName")) {
                    foundPackage = true
                }
                if (lineTrimmed.contains("mInstallerUid=0 ") || lineTrimmed.endsWith("mInstallerUid=0")) {
                    isUserRoot = true
                }
                if (lineTrimmed.contains("installInitiatingPackageName=com.android.shell ") || lineTrimmed.endsWith("installInitiatingPackageName=com.android.shell")) {
                    isUserRoot = true
                }
                if (lineTrimmed.contains("mCommitted=true ") || lineTrimmed.endsWith("mCommitted=true")) {
                    isCommitted = true
                }
                if (lineTrimmed.contains("mDestroyed=true ") || lineTrimmed.endsWith("mDestroyed=true")) {
                    isDestroyed = true
                }
                if (lineTrimmed.contains("mode=2 ") || lineTrimmed.endsWith("mode=2")) {
                    isFull = false
                }
            }
        }
        if (foundPackage && isUserRoot && !isCommitted && !isDestroyed && currentSessionId != null) {
            return Pair(currentSessionId, isFull)
        }
        return null
    }

    fun installExistingPackage(packageName: String) {
        val userId = Os.getuid() / 100000
        val result = execute("pm install-existing --user $userId ${shellQuote(packageName)}")
        if (result.exitCode != 0 || !result.output.contains("Success", ignoreCase = true)) {
            throw IOException("Failed to install existing package $packageName: ${result.output}")
        }
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return queryPackagePaths(packageName).isNotEmpty()
    }

    fun getInstalledPackageInfo(packageName: String, pm: android.content.pm.PackageManager): PackageInfo? {
        val paths = queryPackagePaths(packageName)
        if (paths.isEmpty()) {
            return null
        }

        val details = queryPackageDetails(packageName)
        val basePath = paths.firstOrNull { it.endsWith("/base.apk") } ?: paths.first()
        val splitPaths = paths.filter { it != basePath }
        val splitNames = splitPaths.map { File(it).name.removeSuffix(".apk") }

        // Attempt to parse the APK using Android's native parser
        val pi = pm.getPackageArchiveInfo(basePath, 0)
        
        val appInfo = (pi?.applicationInfo ?: ApplicationInfo()).apply {
            this.packageName = packageName
            sourceDir = basePath
            publicSourceDir = basePath
            splitSourceDirs = splitPaths.toTypedArray().takeIf { it.isNotEmpty() }
            flags = flags or ApplicationInfo.FLAG_INSTALLED
            minSdkVersion = details?.minSdk ?: 1
            targetSdkVersion = details?.targetSdk ?: Build.VERSION.SDK_INT
            enabled = details?.enabled ?: true
        }

        return (pi ?: PackageInfo()).apply {
            this.packageName = packageName
            applicationInfo = appInfo
            if (splitNames.isNotEmpty()) {
                this.splitNames = splitNames.toTypedArray()
            }
            versionName = details?.versionName ?: versionName ?: "unknown"
            val existingVC = getLongVersionCode()
            setLongVersionCode(if (existingVC > 0L) existingVC else (details?.versionCode ?: 0L))
        }
    }

    fun prepareSourceFiles(
        context: Context,
        uri: Uri,
        apk: ApkLite,
        targetDir: File,
    ): List<File> {
        targetDir.mkdirs()
        return try {
            PackageUtil.extractInstallFiles(context, uri, apk, targetDir)
        } catch (e: Exception) {
            val sourceFile = uniqueFile(targetDir, "source.apk")
            if (!copyContentUriToFile(uri, sourceFile)) {
                throw IOException("Failed to read URI via root: $uri", e)
            }
            try {
                PackageUtil.extractInstallFilesFromFile(sourceFile, apk, targetDir)
            } finally {
                runCatching { sourceFile.delete() }
            }
        }
    }

    fun copyContentUriToFile(uri: Uri, targetFile: File): Boolean {
        targetFile.parentFile?.mkdirs()
        val result = execute(
            "content read --uri ${shellQuote(uri.toString())} > ${shellQuote(targetFile.absolutePath)}"
        )
        ensureReadableByApp(targetFile)
        
        if (result.exitCode != 0 || !targetFile.exists() || targetFile.length() <= 0L) {
            runCatching { targetFile.delete() }
            return false
        }
        return true
    }

    fun copyFileToFile(sourcePath: String, targetFile: File): Boolean {
        targetFile.parentFile?.mkdirs()
        val result = execute(
            "cat ${shellQuote(sourcePath)} > ${shellQuote(targetFile.absolutePath)}"
        )
        ensureReadableByApp(targetFile)
        if (result.exitCode != 0 || !targetFile.exists() || targetFile.length() <= 0L) {
            runCatching { targetFile.delete() }
            return false
        }
        return true
    }

    private fun ensureReadableByApp(targetFile: File) {
        // Keep ownership with the app uid so PackageManager/AssetManager can read resource tables.
        execute("chown ${android.os.Process.myUid()} ${shellQuote(targetFile.absolutePath)}")
        execute("chmod 644 ${shellQuote(targetFile.absolutePath)}")
    }

    fun prepareInstalledPackageFiles(
        info: PackageInfo,
        targetDir: File,
        excludeSplitName: String? = null,
    ): List<File> {
        targetDir.mkdirs()
        val files = ArrayList<File>()
        val source = info.applicationInfo?.sourceDir?.let { File(it) }
        if (source != null && source.exists()) {
            files.add(copyToTemp(source, targetDir, source.name))
        }
        val splitSourceDirs = info.applicationInfo?.splitSourceDirs
        val splitNames = info.splitNames
        if (splitSourceDirs != null) {
            for (index in splitSourceDirs.indices) {
                val splitFile = File(splitSourceDirs[index])
                if (!splitFile.exists()) continue
                val splitName = splitNames?.getOrNull(index)
                if (excludeSplitName != null && splitName == excludeSplitName) continue
                files.add(copyToTemp(splitFile, targetDir, splitFile.name))
            }
        }
        return files
    }

    fun copyToTemp(source: File, targetDir: File, name: String = source.name): File {
        targetDir.mkdirs()
        val target = uniqueFile(targetDir, name)
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    fun deleteFiles(files: List<File>) {
        files.forEach { file ->
            runCatching { file.delete() }
        }
    }

    fun hasLauncherActivity(packageName: String): Boolean {
        val result = execute("pm resolve-activity --brief -c android.intent.category.LAUNCHER ${shellQuote(packageName)}")
        return result.exitCode == 0 && !result.output.contains("No activity found")
    }

    fun launchPackage(packageName: String) {
        execute("monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1")
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        val action = if (enabled) "enable" else "disable-user"
        val result = execute("pm $action ${shellQuote(packageName)}")
        if (result.exitCode != 0) {
            throw IOException("Failed to $action $packageName: ${result.output}")
        }
    }

    fun uninstallPackage(packageName: String, keepData: Boolean) {
        val keep = if (keepData) "-k " else ""
        val result = execute("pm uninstall $keep${shellQuote(packageName)}")
        if (result.exitCode != 0) {
            throw IOException("Failed to uninstall $packageName: ${result.output}")
        }
    }

    private fun execute(command: String): ShellResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ShellResult(exitCode, output.trim())
    }

    private fun queryPackagePaths(packageName: String): List<String> {
        val result = execute("pm path ${shellQuote(packageName)}")
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.output.lineSequence()
            .mapNotNull { line ->
                val prefix = "package:"
                if (!line.startsWith(prefix)) return@mapNotNull null
                line.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
            }
            .toList()
    }

    private fun queryPackageDetails(packageName: String): PackageDetails? {
        val result = execute("dumpsys package ${shellQuote(packageName)}")
        if (result.exitCode != 0 || result.output.isBlank()) {
            return null
        }

        val text = result.output
        val versionCode = Regex("versionCode=(\\d+)").find(text)
            ?.groupValues?.getOrNull(1)?.toLongOrNull()
        val versionName = Regex("versionName=([^\\s\\r\\n]+)").find(text)
            ?.groupValues?.getOrNull(1)
        val minSdk = Regex("minSdk=(\\d+)").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val targetSdk = Regex("targetSdk=(\\d+)").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val enabledState = Regex("enabled=(\\d+)").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val isEnabled = enabledState <= 1
        return PackageDetails(versionCode, versionName, minSdk, targetSdk, isEnabled)
    }

    private fun parseSessionId(output: String): Int {
        Regex("""\[(\d+)]""").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it
        }
        Regex("""(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it
        }
        throw IOException("Failed to parse session id: $output")
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun uniqueFile(dir: File, name: String): File {
        val baseName = name.ifEmpty { "package.apk" }
        var candidate = File(dir, baseName)
        var index = 1
        while (candidate.exists()) {
            val dot = baseName.lastIndexOf('.')
            val fileName = if (dot > 0) {
                baseName.substring(0, dot) + "-$index" + baseName.substring(dot)
            } else {
                baseName + "-$index"
            }
            candidate = File(dir, fileName)
            index += 1
        }
        return candidate
    }
}
