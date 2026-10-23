package com.cretorix.securepasswordmanager.crypto;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/** Stores immutable, individually authenticated VLT3 objects. */
public final class VaultObjectStore {
    private static final int MAGIC = 0x564f4233;
    private static final int FIXED_OVERHEAD = 4 + 4 + 4 + 1 + 32 + 32 + 1 + 8 + 1 + 12 + 4;

    private final File directory;
    private final byte[] vaultId;
    private final DirectorySync directorySync;

    public VaultObjectStore(File directory, byte[] vaultId) throws IOException {
        this(directory, vaultId, VaultDirectorySync::sync);
    }

    public VaultObjectStore(File directory, byte[] vaultId, DirectorySync directorySync)
            throws IOException {
        if (directory == null || vaultId == null
                || vaultId.length != VaultHeaderV3.VAULT_ID_SIZE || directorySync == null) {
            throw new IllegalArgumentException("Invalid VLT3 object store");
        }
        this.directory = directory;
        this.vaultId = vaultId.clone();
        this.directorySync = directorySync;
        if (!directory.isDirectory()) {
            if (!directory.mkdirs()) {
                throw new IOException("Could not create VLT3 object directory");
            }
            File parent = directory.getParentFile();
            if (parent != null && parent.isDirectory()) {
                directorySync.sync(parent);
            }
        }
    }

    public VaultObjectRef write(byte[] dek, VaultObjectRef.Type type, byte[] ownerEntryId,
                                String contentType, byte[] plaintext)
            throws IOException, GeneralSecurityException {
        validateWriteData(dek, type, ownerEntryId, plaintext);
        for (int attempt = 0; attempt < 4; attempt++) {
            byte[] objectId = VaultObjectIds.randomId();
            try {
                if (!fileFor(objectId).exists()) {
                    return writeWithId(dek, objectId, type, ownerEntryId,
                            contentType, plaintext);
                }
            } finally {
                Crypto.wipe(objectId);
            }
        }
        throw new IOException("Could not allocate a unique VLT3 object ID");
    }

    public VaultObjectRef writeWithId(byte[] dek, byte[] requestedObjectId,
                                      VaultObjectRef.Type type, byte[] ownerEntryId,
                                      String contentType, byte[] plaintext)
            throws IOException, GeneralSecurityException {
        validateWriteData(dek, type, ownerEntryId, plaintext);
        if (!VaultObjectRef.validId(requestedObjectId)) {
            throw new IOException("Invalid VLT3 object ID");
        }
        byte[] objectId = requestedObjectId.clone();
        if (fileFor(objectId).exists()) {
            Crypto.wipe(objectId);
            throw new IOException("VLT3 object ID already exists");
        }
        byte[] generationId = VaultObjectIds.randomId();
        while (Crypto.constantTimeEquals(generationId, objectId)) {
            Crypto.wipe(generationId);
            generationId = VaultObjectIds.randomId();
        }
        String actualContentType = contentType == null ? "" : contentType;
        byte[] key = VaultV3KeyDerivation.deriveObjectKey(dek, vaultId,
                type.name(), objectId, generationId,
                VaultV3Format.OBJECT_SCHEMA_VERSION);
        byte[] aad = associatedData(type, objectId, generationId, ownerEntryId,
                plaintext.length);
        try {
            Crypto.SealedBox box = Crypto.seal(key, plaintext, aad);
            byte[] hash = Crypto.sha256(box.ciphertext);
            VaultObjectRef ref = new VaultObjectRef(objectId, generationId, type,
                    ownerEntryId, plaintext.length, box.ciphertext.length, hash,
                    VaultV3Format.OBJECT_SCHEMA_VERSION, actualContentType);
            writeImmutable(ref, box);
            return ref;
        } finally {
            Crypto.wipe(key);
            Crypto.wipe(objectId);
            Crypto.wipe(generationId);
            Crypto.wipe(aad);
        }
    }

