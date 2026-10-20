package io.github.vvb2060.packageinstaller.core;

import android.Manifest;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.Intent_rename;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageInstaller_rename;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager_rename;
import android.content.pm.VersionedPackage;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.graphics.drawable.DrawableKt;
import androidx.lifecycle.MutableLiveData;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;

import javax.net.ssl.SSLContext;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.apk.ApkLite;
import io.github.vvb2060.packageinstaller.apk.ZipParser;
import io.github.vvb2060.packageinstaller.apksig.Block;
import io.github.vvb2060.packageinstaller.apksig.Parser;
import io.github.vvb2060.packageinstaller.datamodel.DownloadEngine;
import io.github.vvb2060.packageinstaller.datamodel.FileDataSource;
import io.github.vvb2060.packageinstaller.datamodel.NetDataSource;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.installmodel.InstallFailed;
import io.github.vvb2060.packageinstaller.installmodel.InstallInstalling;
import io.github.vvb2060.packageinstaller.installmodel.InstallParse;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.installmodel.InstallSuccess;
import io.github.vvb2060.packageinstaller.installmodel.InstallUserAction;
import io.github.vvb2060.packageinstaller.installmodel.PackageUserAction;
import io.github.vvb2060.packageinstaller.provider.ShizukuProvider;
import rikka.shizuku.Shizuku;

public class InstallRepository {
    private static final String TAG = InstallRepository.class.getSimpleName();

    public final MutableLiveData<InstallStage> installResult = new MutableLiveData<>();
    public final MutableLiveData<Integer> stagingProgress = new MutableLiveData<>();
    private final Application context;
    private PackageManager packageManager;
    private PackageInstaller packageInstaller;
    private int sessionId = SessionInfo.INVALID_ID;
    private int callingUid = Process.INVALID_UID;
    private Intent intent;
    private ApkLite apkLite;
    private ZipParser zipParser;
    private Closeable closeable;

    public InstallRepository(Application context) {
        this.context = context;
    }

    public boolean preCheck(Intent intent) {
        ShizukuProvider.waitForBinder(context);
        if (!Shizuku.pingBinder()
            || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            || Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES)
            != PackageManager.PERMISSION_GRANTED) {
            installResult.postValue(new InstallAborted(InstallAborted.ABORT_SHIZUKU, null,
                context.getPackageManager().getLaunchIntentForPackage(
                    rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID)));
            return false;
        }

        this.intent = intent;
        Log.v(TAG, "Intent: " + intent);

        BinderWrapper.init(context);
        packageManager = context.getPackageManager();
        packageInstaller = BinderWrapper.installer;
        BinderWrapper.disableAdbVerify(context);
        PreferredActivity.set(packageManager);

