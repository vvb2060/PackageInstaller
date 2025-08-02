package io.github.vvb2060.packageinstaller.model

import android.content.Intent
import android.content.pm.PackageInfo

sealed class InstallStage(val stageCode: Int) {
    companion object {
        const val STAGE_ABORTED = 0
        const val STAGE_PARSE = 1
        const val STAGE_USER_ACTION = 2
        const val STAGE_ARCHIVE = 3
        const val STAGE_INSTALLING = 4
        const val STAGE_SUCCESS = 5
        const val STAGE_FAILED = 6
    }
}

class InstallParse : InstallStage(STAGE_PARSE)

class InstallUserAction(
    val apkLite: ApkLite,
    val oldApk: PackageInfo?,
    val fullInstall: Boolean = true,
    val skipCreate: Boolean = true,
) : InstallStage(STAGE_USER_ACTION)

class PackageUserAction(
    val apkLite: ApkLite,
    val oldApk: PackageInfo,
) : InstallStage(STAGE_ARCHIVE)

class InstallInstalling(
    val apkLite: ApkLite,
) : InstallStage(STAGE_INSTALLING)

class InstallSuccess(
    val apkLite: ApkLite,
    val startIntent: Intent?,
    val path: String? = null,
) : InstallStage(STAGE_SUCCESS)

class InstallFailed(
    val apkLite: ApkLite,
    val legacyCode: Int,
    val statusCode: Int,
    val message: String?,
) : InstallStage(STAGE_FAILED)

class InstallAborted(
    val abortReason: Int,
    val intent: Intent? = null,
) : InstallStage(STAGE_ABORTED) {

    companion object {
        const val ABORT_CLOSE = 0
        const val ABORT_SHIZUKU = 1
        const val ABORT_PARSE = 2
        const val ABORT_SPLIT = 3
        const val ABORT_NOTFOUND = 4
        const val ABORT_CREATE = 5
        const val ABORT_WRITE = 6
        const val ABORT_INFO = 7
    }
}
