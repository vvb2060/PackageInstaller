package io.github.vvb2060.packageinstaller.installmodel;

public final class SettingsUserAction extends InstallStage {

    public final boolean safeMode;

    public SettingsUserAction(boolean safeMode) {
        this.safeMode = safeMode;
        super(SETTINGS_USER_ACTION);
    }
}
