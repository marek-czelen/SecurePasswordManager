package com.cretorix.securepasswordmanager.security;

/** User-secret policy for vault recovery; numeric-only PINs are intentionally rejected. */
public final class PinPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 24;

    private PinPolicy() {
    }

    public static boolean isValid(char[] pin) {
        if (pin == null || pin.length < MIN_LENGTH || pin.length > MAX_LENGTH) {
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