    private static void validateWriteData(byte[] dek, VaultObjectRef.Type type,
                                          byte[] ownerEntryId, byte[] plaintext)
            throws IOException {
        if (dek == null || dek.length != Crypto.KEY_SIZE || type == null || plaintext == null
                || (type == VaultObjectRef.Type.TEXT_BLOCK) != (ownerEntryId == null)
                || (ownerEntryId != null && ownerEntryId.length != 16)
                || (type != VaultObjectRef.Type.FILE && plaintext.length == 0)) {
            throw new IOException("Invalid VLT3 object data");
        }
        if (plaintext.length > VaultObjectRef.maxPlaintext(type)) {
            throw new VaultSizeLimitException("VLT3 object exceeds its configured limit");
        }
    }

    public byte[] read(VaultObjectRef ref, byte[] dek)
            throws IOException, GeneralSecurityException {
        File file = fileFor(ref.objectId);
        long maxFileSize = FIXED_OVERHEAD + (ref.ownerEntryId == null ? 0 : 16)
                + ref.ciphertextLength;
        if (!file.isFile() || file.length() > maxFileSize
                || file.length() < FIXED_OVERHEAD + 16L) {
            throw new IOException("Missing or invalid VLT3 object");
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            ObjectFrame frame = readFrameHeader(input, ref);
            byte[] ciphertext = new byte[(int) ref.ciphertextLength];
            byte[] key = null;
            byte[] plaintext = null;
            try {
                input.readFully(ciphertext);
                if (input.read() != -1
                        || !Crypto.constantTimeEquals(Crypto.sha256(ciphertext),
                        ref.ciphertextSha256)) {
                    throw new IOException("VLT3 object ciphertext hash mismatch");
                }
                key = VaultV3KeyDerivation.deriveObjectKey(dek, vaultId,
                        ref.type.name(), ref.objectId, ref.generationId,
                        ref.objectSchemaVersion);
                plaintext = Crypto.open(key, frame.nonce, ciphertext,
                        associatedData(ref.type, ref.objectId, ref.generationId,
                                ref.ownerEntryId, ref.plaintextLength));
                if (plaintext.length != ref.plaintextLength) {
                    Crypto.wipe(plaintext);
                    throw new IOException("VLT3 object length mismatch");
                }
                return plaintext;
            } finally {
                Crypto.wipe(key);
                Crypto.wipe(ciphertext);
            }
        }
    }

    public void checkPresent(VaultObjectRef ref) throws IOException {
        File file = fileFor(ref.objectId);
        if (!file.isFile() || file.length() != encodedLength(ref)) {
            throw new IOException("Missing or truncated VLT3 object");
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            readFrameHeader(input, ref);
        }
    }

    public void delete(VaultObjectRef ref) throws IOException {
        File file = fileFor(ref.objectId);
        if (file.exists() && !Files.deleteIfExists(file.toPath())) {
            throw new IOException("Could not delete VLT3 object");
        }
        syncDirectory();
    }

    public void deleteAll() throws IOException {
        deleteAllInDirectory(directory);
    }

