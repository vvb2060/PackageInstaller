package io.github.vvb2060.packageinstaller.model

import android.content.Intent
import android.content.pm.PackageInfo

sealed class InstallStage(val stageCode: Int) {
    companion object {
        const val STAGE_ABORTED = 0
        const val STAGE_PARSE = 1
        const val STAGE_USER_ACTION = 2
        const val STAGE_INSTALLING = 3
        const val STAGE_SUCCESS = 4
        const val STAGE_FAILED = 5
    }
}

class InstallParse : InstallStage(STAGE_PARSE)

data class InstallUserAction(
    val apkLite: ApkLite,
    val oldApk: PackageInfo?,
    val fullInstall: Boolean,
    val skipCreate: Boolean,
) : InstallStage(STAGE_USER_ACTION)

data class InstallInstalling(
    val apkLite: ApkLite,
) : InstallStage(STAGE_INSTALLING)

data class InstallSuccess(
    val apkLite: ApkLite,
    val startIntent: Intent?,
) : InstallStage(STAGE_SUCCESS)

data class InstallFailed(
    val apkLite: ApkLite,
    val legacyCode: Int,
    val statusCode: Int,
    val message: String?,
) : InstallStage(STAGE_FAILED)

data class InstallAborted(
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
