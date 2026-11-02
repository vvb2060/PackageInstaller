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

import java.util.List;
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

    private InstallRepository repository;
    private boolean cancelled;

    public InstallViewModel(@NonNull Application application) {
        super(application);
        executor = Executors.newFixedThreadPool(1);
        installResult = new MutableLiveData<>(new InstallParse());
        stagingProgress = new MutableLiveData<>(-1);
        sp = application.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);
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
            var pm = context.getPackageManager();
            ShizukuProvider.waitForBinder(context);
            if (Shizuku.getBinder() == null
                || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                || Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
                var packageName = rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID;
                installResult.postValue(new InstallAborted(InstallAborted.ABORT_SHIZUKU,
                    pm.getLaunchIntentForPackage(packageName)));
                return;
            }
            BinderWrapper.init(context);
            BinderWrapper.disableAdbVerify(context);
            PreferredActivity.set(pm);

            var safeMode = sp.getBoolean("safe_mode", needSafeMode());
            repository = new InstallRepository(context, safeMode, installResult, stagingProgress);
            addCloseable(repository);
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

    private static boolean needSafeMode() {
        var packageName = PreferredActivity.getSystemInstaller();
        if (packageName == null) return false;
        try {
            var info = BinderWrapper.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            var perms = info.requestedPermissions;
            if (perms == null) return false;
            var list = List.of(perms);
            if (list.contains(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION)) {
                return false;
            }
            return list.contains(Manifest.permission.INTERNET);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
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
