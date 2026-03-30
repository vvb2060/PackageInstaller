package io.github.vvb2060.packageinstaller.model

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.app.IActivityManager
import android.content.AttributionSource
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.system.Os
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.LSPass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object Hook {
    private var hooked = false
    private lateinit var am: IActivityManager
    lateinit var pm: PackageManager
    lateinit var installer: PackageInstaller

    init {
        LSPass.setHiddenApiExemptions("")
    }

    fun init(context: Context) {
        if (hooked) return

        val ibinder = SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)
        am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(ibinder))

        pm = context.packageManager
        var contextImpl = context
        while (contextImpl is ContextWrapper) {
            contextImpl = contextImpl.baseContext
        }
        contextImpl::class.java.getDeclaredField("mPackageManager").apply {
            isAccessible = true
            set(contextImpl, null)
        }

        installer = pm.packageInstaller
        pm::class.java.getDeclaredField("mInstaller").apply {
            isAccessible = true
            set(pm, null)
        }

        ActivityThread::class.java.getDeclaredField("sPackageManager").apply {
            isAccessible = true
            val sPackageManager = get(null) as IPackageManager
            val wrapper = ShizukuBinderWrapper(sPackageManager.asBinder())
            set(null, IPackageManager.Stub.asInterface(wrapper))
        }
        pm::class.java.getDeclaredField("mPM").apply {
            isAccessible = true
            val mPM = get(pm) as IPackageManager
            val wrapper = ShizukuBinderWrapper(mPM.asBinder())
            set(pm, IPackageManager.Stub.asInterface(wrapper))
        }
        installer::class.java.getDeclaredField("mInstaller").apply {
            isAccessible = true
            val mInstaller = get(installer) as IPackageInstaller
            val wrapper = ShizukuBinderWrapper(mInstaller.asBinder())
            set(installer, IPackageInstaller.Stub.asInterface(wrapper))
        }
        hooked = true
    }

    @SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    private fun wrapGlobalSettings(callback: Runnable) {
        runCatching {
            val holder = Settings.Global::class.java.getDeclaredField("sProviderHolder").run {
                isAccessible = true
                get(null)
            }
            val provider = holder::class.java.getDeclaredField("mContentProvider").run {
                isAccessible = true
                get(holder)
            }
            provider::class.java.getDeclaredField("mRemote").run {
                isAccessible = true
                val binder = get(provider) as IBinder
                val wrapper = ShizukuBinderWrapper(binder)
                set(provider, wrapper)
                try {
                    callback.run()
                } finally {
                    set(provider, binder)
                }
            }
        }.onFailure {
            // Fallback without reflection to avoid hard crash on future Android releases.
            callback.run()
        }
    }

    fun PackageInstaller.Session.wrap() {
        return this::class.java.getDeclaredField("mSession").run {
            isAccessible = true
            val mSession = get(this@wrap) as IPackageInstallerSession
            val wrapper = ShizukuBinderWrapper(mSession.asBinder())
            set(this@wrap, IPackageInstallerSession.Stub.asInterface(wrapper))
        }
    }

    fun disableAdbVerify(context: Context) {
        val name = "verifier_verify_adb_installs"
        val verifierIncludeAdb = Settings.Global.getInt(context.contentResolver, name, 1) != 0
        if (verifierIncludeAdb) {
            wrapGlobalSettings {
                val contextWrapper = ShizukuContext(context)
                val cr = object : ContentResolver(contextWrapper) {}
                Settings.Global.putInt(cr, name, 0)
            }
        }
    }

    fun startActivity(context: Context, intent: Intent) {
        if (intent.action == "io.github.vvb2060.packageinstaller.ROOT_LAUNCH") {
            intent.`package`?.let {
                RootInstallHelper.launchPackage(it)
            }
            return
        }
        if (!::am.isInitialized) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        val userId = Os.getuid() / 100000
        am.startActivityAsUser(
            null, "com.android.shell", intent, intent.type,
            null, null, 0, 0, null, null, userId
        )
    }
}

private class ShizukuContext(context: Context) : ContextWrapper(context) {
    @RequiresApi(Build.VERSION_CODES.S)
    override fun getAttributionSource(): AttributionSource {
        val builder = AttributionSource.Builder(Shizuku.getUid())
            .setPackageName("com.android.shell")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setPid(Process.INVALID_PID)
        }
        return builder.build()
    }

    override fun getOpPackageName(): String {
        return "com.android.shell"
    }
}
