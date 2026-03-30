package io.github.vvb2060.packageinstaller.model

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.Intent_rename
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionInfo
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageInstaller_rename
import android.content.pm.PackageManager
import android.content.pm.PackageManager_rename
import android.content.pm.VersionedPackage
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.github.vvb2060.packageinstaller.R
import io.github.vvb2060.packageinstaller.model.Hook.wrap
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_CLOSE
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_CREATE
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_INFO
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_NOTFOUND
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_PARSE
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_SHIZUKU
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_SPLIT
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_PRIVILEGED
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_WRITE
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File
import java.io.IOException

class InstallRepository(private val context: Application) {
    private val TAG = InstallRepository::class.java.simpleName
    val installResult = MutableLiveData<InstallStage>()
    val stagingProgress = MutableLiveData<Int>()
    private lateinit var packageManager: PackageManager
    private lateinit var packageInstaller: PackageInstaller
    private var stagedSessionId = SessionInfo.INVALID_ID
    private var callingUid = Process.INVALID_UID
    private lateinit var intent: Intent
    private var apkLite: ApkLite? = null
    private var oldApkInfo: PackageInfo? = null
    private var privilegedMode = PrivilegedMode.SHIZUKU

    private enum class PrivilegedMode {
        SHIZUKU,
        ROOT,
    }

    private fun getPackageInfoSafely(packageName: String): PackageInfo? {
        val flags = if (privilegedMode == PrivilegedMode.ROOT) {
            0
        } else {
            PackageManager_rename.MATCH_KNOWN_PACKAGES
        }
        val info = try {
            packageManager.getPackageInfo(packageName, flags)
        } catch (_: SecurityException) {
            null
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (info != null) {
            return info
        }
        if (privilegedMode == PrivilegedMode.ROOT) {
            return RootInstallHelper.getInstalledPackageInfo(packageName, packageManager)
        }
        return info
    }

    fun preCheck(intent: Intent): Boolean {
        val binderReady = Shizuku.pingBinder()
        val shizukuReady = binderReady
            && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            && Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED
        if (shizukuReady) {
            privilegedMode = PrivilegedMode.SHIZUKU
            this.intent = intent
            Log.v(TAG, "Intent: $intent")

            Hook.init(context)
            packageManager = Hook.pm
            packageInstaller = Hook.installer
            Hook.disableAdbVerify(context)
            PreferredActivity.set(context.packageManager)

            installResult.value = InstallParse()
            return true
        }

        if (RootInstallHelper.isAvailable()) {
            privilegedMode = PrivilegedMode.ROOT
            this.intent = intent
            Log.v(TAG, "Intent: $intent")

            packageManager = context.packageManager
            installResult.value = InstallParse()
            return true
        }

        if (shizukuReady || binderReady) {
            installResult.value = InstallAborted(
                ABORT_SHIZUKU,
                context.packageManager.getLaunchIntentForPackage(
                    ShizukuProvider.MANAGER_APPLICATION_ID
                )
            )
        }

        if (!binderReady) {
            installResult.value = InstallAborted(ABORT_PRIVILEGED)
        }
        return false
    }

    fun parseUri() {
        var uri = intent.data
        if (uri == null) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            intent.setData(uri)
        }
        if (uri != null && "package" == uri.scheme) {
            val packageName = uri.schemeSpecificPart
            installResult.postValue(processPackageUri(packageName))
            return
        }
        if (uri != null && "market" == uri.scheme && uri.authority == "details") {
            uri.getQueryParameter("id")?.let {
                installResult.postValue(processPackageUri(it))
                return
            }
        }
        if (uri != null && ContentResolver.SCHEME_CONTENT == uri.scheme) {
            installResult.postValue(processContentUri(uri))
            return
        }
        if (uri == null) {
            intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.let {
                installResult.postValue(processPackageUri(it))
                return
            }
        }
        installResult.postValue(InstallAborted(ABORT_INFO))
    }

