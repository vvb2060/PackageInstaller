package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.PackageUserAction;

public class ArchiveConfirmationFragment extends BaseDialogFragment {

    private final PackageUserAction mDialogData;

    public ArchiveConfirmationFragment(PackageUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setIcon(mDialogData.getApkLite().getIcon())
            .setMessage(R.string.archive_confirm_question)
            .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                requireActivity().finish())
            .setPositiveButton(R.string.archive, (dialog, which) ->
                mViewModel.archivePackage(mDialogData.getOldApk()))
            .create();
    }

}
