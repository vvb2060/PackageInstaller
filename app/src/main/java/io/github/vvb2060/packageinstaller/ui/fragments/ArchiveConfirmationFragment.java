package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.PackageUserAction;

public class ArchiveConfirmationFragment extends BaseDialogFragment {

    private final PackageUserAction mDialogData;

    public ArchiveConfirmationFragment(PackageUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        TextView textView = dialogView.requireViewById(R.id.message);
        textView.setText(R.string.archive_confirm_question);
        CheckBox checkBox = dialogView.requireViewById(R.id.checkbox);
        checkBox.setVisibility(View.VISIBLE);
        checkBox.setText(R.string.uninstall_keep_data);
        var info = mDialogData.getOldApk().applicationInfo;
        assert info != null;
        int text = info.enabled ? R.string.disable : R.string.enable;
        return new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setIcon(mDialogData.getApkLite().getIcon())
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                requireActivity().finish())
            .setPositiveButton(R.string.archive, (dialog, which) ->
                mViewModel.archivePackage(mDialogData.getOldApk(), checkBox.isChecked()))
            .setNeutralButton(text, (dialog, which) -> {
                requireActivity().finish();
                mViewModel.setPackageEnabled(info.packageName, !info.enabled);
            })
            .create();
    }

}
