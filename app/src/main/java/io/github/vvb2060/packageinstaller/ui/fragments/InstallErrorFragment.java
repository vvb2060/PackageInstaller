package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallAborted;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

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
        return new AlertDialog.Builder(requireContext())
            .setTitle(R.string.error_title)
            .setIcon(R.drawable.ic_app_icon)
            .setMessage(getErrorMessage(mAborted.getAbortReason()))
            .setPositiveButton(android.R.string.ok,
                (dialog, which) -> {
                    cleanAndFinish();
                    var intent = mAborted.getIntent();
                    if (intent != null) {
                        requireActivity().startActivity(intent);
                    }
                })
            .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        cleanAndFinish();
    }

    private static int getErrorMessage(int code) {
        return switch (code) {
            case InstallAborted.ABORT_SHIZUKU -> R.string.error_shizuku;
            case InstallAborted.ABORT_PARSE -> R.string.error_parse;
            case InstallAborted.ABORT_NOINSTALL -> R.string.error_noinstalled;
            case InstallAborted.ABORT_CREATE -> R.string.error_create;
            case InstallAborted.ABORT_WRITE -> R.string.error_write;
            default -> R.string.error_title;
        };
    }

    private void cleanAndFinish() {
        mViewModel.cleanupInstall();
        requireActivity().finish();
    }
}
