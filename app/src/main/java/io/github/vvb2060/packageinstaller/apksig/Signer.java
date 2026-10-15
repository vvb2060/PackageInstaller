package io.github.vvb2060.packageinstaller.apksig;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;

public class Signer {
    public static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault());

    public List<Cert> certs = new ArrayList<>();
    public int minSdkVersion;
    public int maxSdkVersion;
    public boolean isDevTarget;
    public Instant stampTime;
    public Integer StrippingProtection;
    public Integer RotationMinSdkVersion;
    public Integer HybridMinSdkVersion;
    public Integer HybridMaxSdkVersion;
    public boolean hasUnknownAttributes;

    public void appendSignerInfo(SpannableStringBuilder ssb, int index) {
        var sb = new StringBuilder();
        if (minSdkVersion != 0 && maxSdkVersion != 0) {
            sb.append("SDK ").append(minSdkVersion);
            if (isDevTarget) sb.append("(dev)");
            sb.append(" ~ ").append(maxSdkVersion);
            sb.append(":");
        } else if (index > 0) {
            sb.append("Signer #").append(index).append(":");
        } else {
            sb.append("Signer:");
        }
        sb.append("\n");
        ssb.append(sb, new StyleSpan(Typeface.BOLD),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);

        sb.setLength(0);
        if (stampTime != null) {
            sb.append("Stamp time: ").append(FORMATTER.format(stampTime)).append("\n");
        }
        if (StrippingProtection != null) {
            sb.append("Stripping protection: ").append(StrippingProtection).append("\n");
        }
        if (RotationMinSdkVersion != null) {
            sb.append("Rotation min sdk: ").append(RotationMinSdkVersion).append("\n");
        }
        if (HybridMinSdkVersion != null) {
            sb.append("Hybrid sdk: ").append(HybridMinSdkVersion);
            var max = HybridMaxSdkVersion != null ? HybridMaxSdkVersion : Integer.MAX_VALUE;
            sb.append(" ~ ").append(max).append("\n");
        }
        if (hasUnknownAttributes) {
            sb.append("Has unknown attributes\n");
        }
        ssb.append(sb);

        for (int i = 0, certsSize = certs.size(); i < certsSize; i++) {
            var cert = certs.get(i);
            if (i > 0) ssb.append("↑\n");
            cert.appendCertInfo(ssb);
        }
    }
}
