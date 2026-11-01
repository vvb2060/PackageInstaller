package io.github.vvb2060.packageinstaller.viewmodel;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.core.InstallRepository;
import io.github.vvb2060.packageinstaller.core.PreferredActivity;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.provider.ShizukuProvider;
import rikka.shizuku.Shizuku;

public class InstallViewModel extends AndroidViewModel {

    private final Executor executor;
    private final MutableLiveData<InstallStage> installResult;
    private final MutableLiveData<Integer> stagingProgress;
    private final SharedPreferences sp;
    private final InstallRepository repository;
    private boolean cancelled = false;

    public InstallViewModel(@NonNull Application application) {
        super(application);
        executor = Executors.newFixedThreadPool(1);
        installResult = new MutableLiveData<>(new InstallParse());
        stagingProgress = new MutableLiveData<>(-1);
        sp = application.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);
        var pm = application.getPackageManager();
        var safeMode = sp.getBoolean("safe_mode", PreferredActivity.needSafeMode(pm));
        repository = new InstallRepository(application, safeMode, installResult, stagingProgress);
        addCloseable(repository);
    }

    public LiveData<InstallStage> getCurrentInstallStage() {
        return installResult;
    }

    public LiveData<Integer> getStagingProgress() {
        return stagingProgress;
    }

    public void preprocessIntent(Intent intent) {
        executor.execute(() -> {
            var context = getApplication();
            ShizukuProvider.waitForBinder(context);
            if (Shizuku.getBinder() == null
                || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                || Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
                installResult.postValue(new InstallAborted(InstallAborted.ABORT_SHIZUKU,
                    context.getPackageManager().getLaunchIntentForPackage(
                        rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID)));
                return;
            }
            BinderWrapper.init(context);
            BinderWrapper.disableAdbVerify(context);
            PreferredActivity.set(context.getPackageManager());

            repository.parseUri(intent);
        });
    }

    public void initiateInstall(boolean setInstaller, boolean commit,
                                boolean full, boolean removeSplit) {
        if (cancelled) {
            installResult.setValue(new InstallAborted(InstallAborted.ABORT_WRITE));
            return;
        }
        executor.execute(() ->
            repository.install(setInstaller, commit, full, removeSplit)
        );
    }

    public void setSafeMode(boolean safeMode) {
        sp.edit().putBoolean("safe_mode", safeMode).apply();
    }

    public void cleanup() {
        if (cancelled) return;
        repository.close();
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
