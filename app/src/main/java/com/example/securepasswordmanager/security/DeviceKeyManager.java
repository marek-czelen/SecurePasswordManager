package com.example.securepasswordmanager.security;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

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
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Manages the Android Keystore keys that bind a vault to this device.
 *
 * <p>Two kinds of keys are used:</p>
 * <ul>
 *   <li><b>Device key (AES-256-GCM, no user auth)</b> — binds the TRUE slot to the
 *       device. Encryption and decryption never require authentication.</li>
 *   <li><b>Biometric key (RSA-2048, auth-bound)</b> — protects the FAKE slot DEK.
 *       The public key wraps the DEK <em>without</em> authentication (so the vault can be
 *       created without any biometric), while the private key can only unwrap it after a
 *       successful {@code BiometricPrompt}. This is why an asymmetric key is required: a
 *       symmetric auth-bound key would demand authentication even to encrypt, which makes
 *       first-run vault creation impossible. The key authenticates per operation so it can
 *       be bound to a BiometricPrompt.CryptoObject.</li>
 * </ul>
 *
 * <p>Security boundary: hardware-backed keys protect key material at rest and gate
 * biometric access. They do not protect plaintext once the app is unlocked, and a device
 * without a secure lock screen degrades the biometric key to a non-auth key.</p>
 */
public final class DeviceKeyManager {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPPadding";
    private static final byte TYPE_AES = 1;
    private static final byte TYPE_RSA = 2;

    private final KeyStore keyStore;

    public DeviceKeyManager() throws GeneralSecurityException, IOException {
        keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
    }

    /**
     * Ensures the alias exists. When {@code authenticationRequired} is true an RSA
     * biometric key is created; otherwise an AES device key is created.
     */
    public void ensureKey(String alias, boolean authenticationRequired) throws GeneralSecurityException {
        if (keyStore.containsAlias(alias)) {
            return;
        }
        if (authenticationRequired) {
            // Prefer StrongBox + auth, then TEE + auth. As a last resort fall back to a
            // non-auth RSA key so vault creation still succeeds on devices without a
            // secure lock screen or enrolled biometrics (biometric unlock is then simply
            // unavailable, but PIN access always works).
            if (tryGenerateRsa(alias, true, true)) {
                return;
            }
            if (tryGenerateRsa(alias, false, true)) {
                return;
            }
            if (tryGenerateRsa(alias, false, false)) {
                return;
            }
            throw new KeyStoreException("Unable to create biometric key");
        }
        try {
            generateAes(alias, true);
        } catch (StrongBoxUnavailableException exception) {
            generateAes(alias, false);
        }
    }

    private boolean tryGenerateRsa(String alias, boolean strongBox, boolean auth) {
        try {
            generateRsa(alias, strongBox, auth);
            return true;
        } catch (GeneralSecurityException | RuntimeException exception) {
            safeDelete(alias);
            return false;
        }
    }

    private void generateRsa(String alias, boolean strongBox, boolean auth)
            throws GeneralSecurityException {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP);
        if (auth) {
            builder.setUserAuthenticationRequired(true);
            // Timeout 0 = authenticate on every use. This is required for keys bound to a
            // BiometricPrompt.CryptoObject; a time-based (timeout > 0) key rejects CryptoObject.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);
        generator.initialize(builder.build());
        generator.generateKeyPair();
    }

    private void generateAes(String alias, boolean strongBox) throws GeneralSecurityException {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true);
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(builder.build());
        generator.generateKey();
    }

    /**
     * Wraps a DEK with the given key. AES keys produce {@code TYPE_AES | ivLen | iv | ct};
     * RSA keys produce {@code TYPE_RSA | ct}. Wrapping never requires authentication.
     */
    public byte[] wrap(String alias, byte[] plaintext) throws GeneralSecurityException {
        Key key = keyStore.getKey(alias, null);
        if (key instanceof SecretKey) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[2 + iv.length + ciphertext.length];
            out[0] = TYPE_AES;
            out[1] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 2, iv.length);
            System.arraycopy(ciphertext, 0, out, 2 + iv.length, ciphertext.length);
            return out;
        }
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
     * Prepares an RSA decrypt cipher for the biometric key. The returned cipher must be
     * authorized through a {@code BiometricPrompt.CryptoObject} before use.
     */
    public Cipher prepareDecrypt(String alias) throws GeneralSecurityException {
        Key key = keyStore.getKey(alias, null);
        if (!(key instanceof PrivateKey)) {
            throw new KeyStoreException("Biometric key is unavailable");
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
                return info.getSecurityLevel() != KeyProperties.SECURITY_LEVEL_SOFTWARE;
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
}
