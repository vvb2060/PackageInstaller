package io.github.vvb2060.packageinstaller.installmodel;

public abstract sealed class InstallStage permits InstallParse, InstallUserAction,
    PackageUserAction, InstallInstalling, InstallSuccess, InstallFailed, InstallAborted {

    public static final int STAGE_ABORTED = 0;
    public static final int STAGE_PARSE = 1;
    public static final int STAGE_USER_ACTION = 2;
    public static final int STAGE_ARCHIVE = 3;
    public static final int STAGE_INSTALLING = 4;
    public static final int STAGE_SUCCESS = 5;
    public static final int STAGE_FAILED = 6;

    public final int stageCode;

    protected InstallStage(int stageCode) {
        this.stageCode = stageCode;
    }

}
