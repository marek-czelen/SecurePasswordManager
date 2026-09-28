package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/** Bounded plaintext and encrypted encodings for VLT3 manifests. */
public final class VaultManifestCodec {
    private static final int PLAIN_MAGIC = 0x564d4633;
    private static final int ENCRYPTED_MAGIC = 0x564d4533;
    private static final int MAX_MANIFEST_SIZE = 8 * 1024 * 1024;

    private VaultManifestCodec() {
    }

    public static byte[] seal(VaultManifest manifest, byte[] dek, byte[] vaultId)
            throws IOException, GeneralSecurityException {
        byte[] plaintext = encodePlaintext(manifest);
        byte[] key = VaultV3KeyDerivation.deriveManifestKey(dek, vaultId,
                manifest.generationId);
        try {
            byte[] aad = aad(vaultId, manifest.generationId, manifest.schemaVersion);
            Crypto.SealedBox box = Crypto.seal(key, plaintext, aad);
            byte[] encodedBox = box.encode();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(ENCRYPTED_MAGIC);
            output.writeInt(VaultV3Format.CONTAINER_FORMAT_VERSION);
            output.writeInt(manifest.schemaVersion);
            output.write(manifest.generationId);
            output.writeInt(encodedBox.length);
            output.write(encodedBox);
            output.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_MANIFEST_SIZE) {
                throw new IOException("VLT3 manifest is too large");
            }
            return result;
        } finally {
            Crypto.wipe(key);
            Crypto.wipe(plaintext);
        }
    }

    public static VaultManifest open(byte[] encoded, byte[] dek, byte[] vaultId)
            throws IOException, GeneralSecurityException {
        if (encoded == null || encoded.length < 64 || encoded.length > MAX_MANIFEST_SIZE
                || dek == null || dek.length != Crypto.KEY_SIZE
                || vaultId == null || vaultId.length != VaultHeaderV3.VAULT_ID_SIZE) {
            throw new IOException("Invalid VLT3 manifest size or key");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != ENCRYPTED_MAGIC
                || input.readInt() != VaultV3Format.CONTAINER_FORMAT_VERSION) {
            throw new IOException("Invalid VLT3 manifest container");
        }
        int schemaVersion = input.readInt();
        if (schemaVersion != VaultV3Format.MANIFEST_SCHEMA_VERSION) {
            throw new IOException("Unsupported VLT3 manifest schema");
        }
        byte[] generationId = new byte[32];
        input.readFully(generationId);
        int boxLength = input.readInt();
        if (boxLength < 1 || boxLength > MAX_MANIFEST_SIZE
                || boxLength != input.available()) {
            throw new IOException("Invalid VLT3 manifest ciphertext length");
        }
        byte[] encodedBox = new byte[boxLength];
        input.readFully(encodedBox);
        DataInputStream boxInput = new DataInputStream(new ByteArrayInputStream(encodedBox));
        Crypto.SealedBox box = Crypto.SealedBox.decode(boxInput);
        if (boxInput.available() != 0) {
            throw new IOException("Trailing VLT3 manifest ciphertext");
        }
        byte[] key = VaultV3KeyDerivation.deriveManifestKey(dek, vaultId, generationId);
        byte[] plaintext = null;
        try {
            plaintext = Crypto.open(key, box, aad(vaultId, generationId, schemaVersion));
            VaultManifest manifest = decodePlaintext(plaintext);
            if (!Crypto.constantTimeEquals(generationId, manifest.generationId)
                    || schemaVersion != manifest.schemaVersion) {
                throw new IOException("VLT3 manifest generation mismatch");
            }
            return manifest;
        } finally {
            Crypto.wipe(key);
            Crypto.wipe(plaintext);
        }
    }

    static byte[] encodePlaintext(VaultManifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(PLAIN_MAGIC);
        output.writeInt(manifest.schemaVersion);
        output.write(manifest.generationId);
        writeRef(output, manifest.textBlock);
        output.writeInt(manifest.resources.size());
        for (VaultObjectRef resource : manifest.resources) {
            writeRef(output, resource);
        }
        output.flush();
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_MANIFEST_SIZE) {
            throw new IOException("VLT3 manifest plaintext is too large");
        }
        return encoded;
    }

    static VaultManifest decodePlaintext(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 48 || encoded.length > MAX_MANIFEST_SIZE) {
            throw new IOException("Invalid VLT3 manifest plaintext");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != PLAIN_MAGIC) {
            throw new IOException("Invalid VLT3 manifest magic");
        }
        int schemaVersion = input.readInt();
        if (schemaVersion != VaultV3Format.MANIFEST_SCHEMA_VERSION) {
            throw new IOException("Unsupported VLT3 manifest schema");
        }
        byte[] generationId = new byte[32];
        input.readFully(generationId);
        VaultObjectRef textBlock = readRef(input);
        if (textBlock.type != VaultObjectRef.Type.TEXT_BLOCK) {
            throw new IOException("Invalid VLT3 text block reference");
        }
        int count = input.readInt();
        if (count < 0 || count > VaultManifest.MAX_RESOURCES) {
            throw new IOException("Invalid VLT3 resource count");
        }
        List<VaultObjectRef> resources = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            VaultObjectRef ref = readRef(input);
            if (ref.type == VaultObjectRef.Type.TEXT_BLOCK) {
                throw new IOException("Unexpected VLT3 text block resource");
            }
            resources.add(ref);
        }
        if (input.available() != 0) {
            throw new IOException("Trailing VLT3 manifest data");
        }
        try {
            return new VaultManifest(generationId, textBlock, resources);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Invalid VLT3 manifest references", exception);
        }
    }

    private static void writeRef(DataOutputStream output, VaultObjectRef ref)
            throws IOException {
        output.writeByte(ref.type.id);
        output.writeInt(ref.objectSchemaVersion);
        output.write(ref.objectId);
        output.write(ref.generationId);
        output.writeBoolean(ref.ownerEntryId != null);
        if (ref.ownerEntryId != null) {
            output.write(ref.ownerEntryId);
        }
        output.writeLong(ref.plaintextLength);
        output.writeLong(ref.ciphertextLength);
        output.write(ref.ciphertextSha256);
        byte[] contentType = ref.contentType.getBytes(StandardCharsets.UTF_8);
        if (contentType.length > VaultObjectRef.MAX_CONTENT_TYPE_LENGTH) {
            throw new IOException("VLT3 content type is too long");
        }
        output.writeShort(contentType.length);
        output.write(contentType);
    }

    private static VaultObjectRef readRef(DataInputStream input) throws IOException {
        VaultObjectRef.Type type = VaultObjectRef.Type.fromId(input.readUnsignedByte());
        int objectSchema = input.readInt();
        byte[] objectId = new byte[32];
        byte[] generationId = new byte[32];
        input.readFully(objectId);
        input.readFully(generationId);
        boolean hasOwner = input.readBoolean();
        byte[] owner = null;
        if (hasOwner) {
            owner = new byte[16];
            input.readFully(owner);
        }
        long plaintextLength = input.readLong();
        long ciphertextLength = input.readLong();
        byte[] hash = new byte[32];
        input.readFully(hash);
        int contentTypeLength = input.readUnsignedShort();
        if (contentTypeLength > VaultObjectRef.MAX_CONTENT_TYPE_LENGTH) {
            throw new IOException("Invalid VLT3 content type length");
        }
        byte[] contentTypeBytes = new byte[contentTypeLength];
        input.readFully(contentTypeBytes);
        String contentType = new String(contentTypeBytes, StandardCharsets.UTF_8);
        if (type == null) {
            throw new IOException("Unknown VLT3 object type");
        }
        try {
            return new VaultObjectRef(objectId, generationId, type, owner,
                    plaintextLength, ciphertextLength, hash, objectSchema, contentType);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid VLT3 object reference", exception);
        }
    }

    private static byte[] aad(byte[] vaultId, byte[] generationId, int schemaVersion) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 16 + 32 + 4);
        buffer.putInt(0x53504d33);
        buffer.putInt(VaultV3Format.CONTAINER_FORMAT_VERSION);
        buffer.put(vaultId);
        buffer.put(generationId);
        buffer.putInt(schemaVersion);
        return buffer.array();
    }
}
