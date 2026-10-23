package com.cretorix.securepasswordmanager.security;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Manages the Android Keystore keys that bind a vault to this device.
 *
 * <p>The device key is RSA-3072 where supported (RSA-2048 only as a compatibility fallback).
 * Its public half wraps during setup without authentication; its private half can only
 * unwrap after a strong biometric or secure screen-lock credential.</p>
 *
 * <p>Security boundary: hardware-backed keys protect key material at rest and gate
 * device authentication. They do not protect plaintext once the app is unlocked. VLT3 fails
 * closed rather than silently accepting a software-backed key.</p>
 */
public final class DeviceKeyManager {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPPadding";
    private static final byte TYPE_RSA = 2;

    private final KeyStore keyStore;

    public DeviceKeyManager() throws GeneralSecurityException, IOException {
        keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
    }

    /**
     * Ensures an authentication-bound RSA key exists. VLT3 never uses an unauthenticated
     * fallback: a device unable to provide hardware-backed device authentication must fail closed.
     */
    public void ensureKey(String alias, boolean authenticationRequired) throws GeneralSecurityException {
        if (keyStore.containsAlias(alias)) {
            return;
        }
        if (!authenticationRequired) {
            throw new KeyStoreException("VLT3 device keys must require device authentication");
        }
        if (tryGenerateRsa(alias, true, 3072) || tryGenerateRsa(alias, false, 3072)
                || tryGenerateRsa(alias, false, 2048)) {
            if (!isHardwareBacked(alias)) {
                safeDelete(alias);
                throw new KeyStoreException("Hardware-backed device-auth key unavailable");
            }
            return;
        }
        throw new KeyStoreException("Unable to create hardware-backed device-auth key");
    }

    private boolean tryGenerateRsa(String alias, boolean strongBox, int size) {
        try {
            generateRsa(alias, strongBox, size);
            return true;
        } catch (GeneralSecurityException | RuntimeException exception) {
            safeDelete(alias);
            return false;
        }
    }

    private void generateRsa(String alias, boolean strongBox, int size)
            throws GeneralSecurityException {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(size)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP);
        builder.setUserAuthenticationRequired(true);
        // Timeout 0 makes every private decrypt require a device-auth prompt.
        builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG
                | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        builder.setInvalidatedByBiometricEnrollment(true);
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);
        generator.initialize(builder.build());
        generator.generateKeyPair();
    }

    /**
     * Wraps a DEK with the public key. Public wrapping never requires authentication.
     */
    public byte[] wrap(String alias, byte[] plaintext) throws GeneralSecurityException {
        Certificate certificate = keyStore.getCertificate(alias);
        if (certificate == null) {
            throw new KeyStoreException("Keystore key is unavailable");
        }
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, certificate.getPublicKey(), oaepSpec());
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] out = new byte[1 + ciphertext.length];
        out[0] = TYPE_RSA;
        System.arraycopy(ciphertext, 0, out, 1, ciphertext.length);
        return out;
    }

    /**
     * Prepares an RSA decrypt cipher for the device-authenticated key. The returned cipher
     * must be authorized through a device-authenticated {@code BiometricPrompt.CryptoObject}.
     */
    public Cipher prepareDecrypt(String alias) throws GeneralSecurityException {
        Key key = keyStore.getKey(alias, null);
        if (!(key instanceof PrivateKey)) {
            throw new KeyStoreException("Device-auth key is unavailable");
        }
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key, oaepSpec());
        return cipher;
    }

    /** Unwraps an RSA-wrapped DEK using an authenticated cipher. */
    public byte[] decrypt(Cipher authenticatedCipher, byte[] wrapped) throws GeneralSecurityException {
        if (wrapped.length < 2 || wrapped[0] != TYPE_RSA) {
            throw new GeneralSecurityException("Unexpected hardware key material");
        }
        byte[] ciphertext = new byte[wrapped.length - 1];
        System.arraycopy(wrapped, 1, ciphertext, 0, ciphertext.length);
        return authenticatedCipher.doFinal(ciphertext);
    }

    public boolean isHardwareBacked(String alias) throws GeneralSecurityException {
        Key key = keyStore.getKey(alias, null);
        if (key == null) {
            return false;
        }
        try {
            KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), ANDROID_KEYSTORE);
            KeyInfo info = factory.getKeySpec(key, KeyInfo.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int securityLevel = info.getSecurityLevel();
                return securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                        || securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX;
            }
            return info.isInsideSecureHardware();
        } catch (InvalidKeySpecException exception) {
            return false;
        }
    }

    private static OAEPParameterSpec oaepSpec() {
        // AndroidKeyStore uses SHA-1 for MGF1 internally; specifying it explicitly keeps
        // encrypt (public key) and decrypt (private key) consistent across providers.
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT);
    }

    private void safeDelete(String alias) {
        try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (KeyStoreException ignored) {
            // Nothing else we can do; the next generate attempt uses a fresh spec.
        }
    }

    public static String alias(byte[] vaultId, String purpose) {
        return "spm_" + purpose + "_" + UUID.nameUUIDFromBytes(vaultId).toString();
    }

    /** Deletes an invalidated key so recovery can bind the vault to a fresh device key. */
    public void replaceAuthenticatedKey(String alias) throws GeneralSecurityException {
        safeDelete(alias);
        ensureKey(alias, true);
    }

    public void deleteKey(String alias) throws GeneralSecurityException {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias);
        }
    }
}
