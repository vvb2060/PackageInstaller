package io.github.vvb2060.packageinstaller.ui;

import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;
import io.github.vvb2060.packageinstaller.ui.fragments.BaseDialogFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallConfirmationFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallErrorFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallFailedFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallInstallingFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallParseFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.InstallSuccessFragment;
import io.github.vvb2060.packageinstaller.ui.fragments.SettingsFragment;
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
            @NonNull
            @Override
            public Fragment instantiate(@NonNull ClassLoader classLoader, @NonNull String className) {
                var stage = installViewModel.getCurrentInstallStage().getValue();
                assert stage != null;
                return stageToFragment(stage);
            }
        });

        super.onCreate(savedInstanceState);

        if (BuildConfig.DEBUG) {
            StrictMode.enableDefaults();
        }

        var currentInstallStage = installViewModel.getCurrentInstallStage();
        var stage = currentInstallStage.getValue();
        assert stage != null;
        if (stage.stageCode == InstallStage.STAGE_PARSE) {
            installViewModel.preprocessIntent(getIntent());
        }

        currentInstallStage.observe(this, installStage ->
            show(stageToFragment(installStage)));
    }

    private static BaseDialogFragment stageToFragment(InstallStage installStage) {
        return switch (installStage) {
            case InstallAborted aborted -> new InstallErrorFragment(aborted);
            case InstallParse parse -> new InstallParseFragment(parse);
            case InstallUserAction action -> new InstallConfirmationFragment(action);
            case PackageUserAction action -> new UninstallConfirmationFragment(action);
            case SettingsUserAction action -> new SettingsFragment(action);
            case InstallInstalling installing -> new InstallInstallingFragment(installing);
            case InstallSuccess success -> new InstallSuccessFragment(success);
            case InstallFailed failed -> new InstallFailedFragment(failed);
        };
    }

    private void show(BaseDialogFragment newDialog) {
        var currentDialog = (BaseDialogFragment) fragmentManager.findFragmentByTag("dialog");
        if (currentDialog != null) {
            if (currentDialog.stageCode == newDialog.stageCode) {
                return;
            }
            currentDialog.dismissAllowingStateLoss();
        }
        newDialog.show(fragmentManager, "dialog");
    }
}
