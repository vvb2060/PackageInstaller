package io.github.vvb2060.packageinstaller.model

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ApkAssets
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.content.res.`AssetManager$Builder`
import android.content.res.Resources
import android.content.res.XmlBlock
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.io.IOUtils
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.util.Locale
import java.util.function.Consumer

object PackageUtil {
    data class ArchivePresentation(
        val label: String?,
        val icon: Drawable?,
    )

    data class InstallArchiveEntry(
        val archivePath: String,
        val installName: String,
        val length: Long,
    )

    fun getApkLite(pm: PackageManager, pkgInfo: PackageInfo): ApkLite {
        val info = pkgInfo.applicationInfo ?: ApplicationInfo().apply {
            packageName = pkgInfo.packageName
        }
        return ApkLite(
            pkgInfo.packageName,
            null,
            null,
            null,
            pkgInfo.getLongVersionCode(),
            pkgInfo.versionName ?: "null",
            info.minSdkVersion.toString(),
            info.targetSdkVersion.toString(),
            info.loadLabel(pm).toString(),
            info.loadIcon(pm),
        ).apply { zip = true }
    }

    fun parseZipFromFd(afd: AssetFileDescriptor): ApkLite? {
        ZipFile.builder()
            .setIgnoreLocalFileHeader(true)
            .setSeekableByteChannel(afd.createInputStream().getChannel())
            .get().use { zipFile ->
                return parseZip(zipFile) {
                    getAppLiteFromApkFd(
                        afd.getFileDescriptor(),
                        afd.toString(),
                        afd.getLength(),
                    )
                }
            }

    }

    fun parseZipFromFile(file: File): ApkLite? {
        FileInputStream(file).use { input ->
            ZipFile.builder()
                .setIgnoreLocalFileHeader(true)
                .setSeekableByteChannel(input.getChannel())
                .get().use { zipFile ->
                    return parseZip(zipFile) {
                        getAppLiteFromApkFd(
                            input.getFD(),
                            file.absolutePath,
                            file.length(),
                        )
                    }
                }
        }
    }

    private fun parseZip(
        zipFile: ZipFile,
        parseSelfApk: () -> ApkLite?,
    ): ApkLite? {
        val xml = zipFile.getEntry("AndroidManifest.xml")
        if (xml != null) {
            try {
                parseSelfApk()?.let {
                    return it
                }
            } catch (_: Throwable) {
            }
            zipFile.getInputStream(xml).use { input ->
                val bytes = IOUtils.toByteArray(input)
                return getAppLiteFromXmlBytes(bytes)
            }
        }

        var base = zipFile.getEntry("base.apk")
            ?: zipFile.getEntry("splits/base-master_2.apk")
        if (base == null) {
            for (entry in zipFile.getEntries()) {
                if (entry.getName().endsWith(".apk")) {
                    if (base == null) {
                        base = entry
                    } else {
                        return null
                    }
                }
            }
            if (base == null) {
                return null
            }
        }

        ZipArchiveInputStream(zipFile.getInputStream(base)).use { input ->
            var innerEntry = input.getNextEntry()
            while (innerEntry != null) {
                if (innerEntry.getName() == "AndroidManifest.xml") {
                    val bytes = IOUtils.toByteArray(input)
                    return getAppLiteFromXmlBytes(bytes)?.apply {
                        zip = true
                    }
                }
                innerEntry = input.getNextEntry()
            }
            return null
        }
    }

    private fun getAppLiteFromXmlBytesLink(xmlBytes: ByteArray): ApkLite? {
        XmlBlock(xmlBytes).use {
            it.newParser().use { parser ->
                val res = Resources.getSystem()
                return parsingAndroidManifest(parser, res)
            }
        }
    }

