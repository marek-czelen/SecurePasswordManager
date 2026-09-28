package com.example.securepasswordmanager.security;

/** User-secret policy for vault recovery; numeric-only short PINs are intentionally rejected. */
public final class PinPolicy {
    private PinPolicy() {
    }

    public static boolean isValid(char[] pin) {
        if (pin == null || pin.length < 12 || pin.length > 24) {
            return false;
        }
        boolean numericOnly = true;
        for (char character : pin) {
            if (Character.isISOControl(character) || Character.isSurrogate(character)
                    || character > 0x7e || character < 0x20) {
                return false;
            }
            numericOnly &= character >= '0' && character <= '9';
        }
        return !numericOnly;
    }
}
