package io.github.vvb2060.packageinstaller.ui.fragments;

import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public abstract sealed class BaseDialogFragment extends DialogFragment permits
    InstallConfirmationFragment, InstallErrorFragment, InstallFailedFragment,
    InstallInstallingFragment, InstallParseFragment, InstallSuccessFragment,
    UninstallConfirmationFragment, SettingsFragment {

    public final int stageCode;
    InstallViewModel mViewModel;

    public BaseDialogFragment(InstallStage dialogData) {
        stageCode = dialogData.stageCode;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity())
            .get(InstallViewModel.class);
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        finish();
    }

    protected void finish() {
        dismiss();
        var activity = requireActivity();
        var handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(activity::finishAndRemoveTask, 100);
    }

    protected void startActivitySafe(Intent intent, boolean safeMode) {
        if (safeMode) {
            BinderWrapper.startActivity(intent);
        } else try {
            requireActivity().startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException _) {
        }
    }
}
