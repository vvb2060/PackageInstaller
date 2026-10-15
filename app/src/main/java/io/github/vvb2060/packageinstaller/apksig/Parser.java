package io.github.vvb2060.packageinstaller.apksig;

import static io.github.vvb2060.packageinstaller.apksig.Utils.findApkSigningBlock;
import static io.github.vvb2060.packageinstaller.apksig.Utils.getByteBuffer;
import static io.github.vvb2060.packageinstaller.apksig.Utils.getLengthPrefixedSlice;
import static io.github.vvb2060.packageinstaller.apksig.Utils.getSignatureAlgorithmContentDigestAlgorithm;
import static io.github.vvb2060.packageinstaller.apksig.Utils.getSignatureAlgorithmJcaSignatureAlgorithm;
import static io.github.vvb2060.packageinstaller.apksig.Utils.isCertificatePqc;
import static io.github.vvb2060.packageinstaller.apksig.Utils.isSupportedSignatureAlgorithm;
import static io.github.vvb2060.packageinstaller.apksig.Utils.readLengthPrefixedByteArray;

import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.github.vvb2060.packageinstaller.datamodel.DataSource;

public class Parser {
    private static final String TAG = Parser.class.getSimpleName();

    public static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;
    public static final int APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xf05368c0;
    public static final int APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;
    public static final int APK_SIGNATURE_SCHEME_V32_BLOCK_ID = 0x70e1c89f;
    public static final int V1_SOURCE_STAMP_BLOCK_ID = 0x2b09189e;
    public static final int V2_SOURCE_STAMP_BLOCK_ID = 0x6dff800d;
    public static final int VERITY_PADDING_BLOCK_ID = 0x42726577;
    public static final int AGP_DEPENDENCIES_INFO_BLOCK_ID = 0x504b4453;
    public static final int GOOGLE_PLAY_FROSTING_BLOCK_ID = 0x2146444e;
    public static final int GOOGLE_PLAY_PAIRIP_BLOCK_ID = 0xfa1cfabe;
    public static final int TENCENT_VASDOLLY_BLOCK_ID = 0x881155ff;
    public static final int MEITUAN_WALLE_BLOCK_ID = 0x71777777;

    private static final int STRIPPING_PROTECTION_ATTR_ID = 0xbeeff00d;
    private static final int V3_PROOF_OF_ROTATION_ATTR_ID = 0x3ba06f8c;
    private static final int ROTATION_MIN_SDK_VERSION_ATTR_ID = 0x559f8b02;
    private static final int SIGNER_TARGETS_DEV_RELEASE_ATTR_ID = 0xc2a6b3ba;
    private static final int HYBRID_MIN_SDK_VERSION_ATTR_ID = 0xbf940529;
    private static final int HYBRID_MAX_SDK_VERSION_ATTR_ID = 0x9f06b79c;
    private static final int STAMP_PROOF_OF_ROTATION_ATTR_ID = 0x9d6303f7;
    private static final int STAMP_TIME_ATTR_ID = 0xe43c5946;

