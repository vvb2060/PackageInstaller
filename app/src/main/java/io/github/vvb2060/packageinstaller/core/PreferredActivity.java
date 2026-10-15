package io.github.vvb2060.packageinstaller.core;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager_rename;
import android.net.Uri;
import android.os.Build;
import android.system.Os;
import android.util.Log;
import android.util.LogPrinter;

import java.util.ArrayList;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.ui.InstallLaunch;

public class PreferredActivity {

    private static final String TAG = PreferredActivity.class.getSimpleName();
    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static void list(PackageManager pm, String packageName) {
        var preferredActivities = new ArrayList<ComponentName>();
        var intentFilters = new ArrayList<IntentFilter>();
        pm.getPreferredActivities(intentFilters, preferredActivities, packageName);
        Log.d(TAG, packageName + " have " + preferredActivities.size()
            + " number of activities in preferred list");
        for (int index = 0; index < preferredActivities.size(); index++) {
            Log.d(TAG, "-Preferred activity: " + preferredActivities.get(index).getClassName());
            intentFilters.get(index).dump(new LogPrinter(Log.DEBUG, TAG), "--");
        }
    }

    private static Intent makeIntent(String action) {
        var uri = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.APPLICATION_ID)
            .path("base.apk")
            .build();
        return new Intent(action)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setDataAndType(uri, PACKAGE_MIME_TYPE);
    }

    public static String getSystemInstaller(PackageManager pm) {
        var intent = makeIntent(Intent.ACTION_INSTALL_PACKAGE);
        var matches = pm.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY);
        return matches.get(0).activityInfo.packageName;
    }

    private static boolean check(PackageManager pm, String action) {
        var intent = makeIntent(action);
        var info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null) return false;
        var packageName = info.activityInfo.packageName;
        var isSelf = packageName.equals(BuildConfig.APPLICATION_ID);
        var isSystem = packageName.equals("android");
        var isShizukuUser = Os.getuid() / 100000 == 0;

        if (isShizukuUser && !isSelf && !isSystem) {
            Log.v(TAG, "Clear preferred activity for " + info.activityInfo.name);
            pm.clearPackagePreferredActivities(packageName);
        }

        return isSelf;
    }

    private static void add(PackageManager pm, String action) {
        int flags = PackageManager.MATCH_DEFAULT_ONLY;
        var intent = makeIntent(action);
        var infos = pm.queryIntentActivities(intent, flags);
        var set = new ComponentName[infos.size()];
        for (int i = 0, size = infos.size(); i < size; i++) {
            var info = infos.get(i);
            var activityInfo = info.activityInfo;
            var componentName = new ComponentName(activityInfo.packageName, activityInfo.name);
            set[i] = componentName;
        }
        IntentFilter filter;
        try {
            filter = new IntentFilter(action, PACKAGE_MIME_TYPE);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException(e);
        }
        var activity = new ComponentName(BuildConfig.APPLICATION_ID, InstallLaunch.class.getName());
        int match = IntentFilter.MATCH_CATEGORY_TYPE | IntentFilter.MATCH_ADJUSTMENT_MASK;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var rename = (PackageManager_rename) (Object) pm;
            rename.addUniquePreferredActivity(filter, match, set, activity);
        } else {
            pm.addPreferredActivity(filter, match, set, activity);
        }
    }

    public static void set(PackageManager rawPm) {
        var pm = BinderWrapper.pm;
        if (check(pm, Intent.ACTION_VIEW) && check(pm, Intent.ACTION_INSTALL_PACKAGE)) {
            return;
        }

        rawPm.clearPackagePreferredActivities(BuildConfig.APPLICATION_ID);
        add(pm, Intent.ACTION_VIEW);
        add(pm, Intent.ACTION_INSTALL_PACKAGE);
        Log.v(TAG, "Preferred activities set for " + BuildConfig.APPLICATION_ID);

        if (BuildConfig.DEBUG) {
            list(pm, getSystemInstaller(pm));
        }
    }
}
