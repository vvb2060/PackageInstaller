package io.github.vvb2060.packageinstaller.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import io.github.vvb2060.packageinstaller.model.InstallRepository
import io.github.vvb2060.packageinstaller.model.InstallStage

@SuppressLint("RestrictedApi")
class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val executor = ArchTaskExecutor.getInstance()
    private val repository = InstallRepository(getApplication())
    val currentInstallStage: LiveData<InstallStage>
        get() = repository.installResult
    val stagingProgress: LiveData<Int>
        get() = repository.stagingProgress

    fun preprocessIntent(intent: Intent) {
        if (repository.preCheck(intent)) {
            executor.executeOnDiskIO {
                repository.parseUri()
            }
        }
    }

    fun cleanupInstall() {
        repository.cleanupInstall()
    }

    fun initiateInstall(setInstaller: Boolean, commit: Boolean) {
        executor.executeOnDiskIO {
            repository.install(setInstaller, commit)
        }
    }
}
