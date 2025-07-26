package android.content.pm;

import android.graphics.Bitmap;
import android.net.Uri;

public class PackageInstaller_rename {
    public static String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";

    public static class SessionParams {
        public int mode;

        public int installFlags;

        public int installLocation;

        public int installReason;

        public long sizeBytes;

        public String appPackageName;

        public Bitmap appIcon;

        public String appLabel;

        public long appIconLastModified;

        public Uri originatingUri;

        public int originatingUid;

        public Uri referrerUri;

        public String abiOverride;

        public String volumeUuid;

        public String[] grantedRuntimePermissions;

        public String installerPackageName;
    }

    public static class SessionInfo {
        public int sessionId;

        public String installerPackageName;

        public String resolvedBaseCodePath;

        public float progress;

        public boolean sealed;

        public boolean active;

        public int mode;

        public int installReason;

        public long sizeBytes;

        public String appPackageName;

        public Bitmap appIcon;

        public CharSequence appLabel;

        public int installLocation;

        public Uri originatingUri;

        public int originatingUid;

        public Uri referrerUri;

        public String[] grantedRuntimePermissions;

        public int installFlags;

    }
}
