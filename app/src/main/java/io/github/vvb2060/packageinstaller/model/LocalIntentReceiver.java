package io.github.vvb2060.packageinstaller.model;

import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender_rename;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller_rename;
import android.content.pm.PackageManager_rename;
import android.os.Bundle;
import android.os.IBinder;

public class LocalIntentReceiver {
    private final ResultObserver mResultObserver;

    private final IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
        @Override
        public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                         IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            int legacyStatus = intent.getIntExtra(PackageInstaller_rename.EXTRA_LEGACY_STATUS,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR);
            mResultObserver.onResult(status, legacyStatus, statusMessage);
        }
    };

    public LocalIntentReceiver(ResultObserver observer) {
        mResultObserver = observer;
    }

    public IntentSender_rename getIntentSender() {
        return new IntentSender_rename((IIntentSender) mLocalSender);
    }

    public interface ResultObserver {
        void onResult(int status, int legacyStatus, String message);
    }
}
