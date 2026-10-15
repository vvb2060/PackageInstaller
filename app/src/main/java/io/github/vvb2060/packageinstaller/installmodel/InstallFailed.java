package io.github.vvb2060.packageinstaller.installmodel;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class InstallFailed extends InstallStage {

    public final ApkLite apkLite;
    public final int statusCode;
    public final String message;

    public InstallFailed(ApkLite apkLite, int statusCode, String message) {
        super(STAGE_FAILED);
        this.apkLite = apkLite;
        this.statusCode = statusCode;
        this.message = message;
    }
}
