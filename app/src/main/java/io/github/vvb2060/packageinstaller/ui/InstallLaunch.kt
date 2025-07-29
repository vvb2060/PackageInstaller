package io.github.vvb2060.packageinstaller.ui

import android.os.Bundle
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import io.github.vvb2060.packageinstaller.model.InstallAborted
import io.github.vvb2060.packageinstaller.model.InstallFailed
import io.github.vvb2060.packageinstaller.model.InstallInstalling
import io.github.vvb2060.packageinstaller.model.InstallStage
import io.github.vvb2060.packageinstaller.model.InstallSuccess
import io.github.vvb2060.packageinstaller.model.InstallUserAction
import io.github.vvb2060.packageinstaller.ui.fragments.InstallConfirmationFragment
import io.github.vvb2060.packageinstaller.ui.fragments.InstallErrorFragment
import io.github.vvb2060.packageinstaller.ui.fragments.InstallFailedFragment
import io.github.vvb2060.packageinstaller.ui.fragments.InstallInstallingFragment
import io.github.vvb2060.packageinstaller.ui.fragments.InstallParseFragment
import io.github.vvb2060.packageinstaller.ui.fragments.InstallSuccessFragment
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel

class InstallLaunch : FragmentActivity() {

    private lateinit var installViewModel: InstallViewModel
    private lateinit var fragmentManager: FragmentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        fragmentManager = supportFragmentManager
        installViewModel = ViewModelProvider(this)[InstallViewModel::class.java]

        installViewModel.preprocessIntent(this.intent)
        installViewModel.currentInstallStage.observe(this) { installStage: InstallStage ->
            onInstallStageChange(installStage)
        }
    }

    private fun onInstallStageChange(installStage: InstallStage) {
        when (installStage.stageCode) {
            InstallStage.Companion.STAGE_ABORTED -> {
                val aborted = installStage as InstallAborted
                if (aborted.abortReason == InstallAborted.ABORT_CLOSE) {
                    showDialogInner(null)
                    finish()
                } else {
                    val errorDialog = InstallErrorFragment(aborted)
                    showDialogInner(errorDialog)
                }
            }

            InstallStage.Companion.STAGE_PARSE -> {
                val parseDialog = InstallParseFragment()
                showDialogInner(parseDialog)
            }

            InstallStage.Companion.STAGE_USER_ACTION -> {
                val uar = installStage as InstallUserAction
                val actionDialog = InstallConfirmationFragment(uar)
                showDialogInner(actionDialog)
            }

            InstallStage.Companion.STAGE_INSTALLING -> {
                val installing = installStage as InstallInstalling
                val installingDialog = InstallInstallingFragment(installing)
                showDialogInner(installingDialog)
            }

            InstallStage.Companion.STAGE_SUCCESS -> {
                val success = installStage as InstallSuccess
                val successDialog = InstallSuccessFragment(success)
                showDialogInner(successDialog)
            }

            InstallStage.Companion.STAGE_FAILED -> {
                val failed = installStage as InstallFailed
                val failureDialog = InstallFailedFragment(failed)
                showDialogInner(failureDialog)
            }
        }
    }

    private fun showDialogInner(newDialog: DialogFragment?) {
        val currentDialog = fragmentManager.findFragmentByTag("dialog") as DialogFragment?
        currentDialog?.dismissAllowingStateLoss()
        newDialog?.show(fragmentManager, "dialog")
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations()) {
            showDialogInner(null)
        }
    }

}
