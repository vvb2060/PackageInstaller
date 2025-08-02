package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.Hook;
import io.github.vvb2060.packageinstaller.model.InstallSuccess;

public class InstallSuccessFragment extends BaseDialogFragment {

    private final InstallSuccess mDialogData;
    private AlertDialog mDialog;

    public InstallSuccessFragment(InstallSuccess dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var context = requireContext();
        var message = mDialogData.getPath() != null ?
            context.getString(R.string.archive_done) + mDialogData.getPath() :
            context.getString(R.string.install_done);
        mDialog = new AlertDialog.Builder(context)
            .setTitle(mDialogData.getApkLite().getLabel())
            .setIcon(mDialogData.getApkLite().getIcon())
            .setMessage(message)
            .setNegativeButton(R.string.done,
                (dialog, which) -> cleanAndFinish())
            .setPositiveButton(R.string.launch, (dialog, which) -> {
                cleanAndFinish();
                Hook.INSTANCE.startActivity(mDialogData.getStartIntent());
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
}
