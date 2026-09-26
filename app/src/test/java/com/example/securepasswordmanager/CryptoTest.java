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
    public void argon2AndHkdfAreDeterministicAndDomainSeparated() throws Exception {
        KdfParameters parameters = new KdfParameters(new byte[16], 8 * 1024, 1, 1);
        byte[] first = PinKdf.derive("correct horse".toCharArray(), parameters);
        byte[] second = PinKdf.derive("correct horse".toCharArray(), parameters);
        assertArrayEquals(first, second);
        assertNotEquals(new String(first), new String(
                PinKdf.derive("correct pony".toCharArray(), parameters)));
        assertArrayEquals(Crypto.hkdf(first, new byte[16], Crypto.utf8("test"), 32),
                Crypto.hkdf(first, new byte[16], Crypto.utf8("test"), 32));
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
    public void vlt2HasNoPinVerifierAndRqr2HasOnlyRandomSecret() throws Exception {
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] qrSecret = Crypto.randomBytes(32);
        KdfParameters parameters = new KdfParameters(new byte[16], 8 * 1024, 1, 1);
        byte[] wrapSalt = Crypto.randomBytes(16);
        VaultSlot slot = new VaultSlot(Crypto.randomBytes(16),
                Crypto.seal(Crypto.randomBytes(32), Crypto.randomBytes(32), "wrap".getBytes()),
                new byte[]{2, 1, 2}, Crypto.seal(Crypto.randomBytes(32),
                Crypto.randomBytes(32), "data".getBytes()));
        VaultEnvelope envelope = new VaultEnvelope(vaultId, parameters, wrapSalt,
                Crypto.sha256(qrSecret), slot);
        VaultEnvelope decoded = VaultEnvelope.decode(envelope.encode());
        assertEquals(2, decoded.version);
        assertEquals(0, decoded.slots.get(0).pinVerifier.length);
        RecoveryQrPayload qr = new RecoveryQrPayload(vaultId, qrSecret, parameters, wrapSalt);
        assertArrayEquals(qrSecret, RecoveryQrPayload.fromQrString(qr.toQrString()).qrSecret);
        String qrText = qr.toQrString();
        int tamperIndex = qrText.length() / 2;
        char replacement = qrText.charAt(tamperIndex) == 'A' ? 'B' : 'A';
        String tampered = qrText.substring(0, tamperIndex) + replacement
                + qrText.substring(tamperIndex + 1);
        assertThrows(Exception.class, () -> RecoveryQrPayload.fromQrString(tampered));
    }

    @Test
    public void legacyTwoSlotEnvelopeCanBeReadForMigration() throws Exception {
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] key = Crypto.randomBytes(32);
        KdfParameters parameters = new KdfParameters(Crypto.randomBytes(16), 100_000);
        Crypto.SealedBox box = Crypto.seal(key, Crypto.randomBytes(32), "pin".getBytes());
        VaultSlot first = new VaultSlot(Crypto.randomBytes(16), Crypto.hmacSha256(key, new byte[16]),
                box, new byte[]{2, 1, 2}, box);
        VaultSlot second = new VaultSlot(Crypto.randomBytes(16), Crypto.hmacSha256(key, new byte[16]),
                box, new byte[]{2, 3, 4}, box);
        VaultEnvelope legacy = new VaultEnvelope(vaultId, parameters,
                java.util.Arrays.asList(first, second));
        assertEquals(2, VaultEnvelope.decode(legacy.encode()).slots.size());
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
