package io.github.vvb2060.packageinstaller.provider;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.core.PreferredActivity;
import moe.shizuku.api.BinderContainer;
import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

public class ShizukuProvider extends ContentProvider {
    private static final String TAG = ShizukuProvider.class.getSimpleName();
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";
    private static final String PERMISSION_MANAGER = "moe.shizuku.manager.permission.MANAGER";

    @Override
    public boolean onCreate() {
        Shizuku.addBinderReceivedListener(() -> {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                var context = getContext();
                BinderWrapper.init(context);
                PreferredActivity.set(context.getPackageManager());
            }
        });
        if (!Sui.isSui()) {
            Sui.init(BuildConfig.APPLICATION_ID);
        }
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        var reply = new Bundle();
        if (Sui.isSui()) return reply;
        if (extras == null) return reply;
        if (method.equals(rikka.shizuku.ShizukuProvider.METHOD_SEND_BINDER)) {
            handleSendBinder(extras);
        }
        return reply;
    }

    @SuppressLint("RestrictedApi")
    private void handleSendBinder(@NonNull Bundle extras) {
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        BinderContainer parcelable = extras.getParcelable(EXTRA_BINDER);
        if (parcelable == null) return;
        var binder = parcelable.binder;
        if (binder == null) return;
        getContext().getMainExecutor().execute(() ->
            Shizuku.onBinderReceived(binder, BuildConfig.APPLICATION_ID));
    }

    @SuppressLint("RestrictedApi")
    public static void waitForBinder(Context context) {
        if (Shizuku.pingBinder()) return;

        var latch = new CountDownLatch(1);
        var receiver = new Binder() {
            @Override
            protected boolean onTransact(int code, @NonNull Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == 1) {
                    var binder = data.readStrongBinder();
                    if (binder != null) {
                        context.getMainExecutor().execute(() -> {
                            Shizuku.onBinderReceived(binder, BuildConfig.APPLICATION_ID);
                            latch.countDown();
                        });
                    } else {
                        Log.w(TAG, "shizuku is not running");
                        latch.countDown();
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        var data = new Bundle();
        data.putBinder("binder", receiver);
        var intent = new Intent(ACTION_REQUEST_BINDER)
            .setPackage(rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", data);

        context.sendOrderedBroadcast(intent, PERMISSION_MANAGER, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                latch.countDown();
            }
        }, null, 0, null, null);
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public final String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
