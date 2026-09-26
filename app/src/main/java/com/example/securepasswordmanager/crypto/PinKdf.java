package com.example.securepasswordmanager.crypto;

import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Derives a 256-bit wrapping key from the user passphrase.
 *
 * <p>VLT2 uses Argon2id from Bouncy Castle. The legacy PBKDF2 branch exists only to
 * recognize old VLT1 data; it is never selected when creating a VLT2 vault.</p>
 */
public final class PinKdf {
    private PinKdf() {
    }

    public static byte[] derive(char[] pin, KdfParameters parameters) throws GeneralSecurityException {
        // VLT2 uses Argon2id because a copied vault must resist GPU-assisted guessing.
        // The PBKDF2 branch is read-only compatibility for explicitly identified VLT1 data.
        if (pin == null || pin.length < (KdfParameters.LEGACY_ALGORITHM.equals(parameters.algorithm)
                ? 6 : 12) || pin.length > (KdfParameters.LEGACY_ALGORITHM.equals(parameters.algorithm)
                ? 64 : 24)) {
            throw new IllegalArgumentException("Passphrase must contain 12 to 24 characters");
        }
        if (KdfParameters.LEGACY_ALGORITHM.equals(parameters.algorithm)) {
            PBEKeySpec spec = new PBEKeySpec(pin, parameters.salt, parameters.iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
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
