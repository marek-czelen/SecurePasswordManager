package com.example.securepasswordmanager.crypto;

import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Derives a 256-bit wrapping key from the user passphrase.
 *
 * <p>VLT3 uses Argon2id from Bouncy Castle for passphrase-based DEK wrapping.</p>
 */
public final class PinKdf {
    private PinKdf() {
    }

    public static byte[] derive(char[] pin, KdfParameters parameters) throws GeneralSecurityException {
        if (pin == null || pin.length < 12 || pin.length > 24 || parameters == null
                || !KdfParameters.ALGORITHM.equals(parameters.algorithm)) {
            throw new IllegalArgumentException("Passphrase must contain 12 to 24 characters");
        }
        byte[] password = new String(pin).getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[Crypto.KEY_SIZE];
        Argon2Parameters argon = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(parameters.salt)
                .withMemoryAsKB(parameters.memoryKiB)
                .withIterations(parameters.timeCost)
                .withParallelism(parameters.parallelism)
                .build();
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(argon);
            generator.generateBytes(password, result);
            return result;
        } finally {
            Crypto.wipe(password);
        }
    }
}
