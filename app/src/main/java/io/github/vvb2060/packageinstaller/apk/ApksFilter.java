package io.github.vvb2060.packageinstaller.apk;

import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import io.github.vvb2060.packageinstaller.zip.CentralDirectoryRecord;

class ApksFilter {

    private static List<String> densityFilter() {
        var allDpis = new int[]{
            DisplayMetrics.DENSITY_LOW,
            DisplayMetrics.DENSITY_MEDIUM,
            DisplayMetrics.DENSITY_TV,
            DisplayMetrics.DENSITY_HIGH,
            DisplayMetrics.DENSITY_XHIGH,
            DisplayMetrics.DENSITY_XXHIGH,
            DisplayMetrics.DENSITY_XXXHIGH
        };
        int deviceDpi = Resources.getSystem().getDisplayMetrics().densityDpi;

        var higherOrEqual = new ArrayList<Integer>();
        var lower = new ArrayList<Integer>();
        for (int dpi : allDpis) {
            if (dpi >= deviceDpi) {
                higherOrEqual.add(dpi);
            } else {
                lower.add(dpi);
            }
        }
        higherOrEqual.sort(Integer::compareTo);
        lower.sort((a, b) -> Integer.compare(b, a));

        var result = new ArrayList<String>(allDpis.length);
        for (int dpi : higherOrEqual) {
            result.add(dpiToName(dpi));
        }
        for (int dpi : lower) {
            result.add(dpiToName(dpi));
        }
        return result;
    }

    private static String dpiToName(int dpi) {
        return switch (dpi) {
            case DisplayMetrics.DENSITY_LOW -> "ldpi";
            case DisplayMetrics.DENSITY_MEDIUM -> "mdpi";
            case DisplayMetrics.DENSITY_TV -> "tvdpi";
            case DisplayMetrics.DENSITY_HIGH -> "hdpi";
            case DisplayMetrics.DENSITY_XHIGH -> "xhdpi";
            case DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi";
            case DisplayMetrics.DENSITY_XXXHIGH -> "xxxhdpi";
            default -> throw new IllegalArgumentException();
        };
    }

    private static List<String> languageFilter() {
        var res = Resources.getSystem();
        var languageSet = new LinkedHashSet<String>();
        var localeList = res.getConfiguration().getLocales();
        for (int i = 0, size = localeList.size(); i < size; i++) {
            var locale = localeList.get(i);
            var parts = locale.toLanguageTag().split("-", 2);
            languageSet.add(parts[0]);
        }
        languageSet.add("base");
        var allLocal = res.getAssets().getLocales();
        for (var tag : allLocal) {
            var locale = Locale.forLanguageTag(tag);
            var parts = locale.toLanguageTag().split("-", 2);
            languageSet.add(parts[0]);
        }
        return new ArrayList<>(languageSet);
    }

    private static List<String> abiFilter() {
        var abiSet = new LinkedHashSet<String>();
        for (var abi : Build.SUPPORTED_ABIS) {
            abiSet.add(abi.replace('-', '_'));
        }
        abiSet.addAll(Arrays.asList("armeabi_v7a", "x86", "x86_64", "arm64_v8a", "riscv64"));
        return new ArrayList<>(abiSet);
    }

    private static String getLabel(CentralDirectoryRecord entry) {
        var name = entry.getName();
        if (name.endsWith(".apk")) {
            name = name.substring(0, name.length() - ".apk".length());
        }
        if (name.startsWith("split_")) {
            name = name.substring("split_".length());
        }

        if (name.startsWith("config.")) {
            return name.substring("config.".length());
        } else {
            int lastIndex = name.lastIndexOf(".config.");
            return lastIndex == -1 ? name : name.substring(lastIndex + ".config.".length());
        }
    }

    private static String getApksLabel(CentralDirectoryRecord entry) {
        var name = entry.getName();
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash != -1) {
            name = name.substring(lastSlash + 1);
        }
        if (name.endsWith(".apk")) {
            name = name.substring(0, name.length() - ".apk".length());
        }
        if (name.endsWith("_2") || name.endsWith("_3")) {
            return null;
        }

        if (name.startsWith("base-")) {
            return name.substring("base-".length());
        } else {
            int lastDash = name.lastIndexOf('-');
            return lastDash == -1 ? name : name.substring(lastDash + 1);
        }
    }

    private static String convert(String s) {
        return switch (s) {
            case "master" -> "base";
            case "tl" -> "fil";
            case "iw" -> "he";
            case "ji" -> "yi";
            case "in" -> "id";
            case null, default -> s;
        };
    }

    static List<CentralDirectoryRecord> filterZipEntries(
        Collection<CentralDirectoryRecord> entries, boolean isApks) {
        var apkMap = new HashMap<String, List<CentralDirectoryRecord>>();
        entries.forEach(entry -> {
            var label = convert(isApks ? getApksLabel(entry) : getLabel(entry));
            if (label != null) {
                apkMap.computeIfAbsent(label, _ -> new ArrayList<>()).add(entry);
            }
        });

        Arrays.asList(abiFilter(), densityFilter(), languageFilter()).forEach(filter -> {
            var found = false;
            for (int i = 0, filterSize = filter.size(); i < filterSize; i++) {
                var name = filter.get(i);
                if (apkMap.containsKey(name)) {
                    if (!found) {
                        found = true;
                    } else if (!"base".equals(name)) {
                        apkMap.remove(name);
                    }
                }
            }
        });

        var result = new ArrayList<CentralDirectoryRecord>();
        for (var list : apkMap.values()) {
            result.addAll(list);
        }
        return result;
    }
}
