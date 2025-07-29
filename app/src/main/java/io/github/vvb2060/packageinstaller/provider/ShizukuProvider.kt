package io.github.vvb2060.packageinstaller.provider

import android.content.pm.PackageManager
import io.github.vvb2060.packageinstaller.model.Hook
import io.github.vvb2060.packageinstaller.model.PreferredActivity
import rikka.shizuku.Shizuku

class ShizukuProvider : rikka.shizuku.ShizukuProvider() {

    override fun onCreate(): Boolean {
        Shizuku.addBinderReceivedListener {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Hook.init(context!!)
                PreferredActivity.set(context!!.packageManager)
            }
        }
        return super.onCreate()
    }
}
