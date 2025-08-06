package io.github.vvb2060.packageinstaller.model

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageManager_rename
import android.net.Uri
import android.os.Build
import android.system.Os
import android.util.Log
import android.util.LogPrinter
import io.github.vvb2060.packageinstaller.BuildConfig
import io.github.vvb2060.packageinstaller.ui.InstallLaunch

object PreferredActivity {
    private const val TAG = "PreferredActivity"
    private const val PACKAGE_MIME_TYPE = "application/vnd.android.package-archive"

    private fun list(pm: PackageManager, packageName: String) {
        val prefActList = ArrayList<ComponentName>()
        val intentList = ArrayList<IntentFilter>()
        pm.getPreferredActivities(intentList, prefActList, packageName)
        Log.d(TAG, packageName + " have " + prefActList.size + " number of activities in preferred list")
        prefActList.forEachIndexed { index, name ->
            Log.d(TAG, "-Preferred activity: ${name.className}")
            intentList[index].dump(LogPrinter(Log.DEBUG, TAG), "--")
        }
    }

    private fun getSystemInstaller(pm: PackageManager): String {
        val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).build()
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, PACKAGE_MIME_TYPE)
        }
        val matches = pm.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
        return matches[0].activityInfo.packageName
    }

    private fun check(pm: PackageManager, action: String): Boolean {
        val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).build()
        val intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, PACKAGE_MIME_TYPE)
        }
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        val packageName = info.activityInfo.packageName
        val isSelf = packageName == BuildConfig.APPLICATION_ID
        val isSystem = packageName == "android"
        val isShizukuUser = Os.getuid() / 100000 == 0

        if (isShizukuUser && !isSelf && !isSystem) {
            Log.v(TAG, "Clear preferred activity for ${info.activityInfo.name}")
            pm.clearPackagePreferredActivities(packageName)
        }

        return isSelf
    }

    private fun add(pm: PackageManager, action: String) {
        val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).build()
        val intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, PACKAGE_MIME_TYPE)
        }
        val filter = IntentFilter().apply {
            addAction(action)
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType(PACKAGE_MIME_TYPE)
        }
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val set = pm.queryIntentActivities(intent, flags).map { info ->
            ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        }.toTypedArray()
        val activity = ComponentName(BuildConfig.APPLICATION_ID, InstallLaunch::class.java.name)
        val match = IntentFilter.MATCH_CATEGORY_TYPE or IntentFilter.MATCH_ADJUSTMENT_MASK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pm as PackageManager_rename
            pm.addUniquePreferredActivity(filter, match, set, activity)
        } else {
            pm.addPreferredActivity(filter, match, set, activity)
        }
    }

    fun set(rawPm: PackageManager) {
        val pm = Hook.pm
        if (check(pm, Intent.ACTION_VIEW) && check(pm, Intent.ACTION_INSTALL_PACKAGE)) {
            return
        }

        rawPm.clearPackagePreferredActivities(BuildConfig.APPLICATION_ID)
        add(pm, Intent.ACTION_VIEW)
        add(pm, Intent.ACTION_INSTALL_PACKAGE)
        Log.v(TAG, "Preferred activities set for ${BuildConfig.APPLICATION_ID}")

        if (BuildConfig.DEBUG) {
            list(pm, getSystemInstaller(pm))
        }
    }
}
