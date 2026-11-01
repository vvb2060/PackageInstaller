package io.github.vvb2060.packageinstaller.installmodel;

public abstract sealed class InstallStage permits InstallParse, InstallUserAction, InstallFailed,
    PackageUserAction, SettingsUserAction, InstallInstalling, InstallSuccess, InstallAborted {

    public static final int STAGE_ABORTED = 0;
    public static final int STAGE_PARSE = 1;
    public static final int STAGE_USER_ACTION = 2;
    public static final int STAGE_INSTALLING = 3;
    public static final int STAGE_SUCCESS = 4;
    public static final int STAGE_FAILED = 5;
    public static final int PACKAGE_USER_ACTION = 6;
    public static final int SETTINGS_USER_ACTION = 7;

    public final int stageCode;

    protected InstallStage(int stageCode) {
        this.stageCode = stageCode;
    }

}
