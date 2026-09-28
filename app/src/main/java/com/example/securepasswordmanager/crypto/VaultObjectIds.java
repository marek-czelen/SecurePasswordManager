package com.example.securepasswordmanager.crypto;

/** Opaque identifiers for immutable VLT3 object generations. */
public final class VaultObjectIds {
    private VaultObjectIds() {
    }

    public static byte[] randomId() {
        return Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE);
    }

    public static String toHex(byte[] value) {
        if (!VaultObjectRef.validId(value)) {
            throw new IllegalArgumentException("Invalid VLT3 object ID");
        }
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(item & 0x0f, 16));
        }
        return output.toString();
    }

    public static byte[] fromHex(String value) {
        if (!isValidHex(value, VaultHeaderV3.GENERATION_ID_SIZE)) {
            throw new IllegalArgumentException("Invalid VLT3 object ID");
        }
        byte[] result = new byte[VaultHeaderV3.GENERATION_ID_SIZE];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    public static boolean isValidHex(String value, int byteLength) {
        if (value == null || value.length() != byteLength * 2) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
