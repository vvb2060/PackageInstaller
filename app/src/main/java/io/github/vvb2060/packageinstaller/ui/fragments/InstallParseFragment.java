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
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;

public final class InstallParseFragment extends BaseDialogFragment {

    private AlertDialog mDialog;

    public InstallParseFragment(InstallParse dialogData) {
        super(dialogData);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        ProgressBar progressBar = dialogView.requireViewById(R.id.progress);
        progressBar.setVisibility(View.VISIBLE);
        TextView textView = dialogView.requireViewById(R.id.message);
        textView.setText(R.string.parsing);
        setCancelable(false);
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_name)
            .setIcon(R.drawable.ic_app_icon)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, (_, _) -> mViewModel.cleanup())
            .create();
        return mDialog;
    }
}
