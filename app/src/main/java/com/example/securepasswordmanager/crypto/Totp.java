package com.example.securepasswordmanager.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP generated locally; the shared secret never leaves the vault. */
public final class Totp {
    private Totp() {
    }

    public static String code(String secret, long timeMillis) throws GeneralSecurityException {
        byte[] key = decodeBase32(secret);
        long counter = timeMillis / 30_000L;
        byte[] message = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(message);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(Locale.US, "%06d", binary % 1_000_000);
    }

    public static byte[] decodeBase32(String value) {
        String normalized = value == null ? "" : value
                .replace(" ", "").replace("-", "").toUpperCase(Locale.US);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("TOTP secret is empty");
        }
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        long buffer = 0;
        int bits = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current == '=') {
                break;
            }
            int valueAt = alphabet.indexOf(current);
            if (valueAt < 0) {
                throw new IllegalArgumentException("Invalid Base32 TOTP secret");
            }
            buffer = (buffer << 5) | valueAt;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                output.write((int) ((buffer >> bits) & 0xff));
                buffer &= bits == 0 ? 0 : (1L << bits) - 1L;
            }
        }
        return output.toByteArray();
    }
}
