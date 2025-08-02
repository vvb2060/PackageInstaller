package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.model.InstallStage;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public class BaseDialogFragment extends DialogFragment {

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
        mViewModel.cleanupInstall();
        requireActivity().finish();
    }
}
