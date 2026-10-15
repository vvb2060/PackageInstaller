package io.github.vvb2060.packageinstaller.apksig;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

public class Cert {
    public static class CertCapabilities {
        public static final int INSTALLED_DATA = 1;
        public static final int SHARED_UID = 1 << 1;
        public static final int PERMISSION = 1 << 2;
        public static final int ROLLBACK = 1 << 3;
        public static final int AUTH = 1 << 4;
        public static final int DEFAULT = INSTALLED_DATA | SHARED_UID | PERMISSION | AUTH;
    }

    private static final byte[] GOOGLE = new byte[]{
        50, 87, -43, -103, -92, -99, 44, -106,
        26, 71, 28, -87, -124, 63, 89, -45,
        65, -92, 5, -120, 69, -125, -4, 8,
        125, -12, 35, 123, 115, 59, -67, 109
    };

    private static final byte[] AOSP = new byte[]{
        -92, 13, -88, 10, 89, -47, 112, -54,
        -87, 80, -49, 21, -63, -116, 69, 77,
        71, -93, -101, 38, -104, -99, -117, 100,
        14, -51, 116, 91, -89, 27, -11, -36
    };

    private static final CertificateFactory certFactory;
    private static final MessageDigest sha256;

    static {
        try {
            certFactory = CertificateFactory.getInstance("X.509");
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (CertificateException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public final byte[] digest;
    public final int flags;
    public final X509Certificate certificate;

    Cert(byte[] bytes) {
        this(bytes, CertCapabilities.DEFAULT);
    }

    Cert(byte[] data, int flags) {
        this.flags = flags;
        digest = sha256.digest(data);
        try {
            var stream = new ByteArrayInputStream(data);
            certificate = (X509Certificate) certFactory.generateCertificate(stream);
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Cert cert) {
            return Arrays.equals(digest, cert.digest);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(digest);
    }

    public boolean isGoogle() {
        return Arrays.equals(digest, GOOGLE);
    }

    public void appendCertInfo(SpannableStringBuilder sb) {
        var name = certificate.getSubjectX500Principal().getName();
        var index1 = sb.length();
        sb.append(name).append("\n");
        var index2 = sb.length();
        sb.setSpan(new TypefaceSpan("serif"), index1, index2,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        sb.append("\n");
        var index3 = sb.length();
        sb.setSpan(new TypefaceSpan("monospace"), index2, index3,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (Arrays.equals(digest, AOSP)) {
            sb.setSpan(new ForegroundColorSpan(Color.RED), index2, index3,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (flags != CertCapabilities.DEFAULT) {
            if ((flags & CertCapabilities.INSTALLED_DATA) != 0) {
                sb.append("INSTALLED_DATA ");
            }
            if ((flags & CertCapabilities.SHARED_UID) != 0) {
                sb.append("SHARED_UID ");
            }
            if ((flags & CertCapabilities.PERMISSION) != 0) {
                sb.append("PERMISSION ");
            }
            if ((flags & CertCapabilities.ROLLBACK) != 0) {
                sb.append("ROLLBACK ");
            }
            if ((flags & CertCapabilities.AUTH) != 0) {
                sb.append("AUTH ");
            }
            var index4 = sb.length();
            if (index4 > index3) {
                sb.replace(index4 - 1, index4, "\n");
                sb.setSpan(new StyleSpan(Typeface.ITALIC), index3, index4,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
