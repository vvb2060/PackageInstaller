package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.installmodel.PackageUserAction;

public final class UninstallConfirmationFragment extends BaseDialogFragment {

    private final PackageUserAction mDialogData;
    private AlertDialog mDialog;

    public UninstallConfirmationFragment(PackageUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        TextView textView = dialogView.requireViewById(R.id.message);
        textView.setText(R.string.uninstall_confirm_question);
        CheckBox checkBox = dialogView.requireViewById(R.id.checkbox);
        checkBox.setVisibility(View.VISIBLE);
        checkBox.setText(R.string.only_clear_data);
        checkBox.setOnCheckedChangeListener((_, isChecked) -> {
            var button = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            button.setText(isChecked ? R.string.clear : R.string.uninstall);
        });
        var app = mDialogData.info.applicationInfo;
        assert app != null;
        var enabled = app.enabled;
        int text = enabled ? R.string.disable : R.string.enable;
        var apk = mDialogData.apkLite;
        mDialog = new AlertDialog.Builder(requireContext())
            .setTitle(apk.getLabel())
            .setIcon(apk.getIcon())
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                requireActivity().finishAndRemoveTask())
            .setPositiveButton(R.string.uninstall, (dialog, which) -> {
                requireActivity().finishAndRemoveTask();
                if (checkBox.isChecked()) {
                    BinderWrapper.clearApplicationUserData(apk.packageName);
                } else {
                    BinderWrapper.uninstallPackage(mDialogData.info);
                }
            })
            .setNeutralButton(text, (dialog, which) -> {
                requireActivity().finishAndRemoveTask();
                BinderWrapper.setPackageEnabled(apk.packageName, !enabled);
            })
            .create();
        return mDialog;
    }

}
