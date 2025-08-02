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
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import io.github.vvb2060.packageinstaller.model.InstallAborted.Companion.ABORT_WRITE
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File
import java.io.IOException

class InstallRepository(private val context: Application) {
    private val TAG = InstallRepository::class.java.simpleName
    val installResult = MutableLiveData<InstallStage>()
    val stagingProgress = MutableLiveData<Int>()
    private val packageManager: PackageManager = context.packageManager
    private val packageInstaller: PackageInstaller = packageManager.packageInstaller
    private var stagedSessionId = SessionInfo.INVALID_ID
    private var callingUid = Process.INVALID_UID
    private lateinit var intent: Intent
    private var apkLite: ApkLite? = null

    fun preCheck(intent: Intent): Boolean {
        if (!Shizuku.pingBinder()
            || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            || Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            installResult.value = InstallAborted(
                ABORT_SHIZUKU,
                packageManager.getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID)
            )
            return false
        }

        this.intent = intent
        Log.v(TAG, "Intent: $intent")

        Hook.init(context)
        Hook.disableAdbVerify(context)
        PreferredActivity.set(packageManager)

        installResult.value = InstallParse()
        return true
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
        val uri = intent.data
        installResult.postValue(InstallInstalling(apkLite!!))
        if (ContentResolver.SCHEME_CONTENT != uri?.scheme) {
            installPackageUri()
            return
        }

        if (stagedSessionId == SessionInfo.INVALID_ID) {
            try {
                val params: SessionParams = createSessionParams(setInstaller, full)
                stagedSessionId = packageInstaller.createSession(params)
            } catch (e: Exception) {
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

    private fun processContentUri(uri: Uri): InstallStage {
        Log.v(TAG, "content URI: $uri")
        packageManager.resolveContentProvider(uri.authority!!, 0)?.also { info ->
            callingUid = info.applicationInfo.uid
        }
        val apk = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                PackageUtil.parseZipFromFd(afd)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to parse APK from content URI: $uri", e)
            null
        }
        if (apk == null) {
            return InstallAborted(ABORT_PARSE)
        }
        val old = try {
            packageManager.getPackageInfo(apk.packageName, PackageManager_rename.MATCH_KNOWN_PACKAGES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        var full = true
        if (apk.isSplit()) {
            for (item in packageInstaller.allSessions) {
                if (item.isActive && item.appPackageName == apk.packageName) {
                    val info = packageInstaller.getSessionInfo(item.sessionId)!!
                    stagedSessionId = info.sessionId
                    full = info.mode == SessionParams.MODE_FULL_INSTALL
                    apk.label = info.appLabel as String?
                    apk.icon = info.appIcon?.toDrawable(context.resources)
                    break
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
            if (apk.label == null) {
                apk.label = old.applicationInfo!!.loadLabel(packageManager).toString()
            }
            if (apk.icon == null) {
                apk.icon = old.applicationInfo!!.loadIcon(packageManager)
            }
            // use this field to store the installer package name
            old.sharedUserId = packageManager.getInstallerPackageName(apk.packageName)
        }
        if (apk.label == null) {
            context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        apk.label = cursor.getString(displayNameIndex)
                    }
                }
            }
        }
        apkLite = apk
        val skipCreate = stagedSessionId != SessionInfo.INVALID_ID
        return InstallUserAction(apk, old, full, skipCreate)
    }

    private fun processPackageUri(packageName: String): InstallStage {
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager_rename.MATCH_KNOWN_PACKAGES)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Requested package not available.", e)
            return InstallAborted(ABORT_NOTFOUND)
        }

        val apk = PackageUtil.getApkLite(packageManager, info!!)
        apkLite = apk
        return if (info.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED != 0) {
            PackageUserAction(apk, info)
        } else {
            InstallUserAction(apk, info)
        }
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

    private fun createSessionParams(setInstaller: Boolean, full: Boolean): SessionParams {
        val mode = if (full) {
            SessionParams.MODE_FULL_INSTALL
        } else {
            SessionParams.MODE_INHERIT_EXISTING
        }
        val params = SessionParams(mode)
        var installer = context.packageName
        if (setInstaller) {
            try {
                packageManager.getPackageInfo("com.android.vending", PackageManager.MATCH_SYSTEM_ONLY)
                installer = "com.android.vending"
            } catch (_: PackageManager.NameNotFoundException) {
                installer = "com.android.shell"
            }
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
            val intent = packageManager.getLaunchIntentForPackage(apkLite!!.packageName)
            installResult.postValue(InstallSuccess(apkLite!!, intent))
        } else {
            installResult.postValue(InstallFailed(apkLite!!, legacyStatus, statusCode, message))
        }
    }

    fun cleanupInstall() {
        if (stagedSessionId > 0) {
            try {
                packageInstaller.abandonSession(stagedSessionId)
            } catch (_: SecurityException) {
            }
            stagedSessionId = 0
        }
    }

    fun archivePackage(info: PackageInfo) {
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
        installResult.postValue(InstallSuccess(apkLite!!, intent, path))
    }

}
