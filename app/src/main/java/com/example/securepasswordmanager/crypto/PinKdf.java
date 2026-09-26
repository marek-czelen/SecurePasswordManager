package com.example.securepasswordmanager.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PinKdf {
    private PinKdf() {
    }

    public static byte[] derive(char[] pin, KdfParameters parameters) throws GeneralSecurityException {
        if (pin == null || pin.length < 6 || pin.length > 64) {
            throw new IllegalArgumentException("PIN must contain 6 to 64 characters");
        }
        PBEKeySpec spec = new PBEKeySpec(pin, parameters.salt, parameters.iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
