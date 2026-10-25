package io.github.vvb2060.packageinstaller.viewmodel;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.vvb2060.packageinstaller.core.InstallRepository;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;

public class InstallViewModel extends AndroidViewModel {

    private final Executor executor = Executors.newFixedThreadPool(1);
    private final InstallRepository repository;
    private boolean cancelled = false;

    public InstallViewModel(@NonNull Application application) {
        super(application);
        repository = new InstallRepository(getApplication());
    }

    public LiveData<InstallStage> getCurrentInstallStage() {
        return repository.installResult;
    }

    public LiveData<Integer> getStagingProgress() {
        return repository.stagingProgress;
    }

    public void preprocessIntent(@NonNull Intent intent) {
        executor.execute(() -> {
            if (repository.preCheck(intent)) {
                repository.parseUri();
            }
        });
    }

    public void cleanup() {
        if (cancelled) return;
        repository.cleanup();
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void initiateInstall(
        boolean setInstaller,
        boolean commit,
        boolean full,
        boolean removeSplit
    ) {
        executor.execute(() ->
            repository.install(setInstaller, commit, full, removeSplit)
        );
    }
}
