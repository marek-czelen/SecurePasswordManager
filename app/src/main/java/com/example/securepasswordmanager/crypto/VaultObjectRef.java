package com.example.securepasswordmanager.crypto;

/** Authenticated manifest reference to one immutable encrypted object generation. */
public final class VaultObjectRef {
    public static final int MAX_CONTENT_TYPE_LENGTH = 64;

    public enum Type {
        TEXT_BLOCK(1),
        IMAGE(2),
        THUMBNAIL(3),
        FILE(4),
        DOCUMENT_PAGE(5);

        final int id;

        Type(int id) {
            this.id = id;
        }

        static Type fromId(int id) {
            for (Type value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return null;
        }
    }

    public final byte[] objectId;
    public final byte[] generationId;
    public final Type type;
    public final byte[] ownerEntryId;
    public final long plaintextLength;
    public final long ciphertextLength;
    public final byte[] ciphertextSha256;
    public final int objectSchemaVersion;
    public final String contentType;

    public VaultObjectRef(byte[] objectId, byte[] generationId, Type type,
                          byte[] ownerEntryId, long plaintextLength,
                          long ciphertextLength, byte[] ciphertextSha256,
                          int objectSchemaVersion, String contentType) {
        if (!validId(objectId) || !validId(generationId) || type == null
                || (type == Type.TEXT_BLOCK) != (ownerEntryId == null)
                || (ownerEntryId != null && ownerEntryId.length != 16)
                || plaintextLength < 0 || plaintextLength > maxPlaintext(type)
                || ciphertextLength != plaintextLength + 16
                || ciphertextSha256 == null || ciphertextSha256.length != 32
                || objectSchemaVersion != VaultV3Format.OBJECT_SCHEMA_VERSION
                || contentType == null || contentType.length() > MAX_CONTENT_TYPE_LENGTH
                || contentType.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_CONTENT_TYPE_LENGTH
                || (type == Type.TEXT_BLOCK && !contentType.isEmpty())) {
            throw new IllegalArgumentException("Invalid VLT3 object reference");
        }
        this.objectId = objectId.clone();
        this.generationId = generationId.clone();
        this.type = type;
        this.ownerEntryId = ownerEntryId == null ? null : ownerEntryId.clone();
        this.plaintextLength = plaintextLength;
        this.ciphertextLength = ciphertextLength;
        this.ciphertextSha256 = ciphertextSha256.clone();
        this.objectSchemaVersion = objectSchemaVersion;
        this.contentType = contentType;
    }

    static boolean validId(byte[] id) {
        return id != null && id.length == 32;
    }

    static long maxPlaintext(Type type) {
        switch (type) {
            case TEXT_BLOCK:
                return 32L * 1024 * 1024;
            case IMAGE:
            case FILE:
            case DOCUMENT_PAGE:
                return 10L * 1024 * 1024;
            case THUMBNAIL:
                return 256L * 1024;
            default:
                throw new IllegalArgumentException("Unknown VLT3 object type");
        }
    }
}
