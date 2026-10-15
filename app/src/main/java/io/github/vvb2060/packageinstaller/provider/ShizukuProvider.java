package io.github.vvb2060.packageinstaller.provider;

import android.content.pm.PackageManager;

import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.core.PreferredActivity;
import rikka.shizuku.Shizuku;

public class ShizukuProvider extends rikka.shizuku.ShizukuProvider {

    @Override
    public boolean onCreate() {
        Shizuku.addBinderReceivedListener(() -> {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                var context = getContext();
                BinderWrapper.init(context);
                PreferredActivity.set(context.getPackageManager());
            }
        });

        return super.onCreate();
    }
}
