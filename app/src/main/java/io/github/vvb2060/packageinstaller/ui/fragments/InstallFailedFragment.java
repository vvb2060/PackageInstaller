package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.PackageInstaller;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallFailed;

public class InstallFailedFragment extends BaseDialogFragment {

    private final InstallFailed mDialogData;

    public InstallFailedFragment(InstallFailed dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.apkLite.getLabel())
            .setIcon(mDialogData.apkLite.getIcon())
            .setMessage(getExplanation())
            .setPositiveButton(R.string.done, (dialogInt, which) -> cleanAndFinish())
            .create();
    }

    private String getExplanation() {
        int res = switch (mDialogData.statusCode) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION -> R.string.install_failed_user_action;
            case PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_failed_blocked;
            case PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_failed_aborted;
            case PackageInstaller.STATUS_FAILURE_INVALID -> R.string.install_failed_invalid_apk;
            case PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.install_failed_conflict;
            case PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_failed_storage;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.install_failed_incompatible;
            case PackageInstaller.STATUS_FAILURE_TIMEOUT -> R.string.install_failed_timeout;
            default -> R.string.install_failed;
        };
        return requireContext().getString(res) + "\n\n" + mDialogData.message;
    }
}
