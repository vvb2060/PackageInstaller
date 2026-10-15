package android.content.res;

import android.content.res.loader.AssetsProvider;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.IOException;

public class ApkAssets {

    public static native ApkAssets loadFromFd(FileDescriptor fd, String friendlyName,
                                              long offset, long length, int flags,
                                              AssetsProvider assets) throws IOException;

    public native String getAssetPath();

    public native XmlResourceParser openXml(String fileName) throws IOException;

    public native void close();
}
