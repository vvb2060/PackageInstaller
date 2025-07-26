package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.PackageInstaller;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallFailed;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public class InstallFailedFragment extends DialogFragment {

    private final InstallFailed mDialogData;
    private InstallViewModel mViewModel;

    public InstallFailedFragment(InstallFailed dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity())
            .get(InstallViewModel.class);
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

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        cleanAndFinish();
    }

    private void cleanAndFinish() {
        mViewModel.cleanupInstall();
        requireActivity().finish();
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
        var message = requireContext().getString(res);
        message += "[" + mDialogData.getLegacyCode() + "]";
        if (mDialogData.getMessage() != null) {
            message += "\n\n" + mDialogData.getMessage();
        }
        return message;

    }
}
