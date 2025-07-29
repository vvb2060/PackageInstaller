package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallInstalling;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public class InstallInstallingFragment extends DialogFragment {

    private final InstallInstalling mDialogData;
    private InstallViewModel mViewModel;
    private AlertDialog mDialog;

    public InstallInstallingFragment(InstallInstalling dialogData) {
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
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        dialogView.requireViewById(R.id.installing).setVisibility(View.VISIBLE);
        TextView textView = dialogView.requireViewById(R.id.installing_message);
        ProgressBar progressBar = dialogView.requireViewById(R.id.progress);
        mViewModel.getStagingProgress().observe(this, progress -> {
            textView.setText(progress <= 100 ? R.string.copying : R.string.installing);
            progressBar.setIndeterminate(progress < 0 || progress > 100);
            progressBar.setProgress(progress);
        });
        setCancelable(false);
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setIcon(mDialogData.getApkLite().getIcon())
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        mDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
    }
}
