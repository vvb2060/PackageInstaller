package io.github.vvb2060.packageinstaller.installmodel;

import android.content.pm.PackageInfo;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class PackageUserAction extends InstallStage {

    public final ApkLite apkLite;
    public final PackageInfo info;

    public PackageUserAction(ApkLite apkLite, PackageInfo info) {
        super(PACKAGE_USER_ACTION);
        this.apkLite = apkLite;
        this.info = info;
    }

}
