package io.github.vvb2060.packageinstaller.installmodel;

import android.content.pm.PackageInfo;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class PackageUserAction extends InstallStage {

    public final ApkLite apkLite;
    public final PackageInfo oldApk;

    public PackageUserAction(ApkLite apkLite, PackageInfo oldApk) {
        super(STAGE_ARCHIVE);
        this.apkLite = apkLite;
        this.oldApk = oldApk;
    }

}
