package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TabStopSpan;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallUserAction;

public class InstallConfirmationFragment extends BaseDialogFragment {

    private final InstallUserAction mDialogData;
    private AlertDialog mDialog;
    private CheckBox mCheckBox;
    private TextView mTextView;

    public InstallConfirmationFragment(InstallUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var context = requireContext();
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        dialogView.requireViewById(R.id.install_confirm).setVisibility(View.VISIBLE);
        mTextView = dialogView.requireViewById(R.id.message);
        mCheckBox = dialogView.requireViewById(R.id.set_installer);
        if (mDialogData.getSkipCreate()) {
            mCheckBox.setVisibility(View.GONE);
        }

        var sb = new SpannableStringBuilder();
        getInfo(context, sb);
        var old = mDialogData.getOldApk();
        int question = R.string.install_confirm_question;
        if (old != null) {
            var flags = old.applicationInfo.flags;
            var installer = BuildConfig.APPLICATION_ID.equals(old.sharedUserId);
            var installed = (flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            if (!installer && savedInstanceState == null) {
                mCheckBox.setChecked(true);
            }
            if (installed) {
                question = R.string.install_confirm_question_update;
            }
        }
        var full = mDialogData.getFullInstall();
        var removeSplit = false;
        if (!full) {
            question = R.string.install_confirm_question_split;
            for (String splitName : old.splitNames) {
                if (splitName.equals(mDialogData.getApkLite().getSplitName())) {
                    question = R.string.install_confirm_question_split_remove;
                    removeSplit = true;
                    break;
                }
            }
        }
        var remove = removeSplit;
        sb.append(context.getString(question), new StyleSpan(Typeface.ITALIC),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        mTextView.setText(sb, TextView.BufferType.SPANNABLE);

        mDialog = new AlertDialog.Builder(context)
            .setIcon(mDialogData.getApkLite().getIcon())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setView(dialogView)
            .setPositiveButton(old != null ? R.string.update : R.string.install,
                (dialogInt, which) ->
                    mViewModel.initiateInstall(mCheckBox.isChecked(), true, full, remove))
            .setNegativeButton(android.R.string.cancel,
                (dialogInt, which) -> cleanAndFinish())
            .setNeutralButton(R.string.add_more,
                (dialogInt, which) ->
                    mViewModel.initiateInstall(mCheckBox.isChecked(), false, full, remove))
            .create();
        return mDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
        if (!mDialogData.getApkLite().needSplit()) {
            mDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.GONE);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
    }

    private void getInfo(Context context, SpannableStringBuilder sb) {
        var apk = mDialogData.getApkLite();
        var old = mDialogData.getOldApk();
        append(context, sb, R.string.package_name, apk.getPackageName());
        if (apk.needSplit()) {
            append(context, sb, R.string.split_name, apk.getSplitName());
            append(context, sb, R.string.split_types, apk.getSplitTypes());
            append(context, sb, R.string.required_split_types, apk.getRequiredSplitTypes());
        }
        if (apk.isSplit()) return;

        var ver = apk.getVersionName() + " (" + apk.getVersionCode() + ")";
        var min = getAndroidName(apk.getMinSdkVersion());
        var target = getAndroidName(apk.getTargetSdkVersion());
        if (old != null) {
            var oldVer = old.versionName + " (" + old.getLongVersionCode() + ")";
            var oldMin = getAndroidName(old.applicationInfo.minSdkVersion);
            var oldTarget = getAndroidName(old.applicationInfo.targetSdkVersion);
            append(context, sb, oldVer, old.getLongVersionCode(), ver, apk.getVersionCode());
            append(context, sb, R.string.min_sdk, oldMin, min);
            append(context, sb, R.string.target_sdk, oldTarget, target);
        } else {
            append(context, sb, R.string.version, ver);
            append(context, sb, R.string.min_sdk, min);
            append(context, sb, R.string.target_sdk, target);
        }
    }

    private void append(Context context, SpannableStringBuilder sb, int label, String now) {
        if (now != null && !now.isEmpty()) {
            sb.append(context.getString(label));
            sb.append(now).append("\n");
        }
    }

    private void append(Context context, SpannableStringBuilder sb,
                        int label, String old, String now) {
        if (old.equals(now)) {
            append(context, sb, label, now);
        } else {
            sb.append(context.getString(label));
            sb.append(old);
            sb.append(" → ");
            sb.append(now, new StyleSpan(Typeface.BOLD),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n");
        }
    }

    private void append(Context context, SpannableStringBuilder sb,
                        String old, long oldVer,
                        String now, long nowVer) {
        if (old.equals(now)) {
            append(context, sb, R.string.version, now);
            return;
        }
        var label = context.getString(R.string.version);
        var start = sb.length();
        sb.append(label).append("\t");
        sb.append(old).append("\n");
        var indicatorStart = sb.length();
        sb.append("\t");
        sb.append(now, new StyleSpan(Typeface.BOLD),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n");
        var end = sb.length();
        var paint = mTextView.getPaint();
        int offset = (int) paint.measureText(label) + 1;
        sb.setSpan(new TabStopSpan.Standard(offset), start, end,
            SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE);

        String indicator;
        if (oldVer < nowVer) {
            indicator = "▲";
        } else if (oldVer > nowVer) {
            indicator = "▼";
        } else {
            indicator = "=";
        }
        var indicatorWidth = paint.measureText(indicator);
        var indicatorSpan = new ReplacementSpan() {
            @Override
            public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                               @Nullable Paint.FontMetricsInt fm) {
                return offset;
            }

            @Override
            public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                             float x, int top, int y, int bottom, @NonNull Paint paint) {
                float textX = x + (offset - indicatorWidth) / 2;
                canvas.drawText(indicator, textX, y, paint);
            }
        };
        sb.setSpan(indicatorSpan, indicatorStart, indicatorStart + 1,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String getAndroidName(String apiLevel) {
        try {
            var api = Integer.parseInt(apiLevel);
            return getAndroidName(api);
        } catch (NumberFormatException ignored) {
            return apiLevel;
        }
    }

    private static String getAndroidName(int apiLevel) {
        return switch (apiLevel) {
            case Build.VERSION_CODES.CUR_DEVELOPMENT -> "Developer Preview";
            case Build.VERSION_CODES.BASE -> "Android 1.0";
            case Build.VERSION_CODES.BASE_1_1 -> "Android 1.1";
            case Build.VERSION_CODES.CUPCAKE -> "Android 1.5";
            case Build.VERSION_CODES.DONUT -> "Android 1.6";
            case Build.VERSION_CODES.ECLAIR -> "Android 2.0";
            case Build.VERSION_CODES.ECLAIR_0_1 -> "Android 2.0.1";
            case Build.VERSION_CODES.ECLAIR_MR1 -> "Android 2.1";
            case Build.VERSION_CODES.FROYO -> "Android 2.2";
            case Build.VERSION_CODES.GINGERBREAD -> "Android 2.3";
            case Build.VERSION_CODES.GINGERBREAD_MR1 -> "Android 2.3.3";
            case Build.VERSION_CODES.HONEYCOMB -> "Android 3.0";
            case Build.VERSION_CODES.HONEYCOMB_MR1 -> "Android 3.1";
            case Build.VERSION_CODES.HONEYCOMB_MR2 -> "Android 3.2";
            case Build.VERSION_CODES.ICE_CREAM_SANDWICH -> "Android 4.0";
            case Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1 -> "Android 4.0.3";
            case Build.VERSION_CODES.JELLY_BEAN -> "Android 4.1";
            case Build.VERSION_CODES.JELLY_BEAN_MR1 -> "Android 4.2";
            case Build.VERSION_CODES.JELLY_BEAN_MR2 -> "Android 4.3";
            case Build.VERSION_CODES.KITKAT -> "Android 4.4";
            case Build.VERSION_CODES.KITKAT_WATCH -> "Android 4.4W";
            case Build.VERSION_CODES.LOLLIPOP -> "Android 5.0";
            case Build.VERSION_CODES.LOLLIPOP_MR1 -> "Android 5.1";
            case Build.VERSION_CODES.M -> "Android 6.0";
            case Build.VERSION_CODES.N -> "Android 7.0";
            case Build.VERSION_CODES.N_MR1 -> "Android 7.1";
            case Build.VERSION_CODES.O -> "Android 8.0";
            case Build.VERSION_CODES.O_MR1 -> "Android 8.1";
            case Build.VERSION_CODES.P -> "Android 9";
            case Build.VERSION_CODES.Q -> "Android 10";
            case Build.VERSION_CODES.R -> "Android 11";
            case Build.VERSION_CODES.S -> "Android 12";
            case Build.VERSION_CODES.S_V2 -> "Android 12L";
            case Build.VERSION_CODES.TIRAMISU -> "Android 13";
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "Android 14";
            case Build.VERSION_CODES.VANILLA_ICE_CREAM -> "Android 15";
            case Build.VERSION_CODES.BAKLAVA -> "Android 16";
            default -> "Unknown (" + apiLevel + ")";
        };
    }
}
