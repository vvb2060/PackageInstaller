package io.github.vvb2060.packageinstaller.model

import android.content.Context
import android.content.ContentResolver
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.Os
import java.io.File
import java.io.InputStream
import java.io.IOException

object RootInstallHelper {
    sealed class InstallStream(open val name: String, open val length: Long) {
        data class RootPath(
            override val name: String,
            override val length: Long,
            val sourcePath: String,
        ) : InstallStream(name, length)

        data class Reader(
            override val name: String,
            override val length: Long,
            val openStream: () -> InputStream,
        ) : InstallStream(name, length)
    }

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

    fun writeSession(sessionId: Int, streams: List<InstallStream>) {
        for (stream in streams) {
            if (stream.length <= 0L) {
                throw IOException("Invalid stream length for ${stream.name}: ${stream.length}")
            }
            when (stream) {
                is InstallStream.RootPath -> writeSessionFromRootPath(sessionId, stream)
                is InstallStream.Reader -> writeSessionFromReader(sessionId, stream)
            }
        }
    }

    private fun writeSessionFromRootPath(sessionId: Int, stream: InstallStream.RootPath) {
        val result = execute(
            "cat ${shellQuote(stream.sourcePath)} | pm install-write -S ${stream.length} $sessionId ${shellQuote(stream.name)} -"
        )
        if (result.exitCode != 0 || result.output.contains("Exception") || result.output.contains("Error")) {
            throw IOException("Failed to write ${stream.name}: ${result.output}")
        }
    }

