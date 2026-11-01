package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;

public final class SettingsFragment extends BaseDialogFragment {

    private final SettingsUserAction mDialogData;

    public SettingsFragment(SettingsUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Log.d("SettingsFragment", "onCreateDialog: " + savedInstanceState);
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        TextView textView = dialogView.requireViewById(R.id.message);
        textView.setText(R.string.settings_safe_mode);
        CheckBox checkBox = dialogView.requireViewById(R.id.checkbox);
        checkBox.setVisibility(View.VISIBLE);
        checkBox.setText(R.string.safe_mode);
        if (savedInstanceState == null) {
            checkBox.setChecked(mDialogData.safeMode);
        }
        return new AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_name)
            .setIcon(R.drawable.ic_app_icon)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, (_, _) -> {
                var checked = checkBox.isChecked();
                if (checked != mDialogData.safeMode) {
                    mViewModel.setSafeMode(checked);
                }
                finish();
            })
            .setNegativeButton(android.R.string.cancel, (_, _) -> finish())
            .setNeutralButton(R.string.launch, (_, _) -> {
                var web = Uri.parse("https://github.com/vvb2060/PackageInstaller");
                var intent = new Intent(Intent.ACTION_VIEW, web);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySafe(intent, false);
                finish();
            })
            .create();
    }
}
