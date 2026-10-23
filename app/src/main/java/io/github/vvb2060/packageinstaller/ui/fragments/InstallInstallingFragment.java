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

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallInstalling;

public final class InstallInstallingFragment extends BaseDialogFragment {

    private final InstallInstalling mDialogData;
    private AlertDialog mDialog;

    public InstallInstallingFragment(InstallInstalling dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        TextView textView = dialogView.requireViewById(R.id.message);
        ProgressBar progressBar = dialogView.requireViewById(R.id.progress);
        progressBar.setVisibility(View.VISIBLE);
        mViewModel.getStagingProgress().observe(this, progress -> {
            var button = mDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            button.setEnabled(progress < 100);
            textView.setText(progress <= 100 ? R.string.copying : R.string.installing);
            progressBar.setIndeterminate(progress < 0 || progress > 100);
            progressBar.setProgress(progress, true);
        });
        setCancelable(false);
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.apkLite.getLabel())
            .setIcon(mDialogData.apkLite.getIcon())
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, (_, _) -> mViewModel.cleanup())
            .create();
        return mDialog;
    }
}
