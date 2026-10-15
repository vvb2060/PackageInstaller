package io.github.vvb2060.packageinstaller.apksig;

import android.util.Pair;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

import io.github.vvb2060.packageinstaller.datamodel.DataSource;
import io.github.vvb2060.packageinstaller.zip.ZipUtils;

class Utils {
    private static final String OID_ML_DSA_65 = "2.16.840.1.101.3.4.3.18";
    private static final String OID_ML_DSA_87 = "2.16.840.1.101.3.4.3.19";

    private static final int SIGNATURE_RSA_PSS_WITH_SHA256 = 0x0101;
    private static final int SIGNATURE_RSA_PSS_WITH_SHA512 = 0x0102;
    private static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
    private static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512 = 0x0104;
    private static final int SIGNATURE_ECDSA_WITH_SHA256 = 0x0201;
    private static final int SIGNATURE_ECDSA_WITH_SHA512 = 0x0202;
    private static final int SIGNATURE_DSA_WITH_SHA256 = 0x0301;
    private static final int SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0421;
    private static final int SIGNATURE_VERITY_ECDSA_WITH_SHA256 = 0x0423;
    private static final int SIGNATURE_VERITY_DSA_WITH_SHA256 = 0x0425;
    private static final int SIGNATURE_ML_DSA = 0x0501;

    private static final int CONTENT_DIGEST_CHUNKED_SHA256 = 1;
    private static final int CONTENT_DIGEST_CHUNKED_SHA512 = 2;
    private static final int CONTENT_DIGEST_VERITY_CHUNKED_SHA256 = 3;

    static boolean isSupportedSignatureAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA512:
            case SIGNATURE_DSA_WITH_SHA256:
            case SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_VERITY_ECDSA_WITH_SHA256:
            case SIGNATURE_VERITY_DSA_WITH_SHA256:
            case SIGNATURE_ML_DSA:
                return true;
            default:
                return false;
        }
    }

    static int getSignatureAlgorithmContentDigestAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_DSA_WITH_SHA256:
                return CONTENT_DIGEST_CHUNKED_SHA256;
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_ECDSA_WITH_SHA512:
            case SIGNATURE_ML_DSA:
                return CONTENT_DIGEST_CHUNKED_SHA512;
            case SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_VERITY_ECDSA_WITH_SHA256:
            case SIGNATURE_VERITY_DSA_WITH_SHA256:
                return CONTENT_DIGEST_VERITY_CHUNKED_SHA256;
            default:
                throw new IllegalArgumentException(
                    "Unknown signature algorithm: 0x" + Long.toHexString(sigAlgorithm));
        }
    }

    static boolean isCertificatePqc(X509Certificate cert) {
        var publicKey = cert.getPublicKey();
        return switch (publicKey.getAlgorithm()) {
            case "ML-DSA", OID_ML_DSA_65, OID_ML_DSA_87 -> true;
            default -> false;
        };
    }

    static Pair<String, ? extends AlgorithmParameterSpec>
    getSignatureAlgorithmJcaSignatureAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
                return Pair.create(
                    "SHA256withRSA/PSS",
                    new PSSParameterSpec(
                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 256 / 8, 1));
            case SIGNATURE_RSA_PSS_WITH_SHA512:
                return Pair.create(
                    "SHA512withRSA/PSS",
                    new PSSParameterSpec(
                        "SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 512 / 8, 1));
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256:
                return Pair.create("SHA256withRSA", null);
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
                return Pair.create("SHA512withRSA", null);
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_VERITY_ECDSA_WITH_SHA256:
                return Pair.create("SHA256withECDSA", null);
            case SIGNATURE_ECDSA_WITH_SHA512:
                return Pair.create("SHA512withECDSA", null);
            case SIGNATURE_DSA_WITH_SHA256:
            case SIGNATURE_VERITY_DSA_WITH_SHA256:
                return Pair.create("SHA256withDSA", null);
            case SIGNATURE_ML_DSA:
                return Pair.create("ML-DSA", null);
            default:
                throw new IllegalArgumentException(
                    "Unknown signature algorithm: 0x" + Long.toHexString(sigAlgorithm));
        }
    }

    static ByteBuffer getByteBuffer(ByteBuffer source, int size) throws BufferUnderflowException {
        int originalLimit = source.limit();
        int position = source.position();
        int limit = position + size;
        if ((limit < position) || (limit > originalLimit)) {
            throw new BufferUnderflowException();
        }
        source.limit(limit);
        try {
            var result = source.slice();
            result.order(source.order());
            source.position(limit);
            return result;
        } finally {
            source.limit(originalLimit);
        }
    }

    static ByteBuffer getLengthPrefixedSlice(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 4) {
            throw new IOException("Remaining buffer too short");
        }
        int len = buf.getInt();
        if (len < 0) {
            throw new IOException("Negative length");
        } else if (len > buf.remaining()) {
            throw new IOException("Remaining buffer too short");
        }
        return getByteBuffer(buf, len);
    }

    static byte[] readLengthPrefixedByteArray(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 4) {
            throw new IOException("Remaining buffer too short");
        }
        int len = buf.getInt();
        if (len < 0) {
            throw new IOException("Negative length");
        } else if (len > buf.remaining()) {
            throw new IOException("Remaining buffer too short");
        }
        byte[] result = new byte[len];
        buf.get(result);
        return result;
    }

    private static final long APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L;
    private static final long APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L;

    static ByteBuffer findApkSigningBlock(DataSource apk) throws IOException {
        var eocd = ZipUtils.findZipEndOfCentralDirectoryRecord(apk);
        if (eocd == null) {
            throw new IOException("ZIP End of Central Directory record not found");
        }

        long centralDirOffset = ZipUtils.getZipEocdCentralDirectoryOffset(eocd.first);
        var footer = apk.getByteBuffer(centralDirOffset - 24, 24);
        footer.order(ByteOrder.LITTLE_ENDIAN);
        if ((footer.getLong(8) != APK_SIG_BLOCK_MAGIC_LO)
            || (footer.getLong(16) != APK_SIG_BLOCK_MAGIC_HI)) {
            throw new IOException("No APK Signing Block magic");
        }
        long apkSigBlockSize = footer.getLong(0);
        long apkSigBlockOffset = centralDirOffset - apkSigBlockSize;
        var pairs = apk.getByteBuffer(apkSigBlockOffset, (int) (apkSigBlockSize - 24));
        pairs.order(ByteOrder.LITTLE_ENDIAN);
        return pairs;
    }
}
