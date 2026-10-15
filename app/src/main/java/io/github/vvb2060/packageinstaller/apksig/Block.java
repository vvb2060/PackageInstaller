package io.github.vvb2060.packageinstaller.apksig;

import android.os.Build;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Block {
    public final int id;
    public final List<Signer> signers = new ArrayList<>();
    public final Map<Integer, byte[]> contentDigests = new ArrayMap<>();

    Block(int id) {
        this.id = id;
    }

    public static boolean[] check(Map<Integer, Object> old, Map<Integer, Object> now) {
        var newBlock = findBlock(now);
        var oldBlock = findBlock(old);
        if (newBlock == null || oldBlock == null) {
            return null;
        }

        boolean[] isSame = {false, true}; // contentDigests, signers

        newBlock.contentDigests.forEach((k, v) -> {
            if (Arrays.equals(v, oldBlock.contentDigests.get(k))) {
                isSame[0] = true;
            }
        });

        var newSigners = findSigners(newBlock);
        var oldSigners = findSigners(oldBlock);
        var size = newSigners.size();
        if (size == 0 || size != oldSigners.size()) {
            isSame[1] = false;
            return isSame;
        }

        if (size == 1) {
            isSame[1] = false;
            var oldCerts = oldSigners.get(0).certs;
            var oldCert = oldCerts.get(0);
            var newcerts = newSigners.get(0).certs;
            for (int i = 0, certsSize = newcerts.size(); i < certsSize; i++) {
                var cert = newcerts.get(i);
                if (cert.equals(oldCert) &&
                    (cert.flags & Cert.CertCapabilities.INSTALLED_DATA) != 0) {
                    if (oldBlock.id == Parser.APK_SIGNATURE_SCHEME_V32_BLOCK_ID) {
                        isSame[1] = newcerts.get(i + 1).equals(oldCerts.get(1));
                    } else {
                        isSame[1] = true;
                    }
                    break;
                }
            }
            if (isSame[1]) {
                return isSame;
            }

            var newCert = newcerts.get(0);
            for (int i = 1, certListSize = oldCerts.size(); i < certListSize; i++) {
                oldCert = oldCerts.get(i);
                if ((oldCert.flags & Cert.CertCapabilities.ROLLBACK) == 0) continue;
                if (newCert.equals(oldCert) &&
                    (newCert.flags & Cert.CertCapabilities.INSTALLED_DATA) != 0) {
                    if (newBlock.id == Parser.APK_SIGNATURE_SCHEME_V32_BLOCK_ID) {
                        isSame[1] = oldCerts.get(i + 1).equals(newcerts.get(1));
                    } else {
                        isSame[1] = true;
                    }
                    break;
                }
            }
            return isSame;
        }

        var list = new ArrayList<Cert>(size);
        for (int i = 0; i < size; i++) {
            list.add(newSigners.get(i).certs.get(0));
        }
        for (int i = 0; i < size; i++) {
            if (!list.contains(oldSigners.get(i).certs.get(0))) {
                isSame[1] = false;
                break;
            }
        }
        return isSame;
    }

    public static Block findBlock(Map<Integer, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            map.get(Parser.APK_SIGNATURE_SCHEME_V32_BLOCK_ID) instanceof Block block) {
            return block;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            map.get(Parser.APK_SIGNATURE_SCHEME_V31_BLOCK_ID) instanceof Block block) {
            return block;
        } else if (map.get(Parser.APK_SIGNATURE_SCHEME_V3_BLOCK_ID) instanceof Block block) {
            return block;
        } else if (map.get(Parser.APK_SIGNATURE_SCHEME_V2_BLOCK_ID) instanceof Block block) {
            return block;
        } else {
            return null;
        }
    }

    private static List<Signer> findSigners(Block block) {
        if (block.id == Parser.APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
            return block.signers;
        }
        var signers = new ArrayList<Signer>();
        for (int i = 0, signerListSize = block.signers.size(); i < signerListSize; i++) {
            var signer = block.signers.get(i);
            var sdk = Build.VERSION.SDK_INT;
            if (sdk >= signer.minSdkVersion && sdk <= signer.maxSdkVersion) {
                if (signer.isDevTarget && sdk == signer.minSdkVersion) {
                    if (!"REL".equals(Build.VERSION.CODENAME)) {
                        signers.add(signer);
                        break;
                    }
                } else {
                    signers.add(signer);
                    break;
                }
            }
        }
        return signers;
    }
}
