package com.example.securepasswordmanager;

import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.Crypto;

import java.util.Arrays;

/**
 * Process-scoped holder for the currently unlocked vault session.
 *
 * <p>Why this exists: launching the camera or the full-screen OCR review can cause Android
 * to destroy and recreate {@link MainActivity} (memory pressure, "don't keep activities").
 * The unlocked state must survive that recreation, otherwise returning from OCR drops the
 * user back to the unlock screen.</p>
 *
 * <p>Security note: this keeps the same plaintext in process memory that the Activity
 * already held — it does not weaken the model. It lives only for the process lifetime and
 * is wiped on auto-lock / explicit lock. It is intentionally not persisted to disk.</p>
 */
final class Session {

    private static char[] truePin;
    private static VaultData data;
    private static byte[] dek;
    private static byte[] qrSecret;
    private static boolean biometricSession;
    private static boolean recoveredPrevious;
    private static boolean active;

    private Session() {
    }

    static synchronized void set(char[] pin, VaultData vaultData, boolean biometric) {
        set(pin, vaultData, biometric, null);
    }

    static synchronized void set(char[] pin, VaultData vaultData, boolean biometric,
                                byte[] dataKey) {
        // Store an independent copy: the Activity may wipe its own PIN array on lock, and
        // that must never zero the session copy (which caused crashes on save/restore).
        truePin = pin == null ? null : pin.clone();
        data = vaultData;
        Crypto.wipe(dek);
        dek = dataKey == null ? null : dataKey.clone();
        biometricSession = biometric;
        active = true;
    }

    static synchronized void setRecoverySecret(byte[] secret) {
        Crypto.wipe(qrSecret);
        qrSecret = secret == null ? null : secret.clone();
    }

    static synchronized byte[] dek() {
        return dek == null ? null : dek.clone();
    }

    static synchronized byte[] recoverySecret() {
        return qrSecret == null ? null : qrSecret.clone();
    }

    static synchronized void updateData(VaultData vaultData) {
        if (active) {
            data = vaultData;
        }
    }

    static synchronized boolean isActive() {
        return active;
    }

    static synchronized char[] pin() {
        return truePin == null ? null : truePin.clone();
    }

    static synchronized VaultData data() {
        return data == null ? VaultData.empty() : data;
    }

    static synchronized boolean isBiometricSession() {
        return biometricSession;
    }

    static synchronized void setRecoveredPrevious(boolean value) {
        recoveredPrevious = value;
    }

    static synchronized boolean isRecoveredPrevious() {
        return recoveredPrevious;
    }

    static synchronized void clear() {
        if (truePin != null) {
            Arrays.fill(truePin, '\0');
        }
        truePin = null;
        data = null;
        Crypto.wipe(dek);
        dek = null;
        Crypto.wipe(qrSecret);
        qrSecret = null;
        biometricSession = false;
        recoveredPrevious = false;
        active = false;
    }
}