    private fun writeSessionFromReader(sessionId: Int, stream: InstallStream.Reader) {
        val command = "pm install-write -S ${stream.length} $sessionId ${shellQuote(stream.name)} -"
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        try {
            stream.openStream().use { input ->
                process.outputStream.use { output ->
                    input.copyTo(output, 8 * 1024)
                }
            }
        } catch (e: Exception) {
            process.destroy()
            throw IOException("Failed to stream ${stream.name}", e)
        }

        val result = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode != 0 || result.contains("Exception") || result.contains("Error")) {
            throw IOException("Failed to write ${stream.name}: $result")
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

        val pi = runCatching { pm.getPackageArchiveInfo(basePath, 0) }.getOrNull()
        
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

    fun prepareSourceStreams(
        context: Context,
        uri: Uri,
        apk: ApkLite,
    ): List<InstallStream> {
        if (!apk.zip) {
            val filePath = uri.path
            if (uri.scheme == ContentResolver.SCHEME_FILE && !filePath.isNullOrEmpty()) {
                val source = File(filePath)
                val length = queryFileLength(filePath)
                val name = source.name.substringBeforeLast(".apk", source.name)
                return listOf(InstallStream.RootPath(name, length, filePath))
            }

            val length = queryContentLength(context, uri)
            val name = "${apk.packageName}-${System.nanoTime()}"
            return listOf(
                InstallStream.Reader(name, length) {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Failed to open source: $uri")
                }
            )
        }

        if (uri.scheme == ContentResolver.SCHEME_FILE && !uri.path.isNullOrEmpty()) {
            val sourceFile = File(uri.path!!)
            val entries = PackageUtil.listInstallArchiveEntries(sourceFile)
            if (entries.isEmpty()) {
                throw IOException("No install entries in archive: ${sourceFile.absolutePath}")
            }
            return entries.map { entry ->
                InstallStream.Reader(entry.installName, entry.length) {
                    PackageUtil.openArchiveEntryStream(sourceFile, entry.archivePath)
                }
            }
        }

        val entries = PackageUtil.listInstallArchiveEntries(context, uri)
        if (entries.isEmpty()) {
            throw IOException("No install entries in archive: $uri")
        }
        return entries.map { entry ->
            InstallStream.Reader(entry.installName, entry.length) {
                PackageUtil.openArchiveEntryStream(context, uri, entry.archivePath)
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

    fun prepareInstalledPackageStreams(
        info: PackageInfo,
        excludeSplitName: String? = null,
    ): List<InstallStream> {
        val streams = ArrayList<InstallStream>()
        val sourcePath = info.applicationInfo?.sourceDir
        if (!sourcePath.isNullOrEmpty()) {
            val sourceName = File(sourcePath).name
            val installName = sourceName.substringBeforeLast(".apk", sourceName)
            streams.add(InstallStream.RootPath(installName, queryFileLength(sourcePath), sourcePath))
        }

        val splitSourceDirs = info.applicationInfo?.splitSourceDirs
        val splitNames = info.splitNames
        if (splitSourceDirs != null) {
            for (index in splitSourceDirs.indices) {
                val splitPath = splitSourceDirs[index]
                val splitName = splitNames?.getOrNull(index)
                if (excludeSplitName != null && splitName == excludeSplitName) continue
                val splitFileName = File(splitPath).name
                val installName = splitFileName.substringBeforeLast(".apk", splitFileName)
                streams.add(InstallStream.RootPath(installName, queryFileLength(splitPath), splitPath))
            }
        }
        return streams
    }

    fun resolvePresentationFromSourcePath(
        context: Context,
        pm: PackageManager,
        sourcePath: String,
    ): PackageUtil.ArchivePresentation? {
        val sourceFile = File(sourcePath)
        if (sourceFile.exists()) {
            return resolvePresentationFromFile(pm, sourceFile)
        }

        val tempDir = File(context.cacheDir, "root-meta-${System.nanoTime()}")
        val tempFile = File(tempDir, "source.apk")
        return try {
            if (!copyFileToFile(sourcePath, tempFile)) {
                null
            } else {
                resolvePresentationFromFile(pm, tempFile)
            }
        } finally {
            deleteRecursively(tempDir)
        }
    }

    fun parseContentUriWithMetadata(
        context: Context,
        pm: PackageManager,
        uri: Uri,
    ): ApkLite? {
        val tempDir = File(context.cacheDir, "root-parse-${System.nanoTime()}")
        val sourceFile = File(tempDir, "source.apk")
        return try {
            val parsed = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    PackageUtil.parseZipFromFd(afd)
                }
            }.getOrNull()
            val apk = parsed ?: run {
                val copied = copyContentUriToFileWithApp(context, uri, sourceFile)
                    || copyContentUriToFile(uri, sourceFile)
                if (!copied) {
                    return null
                }
                PackageUtil.parseZipFromFile(sourceFile)
            } ?: return null

            if (apk.label == null || apk.icon == null) {
                val fromUri = runCatching {
                    PackageUtil.resolveUriArchivePresentation(context, pm, uri, apk.zip)
                }.getOrNull()
                if (apk.label == null) {
                    apk.label = fromUri?.label
                }
                if (apk.icon == null) {
                    apk.icon = fromUri?.icon
                }
            }

            if ((apk.label == null || apk.icon == null) && sourceFile.exists()) {
                val fromFile = resolvePresentationFromFile(pm, sourceFile)
                if (apk.label == null) {
                    apk.label = fromFile?.label
                }
                if (apk.icon == null) {
                    apk.icon = fromFile?.icon
                }
            }
            apk
        } finally {
            deleteRecursively(tempDir)
        }
    }

    private fun copyContentUriToFileWithApp(
        context: Context,
        uri: Uri,
        targetFile: File,
    ): Boolean {
        return try {
            targetFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output, 8 * 1024)
                }
            } ?: return false
            targetFile.exists() && targetFile.length() > 0L
        } catch (_: Exception) {
            runCatching { targetFile.delete() }
            false
        }
    }

    fun deleteRecursively(target: File) {
        runCatching {
            if (target.exists()) {
                target.deleteRecursively()
            }
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
        val isEnabled = queryPackageEnabled(packageName)
        return PackageDetails(versionCode, versionName, minSdk, targetSdk, isEnabled)
    }

    private fun queryPackageEnabled(packageName: String): Boolean {
        val result = execute("pm list packages -d ${shellQuote(packageName)}")
        if (result.exitCode != 0) {
            return true
        }
        return result.output.lineSequence().none { it.trim() == "package:$packageName" }
    }

    private fun queryFileLength(path: String): Long {
        val result = execute("stat -c %s ${shellQuote(path)}")
        if (result.exitCode == 0) {
            val size = result.output.lineSequence().firstOrNull()?.trim()?.toLongOrNull()
            if (size != null && size > 0L) {
                return size
            }
        }
        val fallback = File(path).length()
        if (fallback > 0L) {
            return fallback
        }
        throw IOException("Failed to resolve file length: $path")
    }

    private fun queryContentLength(context: Context, uri: Uri): Long {
        val afdLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: -1L
        if (afdLength > 0L) {
            return afdLength
        }

        val pfdLength = context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: -1L
        if (pfdLength > 0L) {
            return pfdLength
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            var count = 0L
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                count += read
            }
            if (count > 0L) {
                return count
            }
        }

        throw IOException("Failed to resolve content length: $uri")
    }

    private fun resolvePresentationFromFile(
        pm: PackageManager,
        sourceFile: File,
    ): PackageUtil.ArchivePresentation? {
        val parsed = runCatching { PackageUtil.parseZipFromFile(sourceFile) }.getOrNull()
        if (parsed != null && (parsed.label != null || parsed.icon != null)) {
            return PackageUtil.ArchivePresentation(parsed.label, parsed.icon)
        }
        return PackageUtil.resolveArchivePresentation(pm, sourceFile, parsed?.zip == true)
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

}
