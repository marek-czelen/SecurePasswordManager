package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.VaultManifest;
import com.cretorix.securepasswordmanager.crypto.VaultManifestCodec;
import com.cretorix.securepasswordmanager.crypto.VaultObjectRef;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class VaultManifestTest {
    @Test
    public void encryptedManifestRoundTripsAndAuthenticatesContext() throws Exception {
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        VaultManifest manifest = sampleManifest();

        byte[] encoded = VaultManifestCodec.seal(manifest, dek, vaultId);
        VaultManifest decoded = VaultManifestCodec.open(encoded, dek, vaultId);
        assertArrayEquals(manifest.generationId, decoded.generationId);
        assertArrayEquals(manifest.textBlock.objectId, decoded.textBlock.objectId);
        assertEquals(1, decoded.resources.size());
        assertEquals(VaultObjectRef.Type.IMAGE, decoded.resources.get(0).type);

        byte[] tampered = encoded.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(Exception.class, () -> VaultManifestCodec.open(tampered, dek, vaultId));
        assertThrows(Exception.class,
                () -> VaultManifestCodec.open(encoded, dek, Crypto.randomBytes(16)));
        assertThrows(Exception.class,
                () -> VaultManifestCodec.open(encoded, Crypto.randomBytes(32), vaultId));
    }

    @Test
    public void rejectsDuplicateIdsGenerationsAndInvalidReferences() {
        VaultObjectRef text = textRef(Crypto.randomBytes(32), Crypto.randomBytes(32));
        byte[] assetId = Crypto.randomBytes(32);
        byte[] assetGeneration = Crypto.randomBytes(32);
        VaultObjectRef asset = imageRef(assetId, assetGeneration);

        assertThrows(IllegalArgumentException.class, () -> new VaultManifest(
                Crypto.randomBytes(32), text, List.of(asset, asset)));
        assertThrows(IllegalArgumentException.class, () -> new VaultManifest(
                Crypto.randomBytes(32), text, Collections.singletonList(
                imageRef(Crypto.randomBytes(32), text.generationId))));
        assertThrows(IllegalArgumentException.class, () -> new VaultObjectRef(
                Crypto.randomBytes(32), Crypto.randomBytes(32),
                VaultObjectRef.Type.IMAGE, null, 1, 17, new byte[32], 1, "image/jpeg"));
    }

    @Test
    public void enforcesActiveDataAndManifestLimits() {
        VaultObjectRef text = textRef(Crypto.randomBytes(32), Crypto.randomBytes(32));
        List<VaultObjectRef> resources = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            resources.add(new VaultObjectRef(Crypto.randomBytes(32), Crypto.randomBytes(32),
                    VaultObjectRef.Type.FILE, Crypto.randomBytes(16),
                    10L * 1024 * 1024, 10L * 1024 * 1024 + 16,
                    Crypto.randomBytes(32), 1, "application/octet-stream"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new VaultManifest(Crypto.randomBytes(32), text, resources));

        assertThrows(IllegalArgumentException.class, () -> new VaultObjectRef(
                Crypto.randomBytes(32), Crypto.randomBytes(32),
                VaultObjectRef.Type.TEXT_BLOCK, null, 32L * 1024 * 1024 + 1,
                32L * 1024 * 1024 + 17, Crypto.randomBytes(32), 1, ""));
    }

    private static VaultManifest sampleManifest() {
        VaultObjectRef text = textRef(Crypto.randomBytes(32), Crypto.randomBytes(32));
        return new VaultManifest(Crypto.randomBytes(32), text,
                Collections.singletonList(imageRef(Crypto.randomBytes(32),
                        Crypto.randomBytes(32))));
    }

    private static VaultObjectRef textRef(byte[] id, byte[] generation) {
        return new VaultObjectRef(id, generation, VaultObjectRef.Type.TEXT_BLOCK,
                null, 128, 144, Crypto.randomBytes(32), 1, "");
    }

    private static VaultObjectRef imageRef(byte[] id, byte[] generation) {
        return new VaultObjectRef(id, generation, VaultObjectRef.Type.IMAGE,
                Crypto.randomBytes(16), 4096, 4112,
                Crypto.randomBytes(32), 1, "image/jpeg");
    }
}
