package io.github.vvb2060.packageinstaller.apk;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Map;

public class ApkLite {
    public final String packageName;
    public final String splitName;
    public final String configForSplit;
    public final String requiredSplitTypes;
    public final String splitTypes;
    public final long versionCode;
    public final String versionName;
    public final String minSdkVersion;
    public final String targetSdkVersion;
    public String label;
    public Drawable icon;

    public boolean zip = false;
    public long size = 0;
    public Map<Integer, Object> signingBlock = null;
    public boolean[] isSame = null;

    private ApkLite(
        String packageName,
        String splitName,
        String configForSplit,
        String requiredSplitTypes,
        String splitTypes,
        long versionCode,
        String versionName,
        String minSdkVersion,
        String targetSdkVersion,
        String label,
        Drawable icon
    ) {
        this.packageName = packageName;
        this.splitName = splitName;
        this.configForSplit = configForSplit;
        this.requiredSplitTypes = requiredSplitTypes;
        this.splitTypes = splitTypes;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.minSdkVersion = minSdkVersion;
        this.targetSdkVersion = targetSdkVersion;
        this.label = label;
        this.icon = icon;
    }

    public Drawable getIcon() {
        if (icon != null) {
            return icon;
        }
        return Resources.getSystem().getDrawable(android.R.drawable.sym_def_app_icon, null);
    }

    public String getLabel() {
        return label != null ? label : packageName;
    }

    public boolean isSplit() {
        return !zip && splitName != null && !splitName.isEmpty();
    }

    public boolean needSplit() {
        return !zip && (isSplit() || requiredSplitTypes != null || splitTypes != null);
    }

    @SuppressLint({"InlinedApi", "ResourceType"})
    public static ApkLite fromAndroidManifest(XmlResourceParser parser, Resources res)
        throws IOException, XmlPullParserException {
        Drawable icon = null;
        String label = null;
        String packageName = null;
        String splitName = null;
        String configForSplit = null;
        int versionCode = 0;
        int versionCodeMajor = 0;
        String versionName = null;
        String requiredSplitTypes = null;
        String splitTypes = null;
        String minSdk = null;
        String targetSdk = null;

        for (int type = parser.getEventType();
             type != XmlPullParser.END_DOCUMENT;
             type = parser.next()) {
            if (type != XmlPullParser.START_TAG) continue;

            var tagName = parser.getName();
            if ("application".equals(tagName)) {
                var sa = res.obtainAttributes(parser, new int[]{android.R.attr.label,
                    android.R.attr.icon});
                var labelValue = sa.peekValue(0);
                if (labelValue != null && labelValue.string != null) {
                    label = labelValue.string.toString();
                }
                try {
                    icon = sa.getDrawable(1);
                } catch (Resources.NotFoundException ignored) {
                }
                sa.recycle();

                if (packageName != null && minSdk != null) {
                    break;
                }
            } else if ("manifest".equals(tagName)) {
                packageName = parser.getAttributeValue(null, "package");
                splitName = parser.getAttributeValue(null, "split");
                configForSplit = parser.getAttributeValue(null, "configForSplit");
                var sa = res.obtainAttributes(parser, new int[]{android.R.attr.versionCode,
                    android.R.attr.versionName, android.R.attr.versionCodeMajor,
                    android.R.attr.requiredSplitTypes, android.R.attr.splitTypes});
                versionCode = sa.getInteger(0, 0);
                versionName = sa.getString(1);
                versionCodeMajor = sa.getInteger(2, 0);
                requiredSplitTypes = sa.getString(3);
                splitTypes = sa.getString(4);
                sa.recycle();
            } else if ("uses-sdk".equals(tagName)) {
                var sa = res.obtainAttributes(parser, new int[]{android.R.attr.minSdkVersion,
                    android.R.attr.targetSdkVersion});
                minSdk = sa.getString(0);
                targetSdk = sa.getString(1);
                sa.recycle();
            }
        }

        if (packageName == null) {
            throw new IOException("Missing package name");
        }

        var version = (((long) versionCodeMajor) << 32) | (((long) versionCode) & 0xffffffffL);
        var min = minSdk != null ? minSdk : "1";
        var target = targetSdk != null ? targetSdk : min;
        return new ApkLite(packageName, splitName, configForSplit, requiredSplitTypes, splitTypes,
            version, versionName, min, target, label, icon);
    }

    public static ApkLite fromPackageInfo(PackageManager pm, PackageInfo pkgInfo) {
        var info = pkgInfo.applicationInfo;
        if (info == null) {
            info = new ApplicationInfo();
            info.packageName = pkgInfo.packageName;
        }
        var apkLite = new ApkLite(pkgInfo.packageName, null, null, null, null,
            pkgInfo.getLongVersionCode(), pkgInfo.versionName,
            String.valueOf(info.minSdkVersion), String.valueOf(info.targetSdkVersion),
            info.loadLabel(pm).toString(), info.loadIcon(pm));
        apkLite.zip = true;
        return apkLite;
    }
}