    public static Map<Integer, Object> perse(DataSource apk) throws IOException {
        var pairs = findApkSigningBlock(apk);

        var data = new ArrayMap<Integer, Object>();
        ByteBuffer stampBuffer = null;

        while (pairs.remaining() >= 12) {
            var lenLong = pairs.getLong();
            if ((lenLong < 4) || (lenLong > pairs.remaining())) {
                break;
            }
            int len = (int) lenLong;
            int blockId = pairs.getInt();
            var block = getByteBuffer(pairs, len - 4);
            switch (blockId) {
                case APK_SIGNATURE_SCHEME_V2_BLOCK_ID:
                case APK_SIGNATURE_SCHEME_V3_BLOCK_ID:
                case APK_SIGNATURE_SCHEME_V31_BLOCK_ID:
                case APK_SIGNATURE_SCHEME_V32_BLOCK_ID:
                    try {
                        data.putIfAbsent(blockId, perseSignatureScheme(blockId, block));
                    } catch (IOException | RuntimeException e) {
                        Log.e(TAG, "Failed to parse signature scheme block id 0x"
                            + Integer.toHexString(blockId), e);
                        data.putIfAbsent(blockId, e.toString());
                    }
                    break;
                case V2_SOURCE_STAMP_BLOCK_ID:
                    if (stampBuffer == null) stampBuffer = block;
                    break;
                case V1_SOURCE_STAMP_BLOCK_ID:
                    data.putIfAbsent(blockId, "V1 Source Stamp: " +
                        block.remaining() + " bytes");
                    break;
                case GOOGLE_PLAY_PAIRIP_BLOCK_ID:
                    data.putIfAbsent(blockId, "Google Play Pairip: " +
                        block.remaining() + " bytes");
                    break;
                case GOOGLE_PLAY_FROSTING_BLOCK_ID:
                    data.putIfAbsent(blockId, "Google Play Frosting: " +
                        block.remaining() + " bytes");
                    break;
                case AGP_DEPENDENCIES_INFO_BLOCK_ID:
                    data.putIfAbsent(blockId, "AGP Dependencies Info: " +
                        block.remaining() + " bytes");
                    break;
                case VERITY_PADDING_BLOCK_ID:
                    break;
                case TENCENT_VASDOLLY_BLOCK_ID:
                    data.putIfAbsent(blockId, "Tencent VasDolly: " +
                        new String(block.array(), block.arrayOffset() + block.position(),
                            block.remaining()));
                    break;
                case MEITUAN_WALLE_BLOCK_ID:
                    data.putIfAbsent(blockId, "Meituan Walle: " +
                        new String(block.array(), block.arrayOffset() + block.position(),
                            block.remaining()));
                    break;
                default:
                    var sb = new StringBuilder("Unknown id 0x");
                    sb.append(Integer.toHexString(blockId));
                    sb.append(": ");
                    var decoder = StandardCharsets.UTF_8.newDecoder();
                    decoder.onMalformedInput(CodingErrorAction.REPORT);
                    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
                    try {
                        var charBuffer = decoder.decode(block);
                        sb.append(charBuffer.toString());
                    } catch (CharacterCodingException e) {
                        sb.append(block.remaining()).append(" bytes");
                    }
                    data.putIfAbsent(blockId, sb.toString());
                    break;
            }
        }

        try {
            if (stampBuffer != null) {
                var signer = verifySourceStamp(stampBuffer, data);
                data.putIfAbsent(V2_SOURCE_STAMP_BLOCK_ID, signer);
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to verify source stamp", e);
            data.putIfAbsent(V2_SOURCE_STAMP_BLOCK_ID, e.toString());
        }

        return data;
    }

    private static Signer verifySourceStamp(ByteBuffer sourceStampBlock,
                                            ArrayMap<Integer, Object> data) throws IOException {
        var signer = new Signer();
        var sourceStampBlockData = getLengthPrefixedSlice(sourceStampBlock);
        var sourceStampEncodedCertificate = readLengthPrefixedByteArray(sourceStampBlockData);
        var cert = new Cert(sourceStampEncodedCertificate);
        signer.certs.add(cert);
        var signedSignatureSchemes = getLengthPrefixedSlice(sourceStampBlockData);
        var signedSignatureSchemeData = new ArrayMap<Integer, ByteBuffer>();
        while (signedSignatureSchemes.hasRemaining()) {
            var signedSignatureScheme = getLengthPrefixedSlice(signedSignatureSchemes);
            int signatureSchemeId = signedSignatureScheme.getInt();
            var signatures = getLengthPrefixedSlice(signedSignatureScheme);
            signedSignatureSchemeData.put(signatureSchemeId, signatures);
        }

        var size = data.size();
        for (int i = 0; i < size; i++) {
            int blockId = data.keyAt(i);
            int id;
            switch (blockId) {
                case APK_SIGNATURE_SCHEME_V2_BLOCK_ID -> id = 2;
                case APK_SIGNATURE_SCHEME_V3_BLOCK_ID -> id = 3;
                default -> {
                    continue;
                }
            }
            var block = (Block) data.valueAt(i);
            var digests = new ArrayList<Pair<Integer, byte[]>>();
            block.contentDigests.forEach((k, v) -> digests.add(Pair.create(k, v)));
            digests.sort(Comparator.comparing(pair -> pair.first));
            var encodedApkContentDigests = encodeApkContentDigests(digests);

            var signatures = signedSignatureSchemeData.get(id);
            if (signatures == null) {
                throw new SecurityException("No signatures found for signature scheme " + id);
            }
            verifySourceStampSignature(encodedApkContentDigests, cert.certificate, signatures);
        }

        if (sourceStampBlockData.hasRemaining()) {
            var stampAttributeData = getLengthPrefixedSlice(sourceStampBlockData);
            var signatures = getLengthPrefixedSlice(sourceStampBlockData);
            verifySourceStampSignature(stampAttributeData, cert.certificate, signatures);
            stampAttributeData.flip();
            var additionalAttrs = getLengthPrefixedSlice(stampAttributeData);
            perseAdditionalAttributes(additionalAttrs, signer);
        }
        return signer;
    }

    private static void verifySourceStampSignature(ByteBuffer data,
                                                   X509Certificate sourceStampCertificate,
                                                   ByteBuffer signatures) throws IOException {
        int sigAlgorithm = -1;
        byte[] sigAlgorithmSignatureBytes = null;
        while (signatures.hasRemaining()) {
            var signature = getLengthPrefixedSlice(signatures);
            sigAlgorithm = signature.getInt();
            if (!isSupportedSignatureAlgorithm(sigAlgorithm)) {
                continue;
            }
            sigAlgorithmSignatureBytes = readLengthPrefixedByteArray(signature);
            break;
        }
        if (sigAlgorithmSignatureBytes == null) {
            throw new SecurityException("No supported signatures found");
        }
        var signatureAlgorithmParams = getSignatureAlgorithmJcaSignatureAlgorithm(sigAlgorithm);
        String jcaSignatureAlgorithm = signatureAlgorithmParams.first;
        AlgorithmParameterSpec jcaSignatureAlgorithmParams = signatureAlgorithmParams.second;
        var publicKey = sourceStampCertificate.getPublicKey();
        boolean sigVerified;
        try {
            var sig = Signature.getInstance(jcaSignatureAlgorithm);
            sig.initVerify(publicKey);
            if (jcaSignatureAlgorithmParams != null) {
                sig.setParameter(jcaSignatureAlgorithmParams);
            }
            sig.update(data);
            sigVerified = sig.verify(sigAlgorithmSignatureBytes);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Failed to verify signature", e);
        }
        if (!sigVerified) {
            throw new SecurityException("Failed to verify signature");
        }
    }

    private static ByteBuffer encodeApkContentDigests(List<Pair<Integer, byte[]>> contentDigests) {
        int resultSize = 0;

        var size = contentDigests.size();
        for (int i = 0; i < size; i++) {
            var element = contentDigests.get(i);
            resultSize += 12 + element.second.length;
        }

        var result = ByteBuffer.allocate(resultSize);
        result.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < size; i++) {
            var element = contentDigests.get(i);
            var second = element.second;
            result.putInt(8 + second.length);
            result.putInt(element.first);
            result.putInt(second.length);
            result.put(second);
        }

        result.flip();
        return result;
    }

