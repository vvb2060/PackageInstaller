package io.github.vvb2060.packageinstaller.installmodel;

import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller.SessionInfo;

import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class InstallUserAction extends InstallStage {

    public final ApkLite apkLite;
    @Nullable
    public final PackageInfo oldApk;
    public final boolean fullInstall;
    public final boolean safeMode;
    public final int sessionId;

    public InstallUserAction(ApkLite apkLite, PackageInfo oldApk) {
        this(apkLite, oldApk, true, true, SessionInfo.INVALID_ID);
    }

    public InstallUserAction(
        ApkLite apkLite,
        PackageInfo oldApk,
        boolean fullInstall,
        boolean safeMode,
        int sessionId
    ) {
        super(STAGE_USER_ACTION);
        this.apkLite = apkLite;
        this.oldApk = oldApk;
        this.fullInstall = fullInstall;
        this.safeMode = safeMode;
        this.sessionId = sessionId;
    }

}
