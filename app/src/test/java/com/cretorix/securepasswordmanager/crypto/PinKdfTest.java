package com.cretorix.securepasswordmanager.crypto;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class PinKdfTest {
    @Test
    public void derivesKeyFromEightCharacterPassphrase() throws Exception {
        char[] passphrase = "Abcd123!".toCharArray();
        byte[] key = null;
        try {
            KdfParameters parameters = new KdfParameters(new byte[KdfParameters.SALT_SIZE],
                    8 * 1024, 1, 1);
            key = PinKdf.derive(passphrase, parameters);
            assertEquals(Crypto.KEY_SIZE, key.length);
        } finally {
            Arrays.fill(passphrase, '\0');
            Crypto.wipe(key);
        }
    }
}
