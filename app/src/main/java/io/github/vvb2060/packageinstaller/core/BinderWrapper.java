package io.github.vvb2060.packageinstaller.core;

import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager_rename;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.lsposed.hiddenapibypass.LSPass;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class BinderWrapper {
    private static final String TAG = BinderWrapper.class.getSimpleName();
    private static boolean initialized;

    public static IActivityManager am;
    public static IDeviceIdleController deviceIdle;
    public static PackageManager pm;
    public static PackageInstaller installer;

    static {
        LSPass.setHiddenApiExemptions("");
    }

    public static void init(Context context) {
        if (initialized) return;

        var activity = SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE);
        am = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(activity));
        var deviceidle = SystemServiceHelper.getSystemService("deviceidle");
        deviceIdle = IDeviceIdleController.Stub.asInterface(new ShizukuBinderWrapper(deviceidle));

        try {
            deletePmRef(context);
            pm = context.getPackageManager();
            deletePmRef(context);

            deleteInstallerRef();
            installer = pm.getPackageInstaller();
            deleteInstallerRef();

            var field = pm.getClass().getDeclaredField("mPM");
            field.setAccessible(true);
            var mPM = (IPackageManager) field.get(pm);
            var wrapper = new ShizukuBinderWrapper(mPM.asBinder());
            field.set(pm, IPackageManager.Stub.asInterface(wrapper));

            // noinspection SoonBlockedPrivateApi
            field = installer.getClass().getDeclaredField("mInstaller");
            field.setAccessible(true);
            var mInstaller = (IPackageInstaller) field.get(installer);
            wrapper = new ShizukuBinderWrapper(mInstaller.asBinder());
            field.set(installer, IPackageInstaller.Stub.asInterface(wrapper));

            initialized = true;
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "init", e);
        }
    }

    private static void deleteInstallerRef() throws ReflectiveOperationException {
        var field = pm.getClass().getDeclaredField("mInstaller");
        field.setAccessible(true);
        field.set(pm, null);
    }

    private static void deletePmRef(Context context) throws ReflectiveOperationException {
        var contextImpl = context;
        while (contextImpl instanceof ContextWrapper contextWrapper) {
            contextImpl = contextWrapper.getBaseContext();
        }
        var field = contextImpl.getClass().getDeclaredField("mPackageManager");
        field.setAccessible(true);
        field.set(contextImpl, null);
    }

    private static void wrapGlobalSettings(Runnable callback) {
        try {
            // noinspection SoonBlockedPrivateApi,JavaReflectionMemberAccess
            var field = Settings.Global.class.getDeclaredField("sProviderHolder");
            field.setAccessible(true);
            var holder = field.get(null);
            assert holder != null;

            field = holder.getClass().getDeclaredField("mContentProvider");
            field.setAccessible(true);
            var provider = field.get(holder);
            assert provider != null;

            field = provider.getClass().getDeclaredField("mRemote");
            field.setAccessible(true);
            var binder = (IBinder) field.get(provider);
            assert binder != null;

            field.set(provider, new ShizukuBinderWrapper(binder));
            callback.run();
            field.set(provider, binder);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "wrapGlobalSettings", e);
        }
    }

    public static void wrap(PackageInstaller.Session session) {
        try {
            // noinspection SoonBlockedPrivateApi
            var field = session.getClass().getDeclaredField("mSession");
            field.setAccessible(true);
            var mSession = (IPackageInstallerSession) field.get(session);
            assert mSession != null;
            var wrapper = new ShizukuBinderWrapper(mSession.asBinder());
            field.set(session, IPackageInstallerSession.Stub.asInterface(wrapper));
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "wrap", e);
        }
    }

    public static void disableAdbVerify(Context context) {
        String name = "verifier_verify_adb_installs";
        var included = Settings.Global.getInt(context.getContentResolver(), name, 1) != 0;
        if (included) {
            wrapGlobalSettings(() -> {
                var contextWrapper = new ShizukuContext(context);
                var contentResolver = new ContentResolver(contextWrapper) {
                };
                Settings.Global.putInt(contentResolver, name, 0);
            });
        }
    }

    public static void startActivity(Intent intent) {
        int userId = Os.getuid() / 100000;
        try {
            am.startActivityAsUser(null, "com.android.shell", intent, intent.getType(),
                null, null, 0, 0, null, null, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public static PackageInfo getPackageInfo(String packageName)
        throws PackageManager.NameNotFoundException {
        var matchAll = PackageManager_rename.MATCH_KNOWN_PACKAGES
            | PackageManager_rename.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
            | PackageManager_rename.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;
        var pkg = new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST);
        return pm.getPackageInfo(pkg, matchAll);
    }

    private static final class ShizukuContext extends ContextWrapper {
        ShizukuContext(Context context) {
            super(context);
        }

        @NonNull
        @Override
        @RequiresApi(Build.VERSION_CODES.S)
        public AttributionSource getAttributionSource() {
            var builder = new AttributionSource.Builder(Shizuku.getUid())
                .setPackageName("com.android.shell");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                builder.setPid(Process.INVALID_PID);
            }
            return builder.build();
        }

        @NonNull
        @Override
        public String getOpPackageName() {
            return "com.android.shell";
        }
    }
}
