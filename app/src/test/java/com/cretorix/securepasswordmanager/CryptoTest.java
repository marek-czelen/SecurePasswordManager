package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.KdfParameters;
import com.cretorix.securepasswordmanager.crypto.PassphraseQrPayload;
import com.cretorix.securepasswordmanager.crypto.PinKdf;
import com.cretorix.securepasswordmanager.crypto.RecoveryQrPayload;
import com.cretorix.securepasswordmanager.crypto.Totp;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public final class CryptoTest {
    @Test
    public void aesGcmAuthenticatesCiphertextAndAssociatedData() throws Exception {
        byte[] key = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] plaintext = Crypto.utf8("secret");
        byte[] aad = Crypto.utf8("context");
        Crypto.SealedBox box = Crypto.seal(key, plaintext, aad);
        assertArrayEquals(plaintext, Crypto.open(key, box, aad));
        assertThrows(Exception.class, () -> Crypto.open(key, box, Crypto.utf8("other")));
        box.ciphertext[0] ^= 1;
        assertThrows(Exception.class, () -> Crypto.open(key, box, aad));
    }

    @Test
    public void argon2idAndHkdfAreDeterministicAndDomainSeparated() throws Exception {
        KdfParameters parameters = new KdfParameters(new byte[16], 8 * 1024, 1, 1);
        byte[] first = PinKdf.derive("correct horse".toCharArray(), parameters);
        byte[] second = PinKdf.derive("correct horse".toCharArray(), parameters);
        assertArrayEquals(first, second);
        assertNotEquals(new String(first), new String(
                PinKdf.derive("correct pony".toCharArray(), parameters)));
        assertNotEquals(new String(Crypto.hkdf(first, new byte[16],
                        Crypto.utf8("manifest"), Crypto.KEY_SIZE)),
                new String(Crypto.hkdf(first, new byte[16],
                        Crypto.utf8("object"), Crypto.KEY_SIZE)));
    }

    @Test
    public void recoveryAndPassphraseQrRoundTripWithoutContainingDek() throws Exception {
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] qrSecret = Crypto.randomBytes(Crypto.KEY_SIZE);
        KdfParameters parameters = new KdfParameters(new byte[16], 8 * 1024, 1, 1);
        byte[] wrapSalt = Crypto.randomBytes(16);
        RecoveryQrPayload recovery = new RecoveryQrPayload(vaultId, qrSecret,
                parameters, wrapSalt);
        RecoveryQrPayload decoded = RecoveryQrPayload.fromQrString(recovery.toQrString());
        assertArrayEquals(vaultId, decoded.vaultId);
        assertArrayEquals(qrSecret, decoded.qrSecret);
        assertEquals(32, decoded.qrSecret.length);

        PassphraseQrPayload passphrase =
                new PassphraseQrPayload(vaultId, "correct horse".toCharArray());
        PassphraseQrPayload decodedPassphrase =
                PassphraseQrPayload.fromQrString(passphrase.toQrString());
        assertArrayEquals(vaultId, decodedPassphrase.vaultId);
        assertArrayEquals("correct horse".toCharArray(), decodedPassphrase.passphrase);

        String qrText = recovery.toQrString();
        int tamperIndex = qrText.length() / 2;
        char replacement = qrText.charAt(tamperIndex) == 'A' ? 'B' : 'A';
        String tampered = qrText.substring(0, tamperIndex) + replacement
                + qrText.substring(tamperIndex + 1);
        assertThrows(Exception.class, () -> RecoveryQrPayload.fromQrString(tampered));
    }

    @Test
    public void passphraseQrRejectsSecretsOutsideVaultPolicy() {
        byte[] vaultId = Crypto.randomBytes(16);
        assertThrows(IllegalArgumentException.class,
                () -> new PassphraseQrPayload(vaultId, "123456789012".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> new PassphraseQrPayload(vaultId, "short".toCharArray()));
    }

    @Test
    public void totpMatchesRfc6238Sha1Vector() throws Exception {
        assertEquals("287082", Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59_000L));
    }
}
