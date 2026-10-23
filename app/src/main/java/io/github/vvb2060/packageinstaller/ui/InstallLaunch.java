package io.github.vvb2060.packageinstaller.ui;

import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentFactory;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.installmodel.InstallFailed;
import io.github.vvb2060.packageinstaller.installmodel.InstallInstalling;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.installmodel.InstallSuccess;
import io.github.vvb2060.packageinstaller.installmodel.InstallUserAction;
import io.github.vvb2060.packageinstaller.installmodel.PackageUserAction;
import io.github.vvb2060.packageinstaller.ui.fragments.BaseDialogFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallConfirmationFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallErrorFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallFailedFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallInstallingFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallParseFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallSuccessFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.UninstallConfirmationFragment;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public class InstallLaunch extends FragmentActivity {
    private InstallViewModel installViewModel;
    private FragmentManager fragmentManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        var window = getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true);
        }
        fragmentManager = getSupportFragmentManager();
        installViewModel = new ViewModelProvider(this).get(InstallViewModel.class);
        fragmentManager.setFragmentFactory(new FragmentFactory() {
            @Override
            public Fragment instantiate(ClassLoader classLoader, String className) {
                try {
                    var fragmentClass = loadFragmentClass(classLoader, className);
                    var constructor = fragmentClass.getConstructors()[0];
                    if (constructor.getParameterCount() == 1) {
                        var stage = installViewModel.getCurrentInstallStage().getValue();
                        return (Fragment) constructor.newInstance(stage);
                    }
                    return (Fragment) constructor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new Fragment.InstantiationException(className, e);
                }
            }
        });

        super.onCreate(savedInstanceState);

        if (BuildConfig.DEBUG) {
            StrictMode.enableDefaults();
        }

        if (savedInstanceState == null) {
            installViewModel.preprocessIntent(getIntent());
        }
        installViewModel.getCurrentInstallStage().observe(this, this::onInstallStageChange);
    }

    private void onInstallStageChange(InstallStage installStage) {
        switch (installStage) {
            case InstallAborted aborted -> {
                if (aborted.abortReason == InstallAborted.ABORT_CLOSE) {
                    show(null);
                    finishAndRemoveTask();
                } else {
                    show(new InstallErrorFragment(aborted));
                }
            }
            case InstallParse parse -> show(new InstallParseFragment(parse));
            case InstallUserAction action -> show(new InstallConfirmationFragment(action));
            case PackageUserAction action -> show(new UninstallConfirmationFragment(action));
            case InstallInstalling installing -> show(new InstallInstallingFragment(installing));
            case InstallSuccess success -> show(new InstallSuccessFragment(success));
            case InstallFailed failed -> show(new InstallFailedFragment(failed));
        }
    }

    private void show(@Nullable DialogFragment newDialog) {
        var currentDialog = (DialogFragment) fragmentManager.findFragmentByTag("dialog");
        if (currentDialog instanceof BaseDialogFragment current &&
            newDialog instanceof BaseDialogFragment next &&
            current.mDialogData.stageCode == next.mDialogData.stageCode) {
            return;
        }
        if (currentDialog != null) {
            currentDialog.dismissAllowingStateLoss();
        }
        if (newDialog != null) {
            newDialog.show(fragmentManager, "dialog");
        }
    }
}
