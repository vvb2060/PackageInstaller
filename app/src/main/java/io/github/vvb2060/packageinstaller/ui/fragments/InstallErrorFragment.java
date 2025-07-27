package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallAborted;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;
import rikka.shizuku.Shizuku;

public class InstallErrorFragment extends DialogFragment {

    private final InstallAborted mAborted;
    private InstallViewModel mViewModel;

    public InstallErrorFragment(InstallAborted aborted) {
        mAborted = aborted;
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
        var code = mAborted.getAbortReason();
        var context = requireContext();
        return new AlertDialog.Builder(requireContext())
            .setTitle(code == InstallAborted.ABORT_INFO ? R.string.app_name : R.string.error_title)
            .setIcon(R.drawable.ic_app_icon)
            .setMessage(getErrorMessage(context, code))
            .setPositiveButton(android.R.string.ok,
                (dialog, which) -> {
                    cleanAndFinish();
                    if (code == InstallAborted.ABORT_SHIZUKU) {
                        checkShizuku(context);
                    }
                })
            .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        cleanAndFinish();
    }

    private void checkShizuku(Context context) {
        var intent = mAborted.getIntent();
        if (intent == null) {
            Uri web = Uri.parse(context.getString(R.string.shizuku_url));
            intent = new Intent(Intent.ACTION_VIEW, web);
            requireActivity().startActivity(intent);
        } else if (!Shizuku.pingBinder()) {
            requireActivity().startActivity(intent);
        } else {
            Shizuku.requestPermission(1);
        }
    }

    private String getErrorMessage(Context context, int code) {
        switch (code) {
            case InstallAborted.ABORT_SHIZUKU -> {
                if (mAborted.getIntent() == null) {
                    return context.getString(R.string.error_shizuku_notfound);
                } else if (!Shizuku.pingBinder()) {
                    return context.getString(R.string.error_shizuku_notrunning);
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    return context.getString(R.string.error_shizuku_notpermitted);
                } else {
                    return context.getString(R.string.error_shizuku_unavailable);
                }
            }
            case InstallAborted.ABORT_INFO -> {
                return getString(R.string.error_info,
                    context.getString(R.string.license)) + "\n\n" +
                    context.getString(R.string.copyright);
            }
        }
        var id = switch (code) {
            case InstallAborted.ABORT_PARSE -> R.string.error_parse;
            case InstallAborted.ABORT_SPLIT -> R.string.error_split;
            case InstallAborted.ABORT_NOTFOUND -> R.string.error_notfound;
            case InstallAborted.ABORT_CREATE -> R.string.error_create;
            case InstallAborted.ABORT_WRITE -> R.string.error_write;
            default -> R.string.error_title;
        };
        return context.getString(id);
    }

    private void cleanAndFinish() {
        mViewModel.cleanupInstall();
        requireActivity().finish();
    }
}