    fun install(setInstaller: Boolean, commit: Boolean, full: Boolean, removeSplit: Boolean) {
        if (privilegedMode == PrivilegedMode.ROOT) {
            installWithRoot(setInstaller, commit, full, removeSplit)
            return
        }
        val uri = intent.data
        installResult.postValue(InstallInstalling(apkLite!!))
        if (ContentResolver.SCHEME_CONTENT != uri?.scheme &&
            ContentResolver.SCHEME_FILE != uri?.scheme
        ) {
            installPackageUri()
            return
        }

        if (stagedSessionId == SessionInfo.INVALID_ID) {
            try {
                val params: SessionParams = createSessionParams(setInstaller, full)
                stagedSessionId = packageInstaller.createSession(params)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create a staging session", e)
                installResult.postValue(InstallAborted(ABORT_CREATE))
                return
            }
        }

        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.wrap()
            if (removeSplit) {
                session.removeSplit(apkLite!!.splitName!!)
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (apkLite!!.zip) {
                        PackageUtil.stageZip(session, afd) {
                            stagingProgress.postValue(it)
                        }
                    } else {
                        PackageUtil.stageApk(session, afd) {
                            stagingProgress.postValue(it)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            cleanupInstall()
            Log.e(TAG, "Could not stage APK.", e)
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (commit) {
            stagingProgress.postValue(101)
            commit()
        } else {
            installResult.postValue(InstallAborted(ABORT_CLOSE))
        }
    }

    private fun installWithRoot(
        setInstaller: Boolean,
        commit: Boolean,
        full: Boolean,
        removeSplit: Boolean,
    ) {
        val uri = intent.data
        installResult.postValue(InstallInstalling(apkLite!!))
        if (ContentResolver.SCHEME_CONTENT != uri?.scheme &&
            ContentResolver.SCHEME_FILE != uri?.scheme
        ) {
            installPackageUriWithRoot()
            return
        }

        val streams = try {
            when {
                removeSplit -> {
                    val old = oldApkInfo ?: throw IOException("Missing installed package info")
                    RootInstallHelper.prepareInstalledPackageStreams(old, apkLite!!.splitName)
                }
                stagedSessionId == SessionInfo.INVALID_ID && !full && oldApkInfo != null -> {
                    val installed = RootInstallHelper.prepareInstalledPackageStreams(oldApkInfo!!, null)
                    val source = RootInstallHelper.prepareSourceStreams(context, uri!!, apkLite!!)
                    installed + source
                }
                else -> RootInstallHelper.prepareSourceStreams(context, uri!!, apkLite!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare root install streams", e)
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (streams.isEmpty()) {
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (stagedSessionId == SessionInfo.INVALID_ID) {
            try {
                stagedSessionId = RootInstallHelper.createSession(
                    streams.sumOf { it.length },
                    resolveInstallerPackageName(setInstaller),
                    apkLite!!.packageName,
                    full
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create a root staging session", e)
                installResult.postValue(InstallAborted(ABORT_CREATE))
                return
            }
        }

        try {
            RootInstallHelper.writeSession(stagedSessionId, streams)
        } catch (e: Exception) {
            cleanupInstall()
            Log.e(TAG, "Could not stage APK with root.", e)
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (commit) {
            stagingProgress.postValue(101)
            commitRoot()
        } else {
            installResult.postValue(InstallAborted(ABORT_CLOSE))
        }
    }

    private fun installPackageUriWithRoot() {
        stagingProgress.postValue(101)
        try {
            RootInstallHelper.installExistingPackage(apkLite!!.packageName)
            setStageBasedOnResult(
                PackageInstaller.STATUS_SUCCESS,
                PackageManager_rename.INSTALL_SUCCEEDED,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install existing package with root", e)
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun hasApkPresentation(apk: ApkLite): Boolean {
        return apk.label != null && apk.icon != null
    }

    private fun mergeApkPresentation(apk: ApkLite, source: ApkLite?) {
        if (source == null) {
            return
        }
        if (apk.label == null) {
            apk.label = source.label
        }
        if (apk.icon == null) {
            apk.icon = source.icon
        }
    }

    private fun mergeArchivePresentation(
        apk: ApkLite,
        source: PackageUtil.ArchivePresentation?,
    ) {
        if (source == null) {
            return
        }
        if (apk.label == null) {
            apk.label = source.label
        }
        if (apk.icon == null) {
            apk.icon = source.icon
        }
    }

    private fun snapshotApkIcon(apk: ApkLite) {
        val icon = apk.icon ?: return
        apk.icon = try {
            icon.toBitmap().toDrawable(context.resources)
        } catch (_: OutOfMemoryError) {
            icon
        } catch (_: Exception) {
            icon
        }
    }

    private fun fillPresentationFromInstalledInfo(apk: ApkLite, info: PackageInfo?) {
        val appInfo = info?.applicationInfo ?: return
        if (apk.label == null) {
            apk.label = runCatching {
                appInfo.loadLabel(packageManager).toString()
            }.getOrNull()
        }
        if (apk.icon == null) {
            apk.icon = runCatching {
                appInfo.loadIcon(packageManager)
            }.getOrNull()
        }
    }

    private fun fillPresentationFromSourcePath(apk: ApkLite, sourcePath: String?) {
        if (sourcePath.isNullOrEmpty() || hasApkPresentation(apk)) {
            return
        }

        if (privilegedMode != PrivilegedMode.ROOT) {
            return
        }

        val presentation = runCatching {
            RootInstallHelper.resolvePresentationFromSourcePath(context, packageManager, sourcePath)
        }.getOrNull()
        mergeArchivePresentation(apk, presentation)
    }

    private fun fillLabelFromDisplayName(apk: ApkLite, uri: Uri) {
        if (apk.label != null) {
            return
        }
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    apk.label = cursor.getString(displayNameIndex)
                }
            }
        }
    }

    private fun parseContentUriWithAppCopy(uri: Uri): ApkLite? {
        val tempDir = File(context.cacheDir, "content-parse-${System.nanoTime()}")
        val sourceFile = File(tempDir, "source.apk")
        return try {
            sourceFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                sourceFile.outputStream().use { output ->
                    input.copyTo(output, 8 * 1024)
                }
            } ?: return null

            val apk = PackageUtil.parseZipFromFile(sourceFile) ?: return null
            if (!hasApkPresentation(apk)) {
                mergeArchivePresentation(
                    apk,
                    PackageUtil.resolveArchivePresentation(
                        packageManager,
                        sourceFile,
                        apk.zip,
                    )
                )
            }
            snapshotApkIcon(apk)
            apk
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse content uri via app copy", e)
            null
        } finally {
            RootInstallHelper.deleteRecursively(tempDir)
        }
    }

    private fun processContentUri(uri: Uri): InstallStage {
        Log.v(TAG, "content URI: $uri")
        packageManager.resolveContentProvider(uri.authority!!, 0)?.also { info ->
            callingUid = info.applicationInfo.uid
        }
        var apk = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                PackageUtil.parseZipFromFd(afd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse APK from content URI: $uri", e)
            null
        }
        if (apk == null || !hasApkPresentation(apk)) {
            val appParsed = parseContentUriWithAppCopy(uri)
            if (apk == null) {
                apk = appParsed
            } else {
                mergeApkPresentation(apk, appParsed)
            }
        }
        if (privilegedMode == PrivilegedMode.ROOT && (apk?.let { hasApkPresentation(it) } != true)) {
            val rootParsed = parseContentUriWithRoot(uri)
            if (apk == null) {
                apk = rootParsed
            } else {
                mergeApkPresentation(apk, rootParsed)
            }
        }
        if (apk == null) {
            return InstallAborted(ABORT_PARSE)
        }
        val old = getPackageInfoSafely(apk.packageName)
        oldApkInfo = old
        var full = true
        if (apk.isSplit()) {
            if (privilegedMode == PrivilegedMode.SHIZUKU) {
                for (item in packageInstaller.allSessions) {
                    if (item.isActive && item.appPackageName == apk.packageName) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // noinspection NewApi
                            if (item.installerUid != Shizuku.getUid()) {
                                continue
                            }
                        } else {
                            if (item.installReason != PackageManager.INSTALL_REASON_USER) continue
                            item as PackageInstaller_rename.SessionInfo
                            if (item.installFlags and PackageManager_rename.INSTALL_FROM_ADB == 0) {
                                continue
                            }
                        }
                        val info = packageInstaller.getSessionInfo(item.sessionId)!!
                        stagedSessionId = info.sessionId
                        full = info.mode == SessionParams.MODE_FULL_INSTALL
                        apk.label = info.appLabel as String?
                        apk.icon = info.appIcon?.toDrawable(context.resources)
                        break
                    }
                }
            } else if (privilegedMode == PrivilegedMode.ROOT) {
                val rootSession = RootInstallHelper.getActiveSession(apk.packageName)
                if (rootSession != null) {
                    stagedSessionId = rootSession.first
                    full = rootSession.second
                }
            }
            if (stagedSessionId == SessionInfo.INVALID_ID) {
                if (old != null && old.longVersionCode == apk.versionCode) {
                    full = false
                } else {
                    return InstallAborted(ABORT_SPLIT)
                }
            }
        }
        if (old != null) {
            fillPresentationFromInstalledInfo(apk, old)
            // use this field to store the installer package name
            old.sharedUserId = try {
                packageManager.getInstallerPackageName(apk.packageName)
            } catch (e: Exception) {
                null
            }
        }
        if (!hasApkPresentation(apk) && privilegedMode == PrivilegedMode.ROOT) {
            fillPresentationFromSourcePath(apk, old?.applicationInfo?.sourceDir)
        }
        fillLabelFromDisplayName(apk, uri)
        snapshotApkIcon(apk)
        apkLite = apk
        val skipCreate = stagedSessionId != SessionInfo.INVALID_ID
        return InstallUserAction(apk, old, full, skipCreate)
    }

    private fun parseContentUriWithRoot(uri: Uri): ApkLite? {
        return runCatching {
            val apk = RootInstallHelper.parseContentUriWithMetadata(context, packageManager, uri)
                ?: parseContentUriWithAppCopy(uri)
            if (apk != null) {
                snapshotApkIcon(apk)
            }
            apk
        }.getOrElse {
            Log.e(TAG, "Failed to parse content uri with root", it)
            null
        }
    }

    private fun processPackageUri(packageName: String): InstallStage {
        val info = getPackageInfoSafely(packageName)
        if (info == null) {
            Log.e(TAG, "Requested package not available.")
            return InstallAborted(ABORT_NOTFOUND)
        }

        val apk = PackageUtil.getApkLite(packageManager, info)
        fillPresentationFromInstalledInfo(apk, info)
        if (privilegedMode == PrivilegedMode.ROOT && !hasApkPresentation(apk)) {
            fillPresentationFromSourcePath(apk, info.applicationInfo?.sourceDir)
        }
        snapshotApkIcon(apk)

        apkLite = apk
        oldApkInfo = info

        val path = info.applicationInfo!!.sourceDir
        if (path == null || !File(path).exists()) {
            queryZipUri(info)?.let {
                intent.setData(it)
                return processContentUri(it)
            }
        } else if (info.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED != 0) {
            return PackageUserAction(apk, info)
        }
        return InstallUserAction(apk, info)
    }

    private fun queryZipUri(info: PackageInfo): Uri? {
        val name = "${info.packageName}-${info.longVersionCode}.zip"
        val name2 = "${info.packageName}-${info.longVersionCode} (%).zip"
        val dir = Environment.DIRECTORY_DOCUMENTS + File.separator +
            context.getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cr = context.contentResolver
            val tableUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(name, name2)
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            cr.query(tableUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(index)
                    return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
                }
            }
        } else {
            val file = File(context.getExternalFilesDir(dir), name)
            if (file.exists()) {
                return file.toUri()
            }
        }
        return null
    }

    private fun installPackageUri() {
        stagingProgress.postValue(101)
        try {
            val pm = packageManager as PackageManager_rename
            pm.installExistingPackage(apkLite!!.packageName, PackageManager.INSTALL_REASON_USER)
            setStageBasedOnResult(
                PackageInstaller.STATUS_SUCCESS,
                PackageManager_rename.INSTALL_SUCCEEDED,
                null
            )
        } catch (e: PackageManager.NameNotFoundException) {
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun commitRoot() {
        try {
            RootInstallHelper.commitSession(stagedSessionId)
            setStageBasedOnResult(
                PackageInstaller.STATUS_SUCCESS,
                PackageManager_rename.INSTALL_SUCCEEDED,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit root staging session", e)
            cleanupInstall()
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun resolveInstallerPackageName(setInstaller: Boolean): String {
        var installer = context.packageName
        if (setInstaller) {
            installer = if (privilegedMode == PrivilegedMode.ROOT) {
                if (RootInstallHelper.isPackageInstalled("com.android.vending")) {
                    "com.android.vending"
                } else {
                    "com.android.shell"
                }
            } else {
                try {
                    packageManager.getPackageInfo("com.android.vending", PackageManager.MATCH_SYSTEM_ONLY)
                    "com.android.vending"
                } catch (_: PackageManager.NameNotFoundException) {
                    "com.android.shell"
                }
            }
        }
        return installer
    }

    private fun createSessionParams(setInstaller: Boolean, full: Boolean): SessionParams {
        val mode = if (full) {
            SessionParams.MODE_FULL_INSTALL
        } else {
            SessionParams.MODE_INHERIT_EXISTING
        }
        val params = SessionParams(mode)
        val installer = resolveInstallerPackageName(setInstaller)
        if (setInstaller) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        } else {
            val referrerUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_REFERRER)
            params.setReferrerUri(referrerUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val source = if (referrerUri != null) PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                else PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                params.setPackageSource(source)
            }
            params.setOriginatingUri(intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI))
            params.setOriginatingUid(intent.getIntExtra(Intent_rename.EXTRA_ORIGINATING_UID, callingUid))
        }
        // noinspection NewApi
        params.setInstallerPackageName(installer)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        params.setAppPackageName(apkLite!!.packageName)
        if (apkLite!!.needSplit()) {
            params.setAppIcon(apkLite!!.icon?.toBitmap())
            params.setAppLabel(apkLite!!.label)
        }

        val p = params as PackageInstaller_rename.SessionParams
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_ALLOW_TEST
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REPLACE_EXISTING
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REQUEST_DOWNGRADE
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_FULL_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.installFlags = p.installFlags or PackageManager_rename.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK
            p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REQUEST_UPDATE_OWNERSHIP
        }
        return params
    }

    private fun commit() {
        val receiver = LocalIntentReceiver(::setStageBasedOnResult)
        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.wrap()
            // noinspection RequestInstallPackagesPolicy
            session.commit(receiver.intentSender as IntentSender)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit staged session", e)
            cleanupInstall()
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun setStageBasedOnResult(statusCode: Int, legacyStatus: Int, message: String?) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            var intent = try {
                packageManager.getLaunchIntentForPackage(apkLite!!.packageName)
            } catch (e: Exception) {
                null
            }
            if (intent == null && privilegedMode == PrivilegedMode.ROOT) {
                if (RootInstallHelper.hasLauncherActivity(apkLite!!.packageName)) {
                    intent = Intent("io.github.vvb2060.packageinstaller.ROOT_LAUNCH").setPackage(apkLite!!.packageName)
                }
            }
            getPackageInfoSafely(apkLite!!.packageName)?.let { info ->
                fillPresentationFromInstalledInfo(apkLite!!, info)
                if (privilegedMode == PrivilegedMode.ROOT && !hasApkPresentation(apkLite!!)) {
                    fillPresentationFromSourcePath(apkLite!!, info.applicationInfo?.sourceDir)
                }
                snapshotApkIcon(apkLite!!)
            }
            installResult.postValue(InstallSuccess(apkLite!!, intent))
        } else {
            installResult.postValue(InstallFailed(apkLite!!, legacyStatus, statusCode, message))
        }
    }

    fun cleanupInstall() {
        if (stagedSessionId > 0) {
            try {
                if (privilegedMode == PrivilegedMode.ROOT) {
                    RootInstallHelper.abandonSession(stagedSessionId)
                } else {
                    packageInstaller.abandonSession(stagedSessionId)
                }
            } catch (_: Exception) {
            }
            stagedSessionId = 0
        }
    }

    fun archivePackage(info: PackageInfo, uninstall: Boolean) {
        installResult.postValue(InstallInstalling(apkLite!!))

        val name = "${info.packageName}-${info.longVersionCode}.zip"
        val dir = Environment.DIRECTORY_DOCUMENTS + File.separator +
            context.getString(R.string.app_name)
        var path = ""
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, true)
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            }
            val tableUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            cr.insert(tableUri, values)
        } else {
            val file = File(context.getExternalFilesDir(dir), name)
            path = file.absolutePath
            file.toUri()
        }
        if (uri == null) {
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        val list = ArrayList<File>()
        list.add(File(info.applicationInfo!!.sourceDir))
        info.applicationInfo!!.splitSourceDirs?.let {
            for (split in it) {
                list.add(File(split))
            }
        }

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "wt")?.use { afd ->
                PackageUtil.archivePackage(list, afd) {
                    stagingProgress.postValue(it)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to archive package", e)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                context.contentResolver.delete(uri, null, null)
            } else {
                File(uri.path!!).delete()
            }
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, false)
            }
            cr.update(uri, values, null, null)
            cr.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (index != -1) {
                        path = cursor.getString(index)
                    }
                }
            }
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/zip")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (uninstall) {
            if (privilegedMode == PrivilegedMode.ROOT) {
                try {
                    RootInstallHelper.uninstallPackage(info.packageName, true)
                    installResult.postValue(InstallSuccess(apkLite!!, intent, path))
                } catch (e: Exception) {
                    installResult.postValue(InstallSuccess(apkLite!!, intent, "$path\n\n${e.message}"))
                }
                return
            }
            val receiver = LocalIntentReceiver { statusCode, legacyCode, msg ->
                if (statusCode == PackageInstaller.STATUS_SUCCESS) {
                    installResult.postValue(InstallSuccess(apkLite!!, intent, path))
                } else {
                    installResult.postValue(InstallSuccess(apkLite!!, intent, "$path\n\n$msg"))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    // noinspection MissingPermission
                    packageInstaller.requestArchive(
                        info.packageName,
                        receiver.intentSender as IntentSender
                    )
                    return
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Failed to archive for package ${info.packageName}", e)
                }
            }
            // noinspection MissingPermission NewApi
            packageInstaller.uninstall(
                VersionedPackage(info.packageName, info.longVersionCode),
                PackageManager_rename.DELETE_KEEP_DATA,
                receiver.intentSender as IntentSender
            )
            val userId = Os.getuid() / 100000
            val pm = packageManager as PackageManager_rename
            pm.deleteApplicationCacheFilesAsUser(info.packageName, userId, null)
        } else {
            installResult.postValue(InstallSuccess(apkLite!!, intent, path))
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        if (privilegedMode == PrivilegedMode.ROOT) {
            runCatching {
                RootInstallHelper.setPackageEnabled(packageName, enabled)
            }.onFailure {
                Log.e(TAG, "Failed to update package state for $packageName", it)
            }
            return
        }
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
        packageManager.setApplicationEnabledSetting(packageName, newState, 0)
    }

}
