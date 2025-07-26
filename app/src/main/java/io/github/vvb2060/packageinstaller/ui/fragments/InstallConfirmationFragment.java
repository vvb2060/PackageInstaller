package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.model.InstallUserAction;
import io.github.vvb2060.packageinstaller.viewmodel.InstallViewModel;

public class InstallConfirmationFragment extends DialogFragment {

    private final InstallUserAction mDialogData;
    private InstallViewModel mViewModel;
    private InstallViewModel mViewModel2;
    private AlertDialog mDialog;
    private CheckBox mCheckBox;

    public InstallConfirmationFragment(InstallUserAction dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(requireActivity())
            .get(InstallViewModel.class);
        mViewModel2 = new ViewModelProvider(requireActivity())
            .get(InstallViewModel.class);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var context = requireContext();
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        dialogView.requireViewById(R.id.install_confirm).setVisibility(View.VISIBLE);
        TextView textView = dialogView.requireViewById(R.id.message);
        mCheckBox = dialogView.requireViewById(R.id.set_installer);
        if (mDialogData.getSkipCreate()) {
            mCheckBox.setVisibility(View.GONE);
        }

        var sb = new SpannableStringBuilder();
        getInfo(sb);
        var old = mDialogData.getOldApk();
        int question = R.string.install_confirm_question;
        if (old != null) {
            var flags = old.applicationInfo.flags;
            var system = (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            var installed = (flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            mCheckBox.setChecked(system);
            if (installed) {
                question = R.string.install_confirm_question_update;
            }
        }
        var full = mDialogData.getFullInstall();
        if (!full) {
            question = R.string.install_confirm_question_split;
        }
        sb.append(context.getString(question), new StyleSpan(Typeface.ITALIC),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        textView.setText(sb);

        mDialog = new AlertDialog.Builder(context)
            .setIcon(mDialogData.getApkLite().getIcon())
            .setTitle(mDialogData.getApkLite().getLabel())
            .setView(dialogView)
            .setPositiveButton(old != null ? R.string.update : R.string.install,
                (dialogInt, which) -> {
                    mViewModel.initiateInstall(mCheckBox.isChecked(), true, full);
                })
            .setNegativeButton(android.R.string.cancel,
                (dialogInt, which) -> {
                    cleanAndFinish();
                })
            .setNeutralButton(R.string.add_more,
                (dialogInt, which) -> {
                    mViewModel.initiateInstall(mCheckBox.isChecked(), false, full);
                })
            .create();
        return mDialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        cleanAndFinish();
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

    private void cleanAndFinish() {
        mViewModel.cleanupInstall();
        requireActivity().finish();
    }

    private void getInfo(SpannableStringBuilder sb) {
        var apk = mDialogData.getApkLite();
        var old = mDialogData.getOldApk();
        append(sb, R.string.package_name, null, apk.getPackageName());
        append(sb, R.string.split_name, null, apk.getSplitName());
        append(sb, R.string.split_types, null, apk.getSplitTypes());
        append(sb, R.string.required_split_types, null, apk.getRequiredSplitTypes());
        if (apk.isSplit()) return;
        var ver = apk.getVersionName() + " (" + apk.getVersionCode() + ")";
        var min = getAndroidName(apk.getMinSdkVersion());
        var target = getAndroidName(apk.getTargetSdkVersion());
        String oldVer = null;
        String oldMin = null;
        String oldTarget = null;
        if (old != null) {
            oldVer = old.versionName + " (" + old.getLongVersionCode() + ")";
            oldMin = getAndroidName(old.applicationInfo.minSdkVersion);
            oldTarget = getAndroidName(old.applicationInfo.targetSdkVersion);
        }
        append(sb, R.string.version, oldVer, ver);
        append(sb, R.string.min_sdk, oldMin, min);
        append(sb, R.string.target_sdk, oldTarget, target);
    }

    private void append(SpannableStringBuilder sb, int label, String old, String now) {
        var context = requireContext();
        if (old == null || old.equals(now)) {
            if (now != null && !now.isEmpty()) {
                sb.append(context.getString(label));
                sb.append(now).append("\n");
            }
        } else {
            sb.append(context.getString(label));
            sb.append(old);
            sb.append(" → ");
            sb.append(now, new StyleSpan(Typeface.BOLD),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n");
        }
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
