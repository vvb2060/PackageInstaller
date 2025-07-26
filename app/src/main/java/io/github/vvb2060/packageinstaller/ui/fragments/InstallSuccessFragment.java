package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallSuccess;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public class InstallSuccessFragment extends DialogFragment {

    private final InstallSuccess mDialogData;
    private InstallViewModel mViewModel;
    private AlertDialog mDialog;

    public InstallSuccessFragment(InstallSuccess dialogData) {
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
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setIcon(mDialogData.getApkLite().getIcon())
            .setMessage(R.string.install_done)
            .setNegativeButton(R.string.done,
                (dialog, which) -> cleanAndFinish())
            .setPositiveButton(R.string.launch, (dialog, which) -> {
                cleanAndFinish();
                requireActivity().startActivity(mDialogData.getStartIntent());
            })
            .create();
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Button launchButton = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (mDialogData.getStartIntent() == null) {
            launchButton.setVisibility(View.GONE);
        }
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
}