        installResult.postValue(new InstallParse());
        return true;
    }

    public void parseUri() {
        var uri = intent.getData();
        if (uri == null) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            var scheme = uri.getScheme();
            if ("package".equals(scheme)) {
                var packageName = uri.getSchemeSpecificPart();
                installResult.postValue(processPackageUri(packageName));
                return;
            }
            if ("market".equals(scheme) && "details".equals(uri.getAuthority())) {
                var packageName = uri.getQueryParameter("id");
                if (packageName != null) {
                    installResult.postValue(processPackageUri(packageName));
                    return;
                }
            }
            if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
                installResult.postValue(processContentUri(uri));
                return;
            }
            if ("https".equals(scheme) || "http".equals(scheme)) {
                installResult.postValue(processUrl(uri.toString()));
                return;
            }
        }
        var packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (packageName != null) {
            installResult.postValue(processPackageUri(packageName));
            return;
        }
        var url = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (url != null) {
            installResult.postValue(processUrl(url));
            return;
        }
        installResult.postValue(new InstallAborted(InstallAborted.ABORT_INFO));
    }

    public void install(boolean setInstaller, boolean commit, boolean full, boolean removeSplit) {
        installResult.postValue(new InstallInstalling(apkLite));
        if (zipParser == null) {
            installPackageUri();
            return;
        }

        if (sessionId == SessionInfo.INVALID_ID) {
            try {
                var params = createSessionParams(setInstaller, full);
                sessionId = packageInstaller.createSession(params);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create a new session", e);
                var aborted = new InstallAborted(InstallAborted.ABORT_CREATE, e.toString());
                installResult.postValue(aborted);
                return;
            }
        }

        try (var session = packageInstaller.openSession(sessionId)) {
            BinderWrapper.wrap(session);
            if (removeSplit) {
                session.removeSplit(apkLite.splitName);
            } else {
                zipParser.sessionWrite(session, stagingProgress);
                closeable.close();
                zipParser = null;
            }
            if (commit) {
                commit(session);
            } else {
                installResult.postValue(new InstallAborted(InstallAborted.ABORT_CLOSE));
            }
        } catch (IOException | RuntimeException e) {
            cleanupInstall();
            Log.e(TAG, "Could not write APK.", e);
            var aborted = new InstallAborted(InstallAborted.ABORT_WRITE, e.toString());
            installResult.postValue(aborted);
        }
    }

    private void commit(PackageInstaller.Session session) {
        stagingProgress.postValue(101);
        var receiver = new LocalIntentReceiver(this::setStageBasedOnResult);
        try {
            // noinspection RequestInstallPackagesPolicy
            session.commit(receiver.getIntentSender());
        } catch (RuntimeException e) {
            cleanupInstall();
            Log.e(TAG, "Failed to commit session", e);
            setStageBasedOnResult(PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR, e.toString());
        }
    }

    private InstallStage processUrl(String url) {
        Log.v(TAG, "URL: " + url);

        ApkLite parsedApk;
        try {
            var engine = new DownloadEngine(SSLContext.getDefault());
            closeable = engine;
            var dataSource = NetDataSource.open(engine, new URI(url));
            zipParser = new ZipParser(dataSource);
            parsedApk = zipParser.parse();
            dataSource.stopPersistentCache();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse APK: " + url, e);
            return new InstallAborted(InstallAborted.ABORT_PARSE, e.toString());
        }

        var stage = processApkLite(parsedApk);
        if (parsedApk.label == null) {
            parsedApk.label = ((DownloadEngine) closeable).getFileName();
        }
        return stage;
    }

    private InstallStage processContentUri(Uri uri) {
        Log.v(TAG, "URI: " + uri);
        var provider = packageManager.resolveContentProvider(uri.getAuthority(), 0);
        if (provider != null) {
            callingUid = provider.applicationInfo.uid;
        }
        var cr = context.getContentResolver();

        ApkLite parsedApk;
        try {
            var afd = cr.openAssetFileDescriptor(uri, "r");
            if (afd == null) throw new IOException("Failed to open uri");
            closeable = afd;
            var dataSource = FileDataSource.asDataSource(afd);
            zipParser = new ZipParser(dataSource);
            parsedApk = zipParser.parse();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse APK: " + uri, e);
            return new InstallAborted(InstallAborted.ABORT_PARSE, e.toString());
        }

        var stage = processApkLite(parsedApk);
        if (parsedApk.label == null) {
            try (var cursor = cr.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    var displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        parsedApk.label = cursor.getString(displayNameIndex);
                    }
                }
            }
        }
        return stage;
    }

    private InstallStage processApkLite(ApkLite apk) {
        PackageInfo old;
        try {
            old = BinderWrapper.getPackageInfo(apk.packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
            old = null;
        }

        var full = true;
        if (apk.isSplit()) {
            full = findBaseSessionId(apk);
            if (sessionId == SessionInfo.INVALID_ID) {
                if (old != null && old.getLongVersionCode() == apk.versionCode) {
                    full = false;
                } else {
                    return new InstallAborted(InstallAborted.ABORT_SPLIT);
                }
            }
        }

        if (old != null) {
            if (apk.label == null) {
                apk.label = old.applicationInfo.loadLabel(packageManager).toString();
            }
            if (apk.icon == null) {
                apk.icon = old.applicationInfo.loadIcon(packageManager);
            }
        }

        if (full && old != null && Block.findBlock(apk.signingBlock) != null) {
            var base = old.applicationInfo.sourceDir;
            try (var pfd = ParcelFileDescriptor.open(new File(base),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
                var dataSource = FileDataSource.asDataSource(pfd);
                var oldMap = Parser.perse(dataSource);
                apk.isSame = Block.check(oldMap, apk.signingBlock);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Failed to parse APK: " + base, e);
            }
        }

        apkLite = apk;
        var skipCreate = sessionId != SessionInfo.INVALID_ID;
        return new InstallUserAction(apk, old, full, skipCreate);
    }

    private boolean findBaseSessionId(ApkLite apk) {
        var full = true;
        var allSessions = packageInstaller.getAllSessions();
        for (int i = 0, allSessionsSize = allSessions.size(); i < allSessionsSize; i++) {
            var item = allSessions.get(i);
            if (!apk.packageName.equals(item.getAppPackageName())) continue;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // noinspection NewApi
                if (item.getInstallerUid() != Shizuku.getUid()) continue;
            }
            if (item.getInstallReason() != PackageManager.INSTALL_REASON_USER) continue;
            var rename = (PackageInstaller_rename.SessionInfo) (Object) item;
            if ((rename.installFlags & PackageManager_rename.INSTALL_FROM_ADB) == 0) continue;

            var id = item.getSessionId();
            full = item.getMode() == SessionParams.MODE_FULL_INSTALL;
            var appLabel = item.getAppLabel();
            if (appLabel != null) {
                apk.label = appLabel.toString();
            }
            var fullInfo = packageInstaller.getSessionInfo(id);
            rename = (PackageInstaller_rename.SessionInfo) (Object) fullInfo;
            var appIcon = rename.appIcon;
            if (appIcon != null) {
                apk.icon = new BitmapDrawable(context.getResources(), appIcon);
            }
            sessionId = id;
            break;
        }
        return full;
    }

    private InstallStage processPackageUri(String packageName) {
        PackageInfo info;
        try {
            info = BinderWrapper.getPackageInfo(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return new InstallAborted(InstallAborted.ABORT_NOTFOUND, e.toString());
        }

        var apk = ApkLite.fromPackageInfo(packageManager, info);
        apkLite = apk;

        var app = info.applicationInfo;
        if ((app.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
            return new PackageUserAction(apk, info);
        } else if (app.sourceDir != null && new File(app.sourceDir).exists()) {
            return new InstallUserAction(apk, info);
        }
        return processUrl("https://d.apkpure.com/b/XAPK/" + packageName + "?version=latest");
    }

    private void installPackageUri() {
        stagingProgress.postValue(101);
        try {
            var pm = (PackageManager_rename) (Object) BinderWrapper.pm;
            pm.installExistingPackage(apkLite.packageName, PackageManager.INSTALL_REASON_USER);
            setStageBasedOnResult(PackageInstaller.STATUS_SUCCESS,
                PackageManager_rename.INSTALL_SUCCEEDED, null);
        } catch (PackageManager.NameNotFoundException e) {
            setStageBasedOnResult(PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR, e.toString());
        }
    }

    private SessionParams createSessionParams(boolean setInstaller, boolean full) {
        var mode = full ? SessionParams.MODE_FULL_INSTALL : SessionParams.MODE_INHERIT_EXISTING;
        var params = new SessionParams(mode);
        String installer;
        if (setInstaller) {
            try {
                packageManager.getPackageInfo("com.android.vending",
                    PackageManager.MATCH_SYSTEM_ONLY);
                installer = "com.android.vending";
            } catch (PackageManager.NameNotFoundException ignored) {
                installer = "com.android.shell";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(closeable instanceof DownloadEngine
                    ? PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                    : PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
            }
            Uri referrerUri = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
            params.setReferrerUri(referrerUri);
            params.setOriginatingUri(intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI));
            params.setOriginatingUid(intent.getIntExtra(Intent_rename.EXTRA_ORIGINATING_UID, callingUid));
            installer = BuildConfig.APPLICATION_ID;
        }
        // noinspection NewApi
        params.setInstallerPackageName(installer);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        params.setAppPackageName(apkLite.packageName);
        if (apkLite.needSplit()) {
            if (apkLite.icon != null) {
                var bitmap = DrawableKt.toBitmap(apkLite.icon, apkLite.icon.getIntrinsicWidth(),
                    apkLite.icon.getIntrinsicHeight(), null);
                params.setAppIcon(bitmap);
            }
            params.setAppLabel(apkLite.label);
        }

        var p = (PackageInstaller_rename.SessionParams) (Object) params;
        p.installFlags |= PackageManager_rename.INSTALL_ALLOW_TEST;
        p.installFlags |= PackageManager_rename.INSTALL_REPLACE_EXISTING;
        p.installFlags |= PackageManager_rename.INSTALL_REQUEST_DOWNGRADE;
        p.installFlags |= PackageManager_rename.INSTALL_FULL_APP;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.installFlags |= PackageManager_rename.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;
            p.installFlags |= PackageManager_rename.INSTALL_REQUEST_UPDATE_OWNERSHIP;
        }
        return params;
    }

    private void setStageBasedOnResult(int statusCode, int legacyStatus, String message) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            var startIntent = BinderWrapper.pm.getLaunchIntentForPackage(apkLite.packageName);
            try {
                var info = BinderWrapper.getPackageInfo(apkLite.packageName);
                apkLite.icon = info.applicationInfo.loadIcon(packageManager);
                apkLite.label = info.applicationInfo.loadLabel(packageManager).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            installResult.postValue(new InstallSuccess(apkLite, startIntent));
        } else {
            if (message == null) {
                message = PackageManager_rename.installStatusToString(legacyStatus);
            }
            installResult.postValue(new InstallFailed(apkLite, statusCode, message));
        }
    }

    public void cleanupInstall() {
        if (sessionId != SessionInfo.INVALID_ID) {
            try {
                packageInstaller.abandonSession(sessionId);
            } catch (SecurityException ignored) {
            }
            sessionId = SessionInfo.INVALID_ID;
        }
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
            closeable = null;
        }
    }

    public void uninstallPackage(PackageInfo info) {
        var receiver = new LocalIntentReceiver((statusCode, legacyStatus, message) ->
            Log.v(TAG, "Uninstall result: " + statusCode + ", " + legacyStatus + ", " + message));
        var versionedPackage = new VersionedPackage(info.packageName, info.getLongVersionCode());
        // noinspection MissingPermission NewApi WrongConstant
        packageInstaller.uninstall(versionedPackage, PackageManager_rename.DELETE_SYSTEM_APP,
            receiver.getIntentSender());
    }

    public void setPackageEnabled(String packageName, boolean enabled) {
        var newState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        try {
            BinderWrapper.pm.setApplicationEnabledSetting(packageName, newState, 0);
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to set package enabled state", e);
        }
    }

    public void clearApplicationUserData(String packageName) {
        try {
            var pm = (PackageManager_rename) (Object) BinderWrapper.pm;
            pm.clearApplicationUserData(packageName, null);
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to clear application user data", e);
        }
    }
}