    private fun getAppLiteFromXmlBytes(xmlBytes: ByteArray): ApkLite? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getAppLiteFromXmlBytesLink(xmlBytes)
        }
        val clazz = Class.forName("android.content.res.XmlBlock")
        clazz.getConstructor(ByteArray::class.java).run {
            setAccessible(true)
            newInstance(xmlBytes) as AutoCloseable
        }.use {
            clazz.getDeclaredMethod("newParser").run {
                invoke(it) as XmlResourceParser
            }.use { parser ->
                val res = Resources.getSystem()
                return parsingAndroidManifest(parser, res)
            }
        }
    }

    private fun getAppLiteFromApkFd(fd: FileDescriptor, name: String, length: Long): ApkLite? {
        val assets = try {
            try {
                android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
            } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ApkAssets.loadFromFd(fd, name, 0, length, 0, null)
            } else {
                ApkAssets.loadFromFd(fd, name, false, false)
            }
        } catch (_: Throwable) {
            return null
        }
        val am = `AssetManager$Builder`()
            .addApkAssets(assets)
            .build() as AssetManager
        val res = Resources.getSystem()
        val apkResources = Resources(am, res.getDisplayMetrics(), res.getConfiguration())

        val xmlParser = assets.openXml("AndroidManifest.xml")
        return xmlParser?.use {
            parsingAndroidManifest(xmlParser, apkResources)
        }
    }

    private fun parsingAndroidManifest(parser: XmlResourceParser, res: Resources): ApkLite? {
        val ns = "http://schemas.android.com/apk/res/android"
        var icon: Drawable? = null
        var label: String? = null
        var packageName: String? = null
        var splitName: String? = null
        var versionCode = 0
        var versionCodeMajor = 0
        var versionName: String? = null
        var requiredSplitTypes: String? = null
        var splitTypes: String? = null
        var minSdk: String? = null
        var targetSdk: String? = null

        var eventType = parser.getEventType()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.getName() == "application") {
                val iconId = parser.getAttributeResourceValue(ns, "icon", 0)
                icon = try {
                    res.getDrawable(iconId, null)
                } catch (_: Resources.NotFoundException) {
                    null
                }
                val labelId = parser.getAttributeResourceValue(ns, "label", 0)
                label = if (labelId == 0) {
                    parser.getAttributeValue(ns, "label")
                } else try {
                    res.getString(labelId)
                } catch (_: Resources.NotFoundException) {
                    null
                }
                if (packageName != null && minSdk != null) {
                    break
                }
            } else if (eventType == XmlPullParser.START_TAG && parser.getName() == "manifest") {
                packageName = parser.getAttributeValue(null, "package")
                splitName = parser.getAttributeValue(null, "split")
                versionCode = parser.getAttributeIntValue(ns, "versionCode", 0)
                versionCodeMajor = parser.getAttributeIntValue(ns, "versionCodeMajor", 0)
                versionName = parser.getAttributeValue(ns, "versionName")
                requiredSplitTypes =
                    parser.getAttributeValue(ns, "requiredSplitTypes")
                splitTypes = parser.getAttributeValue(ns, "splitTypes")
            } else if (eventType == XmlPullParser.START_TAG && parser.getName() == "uses-sdk") {
                minSdk = parser.getAttributeValue(ns, "minSdkVersion")
                targetSdk = parser.getAttributeValue(ns, "targetSdkVersion")
            }
            eventType = parser.next()
        }
        if (packageName == null) {
            return null
        }
        return ApkLite(
            packageName,
            splitName,
            requiredSplitTypes,
            splitTypes,
            ((versionCodeMajor.toLong()) shl 32) or ((versionCode.toLong()) and 0xffffffffL),
            versionName ?: "null",
            minSdk ?: "1",
            targetSdk ?: "0",
            label,
            icon
        )
    }

    private fun densityFilter(): List<String> {
        val allDpis = arrayOf(
            DisplayMetrics.DENSITY_LOW,
            DisplayMetrics.DENSITY_MEDIUM,
            DisplayMetrics.DENSITY_TV,
            DisplayMetrics.DENSITY_HIGH,
            DisplayMetrics.DENSITY_XHIGH,
            DisplayMetrics.DENSITY_XXHIGH,
            DisplayMetrics.DENSITY_XXXHIGH
        )
        val deviceDpi = Resources.getSystem().getDisplayMetrics().densityDpi
        val (higherOrEqual, lower) = allDpis.partition { it >= deviceDpi }
        val sortedDpis = higherOrEqual.sorted() + lower.sortedDescending()
        return sortedDpis.map {
            when (it) {
                DisplayMetrics.DENSITY_LOW -> "ldpi"
                DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
                DisplayMetrics.DENSITY_TV -> "tvdpi"
                DisplayMetrics.DENSITY_HIGH -> "hdpi"
                DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
                DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
                DisplayMetrics.DENSITY_XXXHIGH -> "xxxhdpi"
                else -> "unknown"
            }
        }
    }

    private fun languageFilter(): List<String> {
        val res = Resources.getSystem()
        val languageSet = LinkedHashSet<String>()
        val localeList = res.getConfiguration().getLocales()
        for (i in 0..<localeList.size()) {
            val locale = localeList.get(i)
            languageSet.add(locale.toLanguageTag().substringBefore('-'))
        }
        languageSet.add("base")
        val allLocal = res.getAssets().getLocales()
        for (tag in allLocal) {
            val locale = Locale.forLanguageTag(tag)
            languageSet.add(locale.toLanguageTag().substringBefore('-'))
        }
        return languageSet.toList()
    }

    private fun abiFilter(): List<String> {
        val abiSet = LinkedHashSet<String>()
        Build.SUPPORTED_ABIS.forEach { abi ->
            abiSet.add(abi.replace('-', '_'))
        }
        abiSet.addAll(arrayOf("armeabi_v7a", "x86", "x86_64", "arm64_v8a", "riscv64"))
        return abiSet.toList()
    }

    private fun ZipArchiveEntry.getLabel(): String {
        val name = this.getName().removeSuffix(".apk").removePrefix("split_")
        return if (name.startsWith("config.")) {
            name.removePrefix("config.")
        } else name.substringAfterLast(".config.")
    }

    private fun ZipArchiveEntry.getApksLabel(): String {
        val name = this.getName().substringAfterLast('/').removeSuffix(".apk")
        return if (name.startsWith("base-")) {
            name.removePrefix("base-")
        } else name.substringAfterLast("-")
    }

    private fun String.convert(): String? {
        return when (this) {
            "master" -> null
            "master_2" -> "base"
            "tl" -> "fil"
            "iw" -> "he"
            "ji" -> "yi"
            "in" -> "id"
            else -> this
        }
    }

    private fun filterZipEntries(zipFile: ZipFile): List<ZipArchiveEntry> {
        val isApks = zipFile.getEntry("toc.pb") != null
        val apkMap = mutableMapOf<String, MutableList<ZipArchiveEntry>>()
        zipFile.getEntries().asSequence()
            .filter { it.getName().endsWith(".apk") }
            .forEach {
                val label = if (isApks) {
                    it.getApksLabel().convert()
                } else {
                    it.getLabel().convert()
                }
                if (label == null) {
                    return@forEach
                }
                apkMap.getOrPut(label) { mutableListOf() }.add(it)
            }
        for (filter in arrayOf(abiFilter(), densityFilter(), languageFilter())) {
            var found = false
            for (name in filter) {
                if (apkMap.containsKey(name)) {
                    if (!found) {
                        found = true
                    } else if (name != "base") {
                        apkMap.remove(name)
                    }
                }
            }
        }
        return apkMap.values.flatten()
    }

    fun stageZip(
        session: PackageInstaller.Session,
        afd: AssetFileDescriptor,
        callback: Consumer<Int>,
    ) {
        ZipFile.builder()
            .setIgnoreLocalFileHeader(true)
            .setSeekableByteChannel(afd.createInputStream().getChannel())
            .get().use { zipFile ->
                val apks = filterZipEntries(zipFile)
                val total = apks.sumOf { it.getSize() }
                var totalRead = 0L
                val buffer = ByteArray(16 * 1024)
                callback.accept(0)
                apks.forEach { entry ->
                    val fileName = entry.getName().substringAfterLast('/')
                    session.openWrite(fileName, 0, entry.getSize()).use { out ->
                        zipFile.getInputStream(entry).use { instream ->
                            var numRead: Int
                            while (instream.read(buffer).also { numRead = it } != -1) {
                                out.write(buffer, 0, numRead)
                                totalRead += numRead
                                val fraction = totalRead.toFloat() / total.toFloat()
                                callback.accept((fraction * 100.0).toInt())
                            }
                            session.fsync(out)
                        }
                    }
                }
            }
    }

    fun stageApk(
        session: PackageInstaller.Session,
        afd: AssetFileDescriptor,
        callback: Consumer<Int>,
    ) {
        callback.accept(0)
        val total = afd.getLength()
        val instream = afd.createInputStream()
        var totalRead = 0L
        val fileName = System.currentTimeMillis().toString()
        session.openWrite(fileName, 0, total).use { out ->
            val buffer = ByteArray(16 * 1024)
            var numRead: Int
            while (instream.read(buffer).also { numRead = it } != -1) {
                out.write(buffer, 0, numRead)
                totalRead += numRead
                val fraction = totalRead.toFloat() / total.toFloat()
                callback.accept((fraction * 100.0).toInt())
            }
            session.fsync(out)
        }
    }

    fun extractInstallFiles(
        context: Context,
        uri: Uri,
        apk: ApkLite,
        targetDir: File,
    ): List<File> {
        targetDir.mkdirs()
        if (!apk.zip) {
            return listOf(copyUriToFile(context, uri, File(targetDir, apk.packageName + ".apk")))
        }

        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return emptyList()
        afd.use {
            ZipFile.builder()
                .setIgnoreLocalFileHeader(true)
                .setSeekableByteChannel(it.createInputStream().getChannel())
                .get().use { zipFile ->
                    return extractZipFiles(zipFile, targetDir)
                }
        }
    }

    fun extractInstallFilesFromFile(
        sourceFile: File,
        apk: ApkLite,
        targetDir: File,
    ): List<File> {
        targetDir.mkdirs()
        if (!apk.zip) {
            return listOf(copyFile(sourceFile, File(targetDir, apk.packageName + ".apk")))
        }
        FileInputStream(sourceFile).use { input ->
            ZipFile.builder()
                .setIgnoreLocalFileHeader(true)
                .setSeekableByteChannel(input.getChannel())
                .get().use { zipFile ->
                    return extractZipFiles(zipFile, targetDir)
                }
        }
    }

    private fun extractZipFiles(zipFile: ZipFile, targetDir: File): List<File> {
        val apks = filterZipEntries(zipFile)
        val files = ArrayList<File>(apks.size)
        for (entry in apks) {
            val file = File(targetDir, entry.getName().substringAfterLast('/'))
            zipFile.getInputStream(entry).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            files.add(file)
        }
        return files
    }

    private fun copyUriToFile(context: Context, uri: Uri, file: File): File {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Failed to open source: $uri")
        return file
    }

    private fun copyFile(source: File, target: File): File {
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    fun resolveArchivePresentation(
        pm: PackageManager,
        sourceFile: File,
        isArchive: Boolean,
    ): ArchivePresentation? {
        if (!sourceFile.exists()) {
            return null
        }

        var tempDir: File? = null
        val archiveFile = if (isArchive) {
            tempDir = File(sourceFile.parentFile, "meta-${System.nanoTime()}")
            extractBaseApkFile(sourceFile, tempDir!!)
        } else {
            sourceFile
        } ?: return null

        return try {
            val pi = readArchivePackageInfo(pm, archiveFile) ?: return null
            val appInfo = pi.applicationInfo ?: return null
            val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrNull()
            val icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()
            if (label == null && icon == null) {
                null
            } else {
                ArchivePresentation(label, icon)
            }
        } finally {
            tempDir?.deleteRecursively()
        }
    }

    private fun readArchivePackageInfo(pm: PackageManager, archiveFile: File): PackageInfo? {
        val pi = pm.getPackageArchiveInfo(archiveFile.absolutePath, 0) ?: return null
        val appInfo = pi.applicationInfo ?: return null
        appInfo.sourceDir = archiveFile.absolutePath
        appInfo.publicSourceDir = archiveFile.absolutePath
        return pi
    }

    private fun extractBaseApkFile(sourceFile: File, targetDir: File): File? {
        targetDir.mkdirs()
        FileInputStream(sourceFile).use { input ->
            ZipFile.builder()
                .setIgnoreLocalFileHeader(true)
                .setSeekableByteChannel(input.getChannel())
                .get().use { zipFile ->
                    val entry = findBaseApkEntry(zipFile) ?: return null
                    val simpleName = entry.getName().substringAfterLast('/').ifEmpty { "base.apk" }
                    val outFile = File(targetDir, simpleName)
                    zipFile.getInputStream(entry).use { entryInput ->
                        outFile.outputStream().use { output ->
                            entryInput.copyTo(output)
                        }
                    }
                    return outFile
                }
        }
    }

    private fun findBaseApkEntry(zipFile: ZipFile): ZipArchiveEntry? {
        var singleApk: ZipArchiveEntry? = null
        var apkCount = 0
        var fallback: ZipArchiveEntry? = null

        for (entry in zipFile.getEntries()) {
            if (!entry.getName().endsWith(".apk")) {
                continue
            }
            apkCount += 1
            if (singleApk == null) {
                singleApk = entry
            }

            val name = entry.getName().substringAfterLast('/')
            if (name == "base.apk") {
                return entry
            }
            if (fallback == null && (name.startsWith("base-") || name.startsWith("base_"))) {
                fallback = entry
            }
        }

        return fallback ?: if (apkCount == 1) singleApk else null
    }

    fun listInstallArchiveEntries(context: Context, uri: Uri): List<InstallArchiveEntry> {
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return emptyList()
        afd.use {
            ZipFile.builder()
                .setIgnoreLocalFileHeader(true)
                .setSeekableByteChannel(it.createInputStream().getChannel())
                .get().use { zipFile ->
                    return buildInstallArchiveEntries(zipFile)
                }
        }
    }

    fun listInstallArchiveEntries(sourceFile: File): List<InstallArchiveEntry> {
        FileInputStream(sourceFile).use { input ->
            ZipFile.builder()
                .setIgnoreLocalFileHeader(true)
                .setSeekableByteChannel(input.getChannel())
                .get().use { zipFile ->
                    return buildInstallArchiveEntries(zipFile)
                }
        }
    }

    fun openArchiveEntryStream(context: Context, uri: Uri, archivePath: String): InputStream {
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("Failed to open source: $uri")
        val zipFile = ZipFile.builder()
            .setIgnoreLocalFileHeader(true)
            .setSeekableByteChannel(afd.createInputStream().getChannel())
            .get()
        return openArchiveEntryStream(zipFile, archivePath, afd)
    }

    fun openArchiveEntryStream(sourceFile: File, archivePath: String): InputStream {
        val input = FileInputStream(sourceFile)
        val zipFile = ZipFile.builder()
            .setIgnoreLocalFileHeader(true)
            .setSeekableByteChannel(input.getChannel())
            .get()
        return openArchiveEntryStream(zipFile, archivePath, input)
    }

    fun resolveUriArchivePresentation(
        context: Context,
        pm: PackageManager,
        uri: Uri,
        isArchive: Boolean,
    ): ArchivePresentation? {
        if (!isArchive) {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
            afd.use {
                val apk = parseZipFromFd(it) ?: return null
                if (apk.label == null && apk.icon == null) {
                    return null
                }
                return ArchivePresentation(apk.label, apk.icon)
            }
        }

        val entries = listInstallArchiveEntries(context, uri)
        val base = entries.firstOrNull { it.installName == "base" } ?: entries.firstOrNull() ?: return null
        val tempFile = File(context.cacheDir, "meta-${System.nanoTime()}.apk")
        return try {
            openArchiveEntryStream(context, uri, base.archivePath).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            resolveArchivePresentation(pm, tempFile, false)
        } finally {
            tempFile.delete()
        }
    }

    private fun buildInstallArchiveEntries(zipFile: ZipFile): List<InstallArchiveEntry> {
        val selected = filterZipEntries(zipFile)
        val entries = ArrayList<InstallArchiveEntry>(selected.size)
        for (entry in selected) {
            val fileName = entry.getName().substringAfterLast('/').ifEmpty { "base.apk" }
            val installName = fileName.substringBeforeLast(".apk", fileName)
            val length = if (entry.getSize() > 0L) {
                entry.getSize()
            } else {
                zipFile.getInputStream(entry).use { stream ->
                    var count = 0L
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        count += read
                    }
                    count
                }
            }
            if (length <= 0L) {
                continue
            }
            entries.add(InstallArchiveEntry(entry.getName(), installName, length))
        }
        return entries
    }

    private fun openArchiveEntryStream(
        zipFile: ZipFile,
        archivePath: String,
        owner: AutoCloseable,
    ): InputStream {
        val entry = zipFile.getEntry(archivePath)
            ?: run {
                runCatching { zipFile.close() }
                runCatching { owner.close() }
                throw IOException("Entry not found: $archivePath")
            }
        val inner = zipFile.getInputStream(entry)
        return object : InputStream() {
            override fun read(): Int {
                return inner.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                return inner.read(b, off, len)
            }

            override fun close() {
                runCatching { inner.close() }
                runCatching { zipFile.close() }
                runCatching { owner.close() }
            }
        }
    }

    fun archivePackage(
        apks: List<File>,
        afd: AssetFileDescriptor,
        callback: Consumer<Int>,
    ) {
        val total = apks.sumOf { it.length() }
        var totalRead = 0L
        callback.accept(0)
        ZipArchiveOutputStream(afd.createOutputStream().getChannel()).use { out ->
            val buffer = ByteArray(16 * 1024)
            for (apk in apks) {
                val entry = ZipArchiveEntry(apk, apk.name)
                entry.setMethod(ZipMethod.XZ.getCode())
                entry.setSize(apk.length())
                out.putArchiveEntry(entry)
                FileInputStream(apk).use { instream ->
                    var numRead: Int
                    val xz = XZCompressorOutputStream(out, 3)
                    val crc = java.util.zip.CRC32()
                    while (instream.read(buffer).also { numRead = it } != -1) {
                        crc.update(buffer, 0, numRead)
                        xz.write(buffer, 0, numRead)
                        totalRead += numRead
                        val fraction = totalRead.toFloat() / total.toFloat()
                        callback.accept((fraction * 100.0).toInt())
                    }
                    xz.finish()
                    entry.setCrc(crc.getValue())
                }
                out.closeArchiveEntry()
            }
        }
    }

}
