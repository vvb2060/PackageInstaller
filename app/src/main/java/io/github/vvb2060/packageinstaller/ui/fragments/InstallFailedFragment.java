package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.PackageInstaller;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallFailed;

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
            .setTitle(mDialogData.getApkLite().getLabel())
            .setIcon(mDialogData.getApkLite().getIcon())
            .setMessage(getExplanation())
            .setPositiveButton(R.string.done, (dialogInt, which) -> cleanAndFinish())
            .create();
    }

    private String getExplanation() {
        int res = switch (mDialogData.getStatusCode()) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_failed_blocked;
            case PackageInstaller.STATUS_FAILURE_INVALID -> R.string.install_failed_invalid_apk;
            case PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.install_failed_conflict;
            case PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_failed_storage;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                R.string.install_failed_incompatible;
            default -> R.string.install_failed;
        };
        var sb = new StringBuilder(requireContext().getString(res));
        sb.append(" (").append(mDialogData.getLegacyCode()).append(")");
        if (mDialogData.getMessage() != null) {
            sb.append("\n\n").append(mDialogData.getMessage());
        }
        return sb.toString();

    }
}
