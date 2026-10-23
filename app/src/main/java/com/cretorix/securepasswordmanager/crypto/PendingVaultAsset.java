package com.cretorix.securepasswordmanager.crypto;

/** One new media object staged for the next VLT3 text/manifest commit. */
public final class PendingVaultAsset {
    public final byte[] objectId;
    public final byte[] ownerEntryId;
    public final VaultObjectRef.Type type;
    public final String contentType;
    public final byte[] plaintext;

    public PendingVaultAsset(String objectId, String ownerEntryId, VaultObjectRef.Type type,
                             String contentType, byte[] plaintext) {
        if (type == null || type == VaultObjectRef.Type.TEXT_BLOCK
                || !VaultItem.isHexId(objectId, 32)
                || !VaultItem.isHexId(ownerEntryId, 16) || plaintext == null
                || plaintext.length > VaultObjectRef.maxPlaintext(type)
                || (type != VaultObjectRef.Type.FILE && plaintext.length == 0)
                || contentType == null
                || contentType.length() > VaultObjectRef.MAX_CONTENT_TYPE_LENGTH
                || contentType.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > VaultObjectRef.MAX_CONTENT_TYPE_LENGTH) {
            throw new IllegalArgumentException("Invalid pending VLT3 asset");
        }
        this.objectId = VaultObjectIds.fromHex(objectId);
        this.ownerEntryId = fromHex(ownerEntryId, 16);
        this.type = type;
        this.contentType = contentType;
        this.plaintext = plaintext.clone();
    }

    public void clear() {
        Crypto.wipe(plaintext);
    }

    private static byte[] fromHex(String value, int byteLength) {
        byte[] result = new byte[byteLength];
        for (int i = 0; i < byteLength; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
