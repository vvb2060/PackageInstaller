package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import io.github.vvb2060.packageinstaller.R;

public class InstallParseFragment extends DialogFragment {

    private AlertDialog mDialog;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        dialogView.requireViewById(R.id.installing).setVisibility(View.VISIBLE);
        TextView textView = dialogView.requireViewById(R.id.installing_message);
        textView.setText(R.string.parsing);
        setCancelable(false);
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_name)
            .setIcon(R.drawable.ic_app_icon)
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
