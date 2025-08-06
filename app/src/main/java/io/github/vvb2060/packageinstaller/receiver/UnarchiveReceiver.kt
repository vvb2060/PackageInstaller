package io.github.vvb2060.packageinstaller.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class UnarchiveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_UNARCHIVE_PACKAGE) {
            return
        }
        val unarchiveId = intent.getIntExtra(
            PackageInstaller.EXTRA_UNARCHIVE_ID,
            PackageInstaller.SessionInfo.INVALID_ID
        )
        if (unarchiveId == PackageInstaller.SessionInfo.INVALID_ID) {
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_UNARCHIVE_PACKAGE_NAME)
            ?: return

        val intentLaunch = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(context.packageName)
            data = Uri.Builder().scheme("package").opaquePart(packageName).build()
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intentLaunch,
            PendingIntent.FLAG_IMMUTABLE
        )

        val state = PackageInstaller.UnarchivalState.createUserActionRequiredState(
            unarchiveId,
            pendingIntent
        )
        // noinspection MissingPermission
        context.packageManager.packageInstaller.reportUnarchivalState(state)
    }

}