    private static Block perseSignatureScheme(int blockId, ByteBuffer signatureBlock)
        throws IOException {
        var block = new Block(blockId);
        var signers = getLengthPrefixedSlice(signatureBlock);
        while (signers.hasRemaining()) {
            var signer = getLengthPrefixedSlice(signers);
            perseSigner(block, signer);
        }

        if (blockId == APK_SIGNATURE_SCHEME_V32_BLOCK_ID && block.signers.size() == 2) {
            var pqc = block.signers.get(0);
            var classical = block.signers.get(1);
            if (isCertificatePqc(classical.certs.get(0).certificate)) {
                pqc = classical;
                classical = block.signers.get(0);
            }
            pqc.certs.add(1, classical.certs.get(0));
            block.signers.clear();
            block.signers.add(pqc);
        }
        return block;
    }

    private static void perseSigner(Block block, ByteBuffer signerBlock) throws IOException {
        var signer = new Signer();
        block.signers.add(signer);

        var signedData = getLengthPrefixedSlice(signerBlock);
        var digests = getLengthPrefixedSlice(signedData);
        while (digests.hasRemaining()) {
            var digest = getLengthPrefixedSlice(digests);
            int sigAlgorithm = digest.getInt();
            if (!isSupportedSignatureAlgorithm(sigAlgorithm)) continue;
            var contentDigest = readLengthPrefixedByteArray(digest);
            int digestAlgorithm = getSignatureAlgorithmContentDigestAlgorithm(sigAlgorithm);
            block.contentDigests.put(digestAlgorithm, contentDigest);
        }

        var certificates = getLengthPrefixedSlice(signedData);
        var encodedCert = readLengthPrefixedByteArray(certificates);
        signer.certs.add(new Cert(encodedCert));

        if (block.id != APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
            signer.minSdkVersion = signedData.getInt();
            signer.maxSdkVersion = signedData.getInt();
        }

        var additionalAttrs = getLengthPrefixedSlice(signedData);
        perseAdditionalAttributes(additionalAttrs, signer);
    }