    public int collectUnreferenced(Set<String> reachableIds) throws IOException {
        if (reachableIds == null) {
            throw new IllegalArgumentException("Reachability set is required");
        }
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException("Could not list VLT3 object directory");
        }
        int deleted = 0;
        for (File file : files) {
            if (!file.isFile()) {
                throw new IOException("Unexpected directory in VLT3 object store");
            }
            String name = file.getName();
            if (name.startsWith(".") && (name.contains(".tmp-")
                    || name.contains(".import-"))) {
                if (!Files.deleteIfExists(file.toPath())) {
                    throw new IOException("Could not remove VLT3 staging object");
                }
                deleted++;
                continue;
            }
            if (!name.matches("[0-9a-f]{64}\\.obj")) {
                throw new IOException("Unknown file in VLT3 object store");
            }
            String id = name.substring(0, 64);
            if (!reachableIds.contains(id)) {
                if (!Files.deleteIfExists(file.toPath())) {
                    throw new IOException("Could not remove orphan VLT3 object");
                }
                deleted++;
            }
        }
        if (deleted > 0) {
            syncDirectory();
        }
        return deleted;
    }

    public static void deleteAllInDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }
        if (!directory.isDirectory()) {
            throw new IOException("VLT3 object path is not a directory");
        }
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException("Could not list VLT3 object directory");
        }
        for (File file : files) {
            if (!file.isFile() || !file.delete()) {
                throw new IOException("Could not remove incomplete VLT3 object");
            }
        }
        VaultDirectorySync.sync(directory);
    }

    public long copyVerified(VaultObjectRef ref, OutputStream destination) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("Backup output is required");
        }
        File file = fileFor(ref.objectId);
        long encodedLength = encodedLength(ref);
        if (!file.isFile() || file.length() != encodedLength) {
            throw new IOException("Missing or truncated VLT3 object");
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            ObjectFrame frame = readFrameHeader(input, ref);
            DataOutputStream output = new DataOutputStream(destination);
            writeFrameHeader(output, ref, frame.nonce);
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[8192];
            long remaining = ref.ciphertextLength;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("Truncated VLT3 object ciphertext");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                remaining -= read;
            }
            if (input.read() != -1
                    || !Crypto.constantTimeEquals(digest.digest(), ref.ciphertextSha256)) {
                throw new IOException("VLT3 object ciphertext hash mismatch");
            }
            output.flush();
            return encodedLength;
        }
    }

    public void importObject(VaultObjectRef ref, InputStream source, long encodedLength)
            throws IOException {
        if (source == null || encodedLength != encodedLength(ref)) {
            throw new IOException("Invalid VLT3 backup object length");
        }
        File target = fileFor(ref.objectId);
        if (target.exists()) {
            throw new IOException("VLT3 object ID already exists");
        }
        byte[] temporaryId = VaultObjectIds.randomId();
        File temporary = new File(directory, "." + VaultObjectIds.toHex(ref.objectId)
                + ".import-" + VaultObjectIds.toHex(temporaryId));
        Crypto.wipe(temporaryId);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                byte[] buffer = new byte[8192];
                long remaining = encodedLength;
                while (remaining > 0) {
                    int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        throw new IOException("Truncated VLT3 backup object");
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
                output.getFD().sync();
            }
            verifyFile(temporary, ref);
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic VLT3 object import is unavailable", exception);
            }
            syncDirectory();
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    public static long encodedLength(VaultObjectRef ref) {
        return FIXED_OVERHEAD + (ref.ownerEntryId == null ? 0 : ref.ownerEntryId.length)
                + ref.ciphertextLength;
    }

    public File fileFor(byte[] objectId) {
        if (!VaultObjectRef.validId(objectId)) {
            throw new IllegalArgumentException("Invalid VLT3 object ID");
        }
        return new File(directory, hex(objectId) + ".obj");
    }

    private void writeImmutable(VaultObjectRef ref, Crypto.SealedBox box)
            throws IOException {
        File target = fileFor(ref.objectId);
        if (target.exists()) {
            throw new IOException("VLT3 object ID already exists");
        }
        File temporary = new File(directory,
                "." + hex(ref.objectId) + ".tmp-" + hex(Crypto.randomBytes(8)));
        try {
            try (FileOutputStream fileOutput = new FileOutputStream(temporary, false);
                 DataOutputStream output = new DataOutputStream(fileOutput)) {
                output.writeInt(MAGIC);
                output.writeInt(VaultV3Format.CONTAINER_FORMAT_VERSION);
                output.writeInt(ref.objectSchemaVersion);
                output.writeByte(ref.type.id);
                output.write(ref.objectId);
                output.write(ref.generationId);
                output.writeBoolean(ref.ownerEntryId != null);
                if (ref.ownerEntryId != null) {
                    output.write(ref.ownerEntryId);
                }
                output.writeLong(ref.plaintextLength);
                output.writeByte(box.nonce.length);
                output.write(box.nonce);
                output.writeInt(box.ciphertext.length);
                output.write(box.ciphertext);
                output.flush();
                fileOutput.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic VLT3 object rename is unavailable", exception);
            }
            syncDirectory();
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void verifyFile(File file, VaultObjectRef ref) throws IOException {
        if (!file.isFile() || file.length() != encodedLength(ref)) {
            throw new IOException("Missing or truncated VLT3 object");
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            readFrameHeader(input, ref);
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[8192];
            long remaining = ref.ciphertextLength;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("Truncated VLT3 object ciphertext");
                }
                digest.update(buffer, 0, read);
                remaining -= read;
            }
            if (input.read() != -1
                    || !Crypto.constantTimeEquals(digest.digest(), ref.ciphertextSha256)) {
                throw new IOException("VLT3 object ciphertext hash mismatch");
            }
        }
    }

    private static void writeFrameHeader(DataOutputStream output, VaultObjectRef ref,
                                         byte[] nonce) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VaultV3Format.CONTAINER_FORMAT_VERSION);
        output.writeInt(ref.objectSchemaVersion);
        output.writeByte(ref.type.id);
        output.write(ref.objectId);
        output.write(ref.generationId);
        output.writeBoolean(ref.ownerEntryId != null);
        if (ref.ownerEntryId != null) {
            output.write(ref.ownerEntryId);
        }
        output.writeLong(ref.plaintextLength);
        output.writeByte(nonce.length);
        output.write(nonce);
        output.writeInt((int) ref.ciphertextLength);
    }

    private ObjectFrame readFrameHeader(DataInputStream input, VaultObjectRef ref)
            throws IOException {
        if (input.readInt() != MAGIC
                || input.readInt() != VaultV3Format.CONTAINER_FORMAT_VERSION
                || input.readInt() != ref.objectSchemaVersion
                || input.readUnsignedByte() != ref.type.id) {
            throw new IOException("Invalid VLT3 object header");
        }
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
        int nonceLength = input.readUnsignedByte();
        if (!Crypto.constantTimeEquals(objectId, ref.objectId)
                || !Crypto.constantTimeEquals(generationId, ref.generationId)
                || hasOwner != (ref.ownerEntryId != null)
                || (owner != null && !Crypto.constantTimeEquals(owner, ref.ownerEntryId))
                || plaintextLength != ref.plaintextLength || nonceLength != Crypto.NONCE_SIZE) {
            throw new IOException("VLT3 object reference mismatch");
        }
        byte[] nonce = new byte[nonceLength];
        input.readFully(nonce);
        int ciphertextLength = input.readInt();
        if (ciphertextLength < 16 || ciphertextLength != ref.ciphertextLength) {
            throw new IOException("Invalid VLT3 object ciphertext length");
        }
        return new ObjectFrame(nonce);
    }

    private byte[] associatedData(VaultObjectRef.Type type, byte[] objectId,
                                  byte[] generationId, byte[] ownerEntryId,
                                  long plaintextLength) {
        byte[] domain = "SPM/VLT3/object".getBytes(StandardCharsets.UTF_8);
        int ownerLength = ownerEntryId == null ? 0 : ownerEntryId.length;
        ByteBuffer buffer = ByteBuffer.allocate(domain.length + 4 * 3 + 1 + 32 + 32
                + 1 + ownerLength + 8);
        buffer.put(domain);
        buffer.putInt(VaultV3Format.CONTAINER_FORMAT_VERSION);
        buffer.putInt(VaultV3Format.OBJECT_SCHEMA_VERSION);
        buffer.putInt(type.id);
        buffer.put(objectId);
        buffer.put(generationId);
        buffer.put((byte) (ownerEntryId == null ? 0 : 1));
        if (ownerEntryId != null) {
            buffer.put(ownerEntryId);
        }
        buffer.putLong(plaintextLength);
        return buffer.array();
    }

    private void syncDirectory() throws IOException {
        directorySync.sync(directory);
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(item & 0x0f, 16));
        }
        return output.toString();
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    public interface DirectorySync {
        void sync(File directory) throws IOException;
    }

    private static final class ObjectFrame {
        final byte[] nonce;

        ObjectFrame(byte[] nonce) {
            this.nonce = nonce;
        }
    }
}
