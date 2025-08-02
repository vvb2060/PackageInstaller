package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.PackageUserAction;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public class ArchiveConfirmationFragment extends DialogFragment {

    private final PackageUserAction mDialogData;
    private InstallViewModel mViewModel;

    public ArchiveConfirmationFragment(PackageUserAction dialogData) {
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
            .setMessage(R.string.archive_confirm_question)
            .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                requireActivity().finish())
            .setPositiveButton(R.string.archive, (dialog, which) ->
                mViewModel.archivePackage(mDialogData.getOldApk()))
            .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        requireActivity().finish();
    }

}
