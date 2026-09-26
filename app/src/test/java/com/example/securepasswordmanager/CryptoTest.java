package com.example.securepasswordmanager;

import com.example.securepasswordmanager.crypto.Crypto;
import com.example.securepasswordmanager.crypto.KdfParameters;
import com.example.securepasswordmanager.crypto.PinKdf;
import com.example.securepasswordmanager.crypto.RecoveryQrPayload;
import com.example.securepasswordmanager.crypto.Totp;
import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultDataCodec;
import com.example.securepasswordmanager.crypto.VaultEnvelope;
import com.example.securepasswordmanager.crypto.VaultItem;
import com.example.securepasswordmanager.crypto.VaultSlot;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public final class CryptoTest {
    @Test
    public void aesGcmAuthenticatesCiphertext() throws Exception {
        byte[] key = Crypto.randomBytes(32);
        Crypto.SealedBox box = Crypto.seal(key, "secret".getBytes(), "aad".getBytes());
        assertArrayEquals("secret".getBytes(), Crypto.open(key, box, "aad".getBytes()));
        box.ciphertext[0] ^= 1;
        assertThrows(Exception.class, () -> Crypto.open(key, box, "aad".getBytes()));
    }

    @Test
    public void pinKdfUsesSalt() throws Exception {
        KdfParameters parameters = new KdfParameters(Crypto.randomBytes(16), 100_000);
        byte[] first = PinKdf.derive("583914".toCharArray(), parameters);
        byte[] second = PinKdf.derive("694025".toCharArray(), parameters);
        assertNotEquals(new String(first), new String(second));
    }

    @Test
    public void payloadAndEnvelopeRoundTrip() throws Exception {
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] slotId = Crypto.randomBytes(16);
        byte[] key = Crypto.randomBytes(32);
        KdfParameters parameters = new KdfParameters(Crypto.randomBytes(16), 100_000);
        Crypto.SealedBox box = Crypto.seal(key, Crypto.randomBytes(32), "pin".getBytes());
        byte[] hardware = new byte[]{2, 9, 8, 7, 6};
        VaultSlot slot = new VaultSlot(slotId, Crypto.hmacSha256(key, slotId), box, hardware, box);
        VaultEnvelope envelope = new VaultEnvelope(vaultId, parameters,
                Collections.singletonList(slot));
        VaultEnvelope decoded = VaultEnvelope.decode(envelope.encode());
        assertArrayEquals(vaultId, decoded.vaultId);
        assertEquals(1, decoded.slots.size());
        RecoveryQrPayload payload = new RecoveryQrPayload(vaultId, slotId, parameters, box);
        assertArrayEquals(vaultId, RecoveryQrPayload.fromQrString(payload.toQrString()).vaultId);
    }

    @Test
    public void dataIsPaddedAndRoundTrips() throws Exception {
        VaultData data = new VaultData(Collections.singletonList(new VaultItem(
                "Google", "user", "password", "https://google.com", "notes", "email")));
        byte[] encoded = VaultDataCodec.encode(data, 8192);
        assertEquals(0, encoded.length % 4096);
        assertEquals("password", VaultDataCodec.decode(encoded).items.get(0).password);
    }

    @Test
    public void totpMatchesRfc6238Sha1Vector() throws Exception {
        assertEquals("287082", Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59_000L));
    }
}
