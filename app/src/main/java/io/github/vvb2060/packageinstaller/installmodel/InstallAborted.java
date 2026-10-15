package io.github.vvb2060.packageinstaller.installmodel;

import android.content.Intent;

import androidx.annotation.Nullable;

public final class InstallAborted extends InstallStage {

    public static final int ABORT_CLOSE = 0;
    public static final int ABORT_SHIZUKU = 1;
    public static final int ABORT_PARSE = 2;
    public static final int ABORT_SPLIT = 3;
    public static final int ABORT_NOTFOUND = 4;
    public static final int ABORT_CREATE = 5;
    public static final int ABORT_WRITE = 6;
    public static final int ABORT_INFO = 7;

    public final int abortReason;
    @Nullable
    public final String message;
    @Nullable
    public final Intent intent;

    public InstallAborted(int abortReason) {
        this(abortReason, null, null);
    }

    public InstallAborted(int abortReason, String message) {
        this(abortReason, message, null);
    }

    public InstallAborted(int abortReason, String message, Intent intent) {
        super(STAGE_ABORTED);
        this.abortReason = abortReason;
        this.message = message;
        this.intent = intent;
    }

}
