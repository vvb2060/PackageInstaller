package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public abstract sealed class BaseDialogFragment extends DialogFragment permits
    InstallConfirmationFragment, InstallErrorFragment, InstallFailedFragment,
    InstallInstallingFragment, InstallParseFragment, InstallSuccessFragment,
    UninstallConfirmationFragment {

    public final InstallStage mDialogData;
    InstallViewModel mViewModel;

    public BaseDialogFragment(InstallStage dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity())
            .get(InstallViewModel.class);
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        cleanAndFinish();
    }

    void cleanAndFinish() {
        mViewModel.cleanup();
        requireActivity().finishAndRemoveTask();
    }
}
