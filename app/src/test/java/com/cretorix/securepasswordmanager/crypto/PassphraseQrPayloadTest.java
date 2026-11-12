package com.cretorix.securepasswordmanager.crypto;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;

public class PassphraseQrPayloadTest {
    @Test
    public void roundTripsEightCharacterPassphrase() throws Exception {
        char[] passphrase = "Abcd123!".toCharArray();
        PassphraseQrPayload encoded = null;
        PassphraseQrPayload decoded = null;
        try {
            encoded = new PassphraseQrPayload(
                    new byte[VaultHeaderV3.VAULT_ID_SIZE], passphrase);
            decoded = PassphraseQrPayload.fromQrString(encoded.toQrString());
            assertArrayEquals(passphrase, decoded.passphrase);
        } finally {
            Arrays.fill(passphrase, '\0');
            if (encoded != null) {
                Arrays.fill(encoded.passphrase, '\0');
                Crypto.wipe(encoded.vaultId);
                Crypto.wipe(encoded.checksum);
            }
            if (decoded != null) {
                Arrays.fill(decoded.passphrase, '\0');
                Crypto.wipe(decoded.vaultId);
                Crypto.wipe(decoded.checksum);
            }
        }
    }
}
