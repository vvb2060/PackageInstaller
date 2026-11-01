package io.github.vvb2060.packageinstaller.installmodel;

import android.content.Intent;

import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

public final class InstallSuccess extends InstallStage {
    public final ApkLite apkLite;
    @Nullable
    public final Intent startIntent;
    public final boolean safeMode;

    public InstallSuccess(ApkLite apkLite, Intent startIntent, boolean safeMode) {
        super(STAGE_SUCCESS);
        this.apkLite = apkLite;
        this.startIntent = startIntent;
        this.safeMode = safeMode;
    }

}
