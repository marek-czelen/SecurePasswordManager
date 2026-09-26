package com.example.securepasswordmanager.security;

public final class PinPolicy {
    private PinPolicy() {
    }

    public static boolean isValid(char[] pin) {
        if (pin == null || pin.length < 6 || pin.length > 12) {
            return false;
        }
        for (char character : pin) {
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
