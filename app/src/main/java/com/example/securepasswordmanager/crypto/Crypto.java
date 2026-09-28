package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small, standard cryptographic primitives used by the vault.
 *
 * <p>This class deliberately does not invent an encryption scheme. AES-GCM provides
 * confidentiality and integrity, HKDF provides domain-separated key derivation, and
 * the platform CSPRNG supplies unpredictable nonces and secrets. Callers own lifecycle
 * decisions and must wipe mutable key material when they are done.</p>
 */
public final class Crypto {
    public static final int KEY_SIZE = 32;
    public static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {
    }

    public static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    public static SealedBox seal(byte[] key, byte[] plaintext, byte[] associatedData)
            throws GeneralSecurityException {
        byte[] nonce = randomBytes(NONCE_SIZE);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return new SealedBox(nonce, cipher.doFinal(plaintext));
    }

    public static byte[] open(byte[] key, SealedBox box, byte[] associatedData)
            throws GeneralSecurityException {
        return open(key, box.nonce, box.ciphertext, associatedData);
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] ciphertext,
                              byte[] associatedData) throws GeneralSecurityException {
        if (key == null || key.length != KEY_SIZE || nonce == null
                || nonce.length != NONCE_SIZE || ciphertext == null || ciphertext.length < 16) {
            throw new IllegalArgumentException("Invalid AES-GCM input");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return cipher.doFinal(ciphertext);
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    public static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    public static byte[] hkdf(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        byte[] actualSalt = salt == null ? new byte[32] : salt;
        byte[] prk = hmacSha256(actualSalt, inputKeyMaterial);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        int counter = 1;
        while (output.size() < length) {
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            block.write(previous, 0, previous.length);
            if (info != null) {
                block.write(info, 0, info.length);
            }
            block.write(counter);
            previous = hmacSha256(prk, block.toByteArray());
            output.write(previous, 0, previous.length);
            counter++;
        }
        byte[] result = output.toByteArray();
        byte[] truncated = new byte[length];
        System.arraycopy(result, 0, truncated, 0, length);
        wipe(prk);
        wipe(previous);
        wipe(result);
        return truncated;
    }

    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        return left != null && right != null && MessageDigest.isEqual(left, right);
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static void wipe(byte[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, (byte) 0);
        }
    }

    public static final class SealedBox {
        public final byte[] nonce;
        public final byte[] ciphertext;

        public SealedBox(byte[] nonce, byte[] ciphertext) {
            if (nonce.length != NONCE_SIZE || ciphertext.length < 16) {
                throw new IllegalArgumentException("Invalid AES-GCM box");
            }
            this.nonce = nonce.clone();
            this.ciphertext = ciphertext.clone();
        }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(nonce.length);
            output.writeInt(ciphertext.length);
            output.write(nonce);
            output.write(ciphertext);
            output.flush();
            return bytes.toByteArray();
        }

        public static SealedBox decode(DataInputStream input) throws IOException {
            int nonceLength = input.readUnsignedByte();
            int ciphertextLength = input.readInt();
            if (nonceLength != NONCE_SIZE || ciphertextLength < 16 || ciphertextLength > 64 * 1024 * 1024) {
                throw new IOException("Invalid AES-GCM box lengths");
            }
            byte[] nonce = new byte[nonceLength];
            byte[] ciphertext = new byte[ciphertextLength];
            input.readFully(nonce);
            input.readFully(ciphertext);
            return new SealedBox(nonce, ciphertext);
        }
    }
}
