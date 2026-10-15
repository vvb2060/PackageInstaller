package io.github.vvb2060.packageinstaller.installmodel;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class InstallInstalling extends InstallStage {

    public final ApkLite apkLite;

    public InstallInstalling(ApkLite apkLite) {
        super(STAGE_INSTALLING);
        this.apkLite = apkLite;
    }

}
