package com.example.securepasswordmanager;

import com.example.securepasswordmanager.crypto.VaultData;

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
    private static boolean biometricSession;
    private static boolean active;

    private Session() {
    }

    static synchronized void set(char[] pin, VaultData vaultData, boolean biometric) {
        // Store an independent copy: the Activity may wipe its own PIN array on lock, and
        // that must never zero the session copy (which caused crashes on save/restore).
        truePin = pin == null ? null : pin.clone();
        data = vaultData;
        biometricSession = biometric;
        active = true;
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

    static synchronized void clear() {
        if (truePin != null) {
            Arrays.fill(truePin, '\0');
        }
        truePin = null;
        data = null;
        biometricSession = false;
        active = false;
    }
}
