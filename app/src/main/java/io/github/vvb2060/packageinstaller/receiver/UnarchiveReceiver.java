package io.github.vvb2060.packageinstaller.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import io.github.vvb2060.packageinstaller.BuildConfig;

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class UnarchiveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (!Intent.ACTION_UNARCHIVE_PACKAGE.equals(intent.getAction())) return;

        int unarchiveId = intent.getIntExtra(PackageInstaller.EXTRA_UNARCHIVE_ID,
            PackageInstaller.SessionInfo.INVALID_ID);
        if (unarchiveId == PackageInstaller.SessionInfo.INVALID_ID) return;

        var packageName = intent.getStringExtra(PackageInstaller.EXTRA_UNARCHIVE_PACKAGE_NAME);
        if (packageName == null) return;

        var intentLaunch = new Intent(Intent.ACTION_INSTALL_PACKAGE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setPackage(BuildConfig.APPLICATION_ID)
            .setData(new Uri.Builder()
                .scheme("package")
                .opaquePart(packageName)
                .build());
        var pendingIntent = PendingIntent.getActivity(context, 0, intentLaunch,
            PendingIntent.FLAG_IMMUTABLE);
        var state = PackageInstaller.UnarchivalState.createUserActionRequiredState(
            unarchiveId, pendingIntent);
        try {
            // noinspection MissingPermission
            context.getPackageManager().getPackageInstaller().reportUnarchivalState(state);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(UnarchiveReceiver.class.getSimpleName(), "Invalid unarchive ID", e);
        }
    }
}
