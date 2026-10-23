package com.cretorix.securepasswordmanager;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;

/** Builds a device-auth prompt that accepts strong biometrics or the secure screen lock. */
final class DeviceAuthenticationPrompt {
    private DeviceAuthenticationPrompt() {
    }

    static BiometricPrompt.Builder builder(Activity activity, String title, String subtitle) {
        return new BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
    }
}