    private static void perseAdditionalAttributes(ByteBuffer attrs, Signer signer)
        throws IOException {
        while (attrs.hasRemaining()) {
            var attr = getLengthPrefixedSlice(attrs);
            int id = attr.getInt();
            switch (id) {
                case V3_PROOF_OF_ROTATION_ATTR_ID:
                case STAMP_PROOF_OF_ROTATION_ATTR_ID:
                    signer.certs = perseProofOfRotationStruct(attr);
                    break;
                case SIGNER_TARGETS_DEV_RELEASE_ATTR_ID:
                    signer.isDevTarget = true;
                    break;
                case STAMP_TIME_ATTR_ID:
                    signer.stampTime = Instant.ofEpochSecond(attr.getLong());
                    break;
                case STRIPPING_PROTECTION_ATTR_ID:
                    signer.StrippingProtection = attr.getInt();
                    break;
                case ROTATION_MIN_SDK_VERSION_ATTR_ID:
                    signer.RotationMinSdkVersion = attr.getInt();
                    break;
                case HYBRID_MIN_SDK_VERSION_ATTR_ID:
                    signer.HybridMinSdkVersion = attr.getInt();
                    break;
                case HYBRID_MAX_SDK_VERSION_ATTR_ID:
                    signer.HybridMaxSdkVersion = attr.getInt();
                    break;
                default:
                    signer.hasUnknownAttributes = true;
                    Log.w(TAG, "Unknown attribute id 0x" + Integer.toHexString(id) +
                        " size " + attr.remaining());
                    break;
            }
        }
    }

    private static List<Cert> perseProofOfRotationStruct(ByteBuffer porBuf) throws IOException {
        var certs = new ArrayList<Cert>();
        porBuf.getInt();
        while (porBuf.hasRemaining()) {
            var level = getLengthPrefixedSlice(porBuf);
            var signedData = getLengthPrefixedSlice(level);
            var encodedCert = readLengthPrefixedByteArray(signedData);
            int flags = level.getInt();
            var cert = new Cert(encodedCert, flags);
            certs.add(cert);
        }
        Collections.reverse(certs);
        return certs;
    }
}
