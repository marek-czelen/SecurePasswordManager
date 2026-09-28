package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** VLT3 text-only block codec. Media bytes are never accepted or serialized here. */
public final class VaultTextDataCodec {
    public static final int MAX_ITEMS = 10_000;
    public static final int MAX_FIELD_LENGTH = 256 * 1024;
    public static final int MAX_BLOCK_SIZE = 32 * 1024 * 1024;
    private static final int MAGIC = 0x56544433;
    private static final int LEGACY_TEXT_DATA_SCHEMA_VERSION = 1;
    private static final int PASSWORD_HISTORY_SCHEMA_VERSION = 2;
    private static final int PADDING_BLOCK_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultTextDataCodec() {
    }

    public static byte[] encode(VaultData data) throws IOException {
        if (data == null || data.items.size() > MAX_ITEMS) {
            if (data != null && data.items.size() > MAX_ITEMS) {
                throw new VaultSizeLimitException("VLT3 entry count exceeds 10,000");
            }
            throw new IOException("Invalid VLT3 text data");
        }
        BoundedOutputStream rawBytes = new BoundedOutputStream(MAX_BLOCK_SIZE - Integer.BYTES);
        DataOutputStream raw = new DataOutputStream(rawBytes);
        raw.writeInt(MAGIC);
        raw.writeInt(VaultV3Format.TEXT_DATA_SCHEMA_VERSION);
        raw.writeInt(data.items.size());
        Set<String> entryIds = new HashSet<>();
        Set<String> resourceIds = new HashSet<>();
        for (VaultItem item : data.items) {
            validateItem(item, entryIds, resourceIds);
            writeFixedHex(raw, item.entryId, 16);
            raw.writeByte(typeId(item.type));
            writeString(raw, item.title);
            writeString(raw, item.username);
            writeString(raw, item.password);
            writeString(raw, item.url);
            writeString(raw, item.notes);
            writeString(raw, item.tags);
            writeString(raw, item.folder);
            writeString(raw, item.totpSecret);
            raw.writeBoolean(item.favorite);
            if (item.isImage() || item.isFile()) {
                writeFixedHex(raw, item.attachmentId, 32);
                raw.writeLong(item.attachmentSize);
                writeString(raw, item.contentType);
                if (item.isImage()) {
                    raw.writeBoolean(item.thumbnailId != null);
                    if (item.thumbnailId != null) {
                        writeFixedHex(raw, item.thumbnailId, 32);
                    }
                }
            }
            raw.writeInt(item.passwordHistory.size());
            for (String previousPassword : item.passwordHistory) {
                writeString(raw, previousPassword);
            }
        }
        raw.flush();
        byte[] encoded = rawBytes.toByteArray();
        int required;
        try {
            required = Math.addExact(Integer.BYTES, encoded.length);
        } catch (ArithmeticException exception) {
            throw new IOException("VLT3 text block is too large", exception);
        }
        int paddedLength = roundUp(required, PADDING_BLOCK_SIZE);
        if (paddedLength > MAX_BLOCK_SIZE) {
            throw new VaultSizeLimitException("VLT3 text block exceeds 32 MiB");
        }
        ByteArrayOutputStream paddedBytes = new ByteArrayOutputStream(paddedLength);
        DataOutputStream padded = new DataOutputStream(paddedBytes);
        padded.writeInt(encoded.length);
        padded.write(encoded);
        byte[] padding = new byte[paddedLength - required];
        RANDOM.nextBytes(padding);
        padded.write(padding);
        padded.flush();
        Crypto.wipe(encoded);
        Crypto.wipe(padding);
        return paddedBytes.toByteArray();
    }

    public static VaultData decode(byte[] padded) throws IOException {
        if (padded == null || padded.length < PADDING_BLOCK_SIZE
                || padded.length > MAX_BLOCK_SIZE
                || padded.length % PADDING_BLOCK_SIZE != 0) {
            throw new IOException("Invalid VLT3 text block size");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(padded));
        int encodedLength = input.readInt();
        if (encodedLength < 12 || encodedLength > padded.length - Integer.BYTES) {
            throw new IOException("Invalid VLT3 text payload length");
        }
        byte[] encoded = new byte[encodedLength];
        input.readFully(encoded);
        DataInputStream raw = new DataInputStream(new ByteArrayInputStream(encoded));
        if (raw.readInt() != MAGIC) {
            throw new IOException("Invalid VLT3 text data magic");
        }
        int schemaVersion = raw.readInt();
        if (schemaVersion != LEGACY_TEXT_DATA_SCHEMA_VERSION
                && schemaVersion != VaultV3Format.TEXT_DATA_SCHEMA_VERSION) {
            throw new IOException("Unsupported VLT3 text data schema");
        }
        int count = raw.readInt();
        if (count < 0 || count > MAX_ITEMS) {
            throw new IOException("Invalid VLT3 entry count");
        }
        List<VaultItem> items = new ArrayList<>(count);
        Set<String> entryIds = new HashSet<>();
        Set<String> resourceIds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String entryId = readHex(raw, 16);
            String type = typeName(raw.readUnsignedByte());
            String title = readString(raw);
            String username = readString(raw);
            String password = readString(raw);
            String url = readString(raw);
            String notes = readString(raw);
            String tags = readString(raw);
            String folder = readString(raw);
            String totp = readString(raw);
            boolean favorite = raw.readBoolean();
            String attachmentId = null;
            String thumbnailId = null;
            String contentType = "";
            long attachmentSize = 0;
            if (VaultItem.TYPE_IMAGE.equals(type) || VaultItem.TYPE_FILE.equals(type)) {
                attachmentId = readHex(raw, 32);
                attachmentSize = raw.readLong();
                contentType = readString(raw);
                if (VaultItem.TYPE_IMAGE.equals(type) && raw.readBoolean()) {
                    thumbnailId = readHex(raw, 32);
                }
            }
            List<String> passwordHistory = new ArrayList<>();
            if (schemaVersion >= PASSWORD_HISTORY_SCHEMA_VERSION) {
                int historyCount = raw.readInt();
                if (historyCount < 0
                        || historyCount > VaultItem.MAX_PASSWORD_HISTORY_ENTRIES) {
                    throw new IOException("Invalid VLT3 password history count");
                }
                for (int historyIndex = 0; historyIndex < historyCount; historyIndex++) {
                    passwordHistory.add(readString(raw));
                }
            }
            VaultItem item;
            try {
                item = new VaultItem(entryId, title, username, password, url, notes, tags,
                        folder, totp, favorite, type, attachmentId, thumbnailId,
                        contentType, attachmentSize, passwordHistory);
                validateItem(item, entryIds, resourceIds);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid VLT3 entry", exception);
            }
            items.add(item);
        }
        if (raw.available() != 0) {
            throw new IOException("Trailing VLT3 text data");
        }
        Crypto.wipe(encoded);
        return new VaultData(items);
    }

    private static void validateItem(VaultItem item, Set<String> entryIds,
                                     Set<String> resourceIds) throws IOException {
        if (item == null || !VaultItem.isHexId(item.entryId, 16)
                || !entryIds.add(item.entryId.toLowerCase(java.util.Locale.ROOT))) {
            throw new IOException("Invalid or duplicate VLT3 entry ID");
        }
        if (VaultItem.TYPE_LOGIN.equals(item.type)) {
            if (item.attachmentId != null || item.thumbnailId != null
                    || item.attachmentSize != 0 || !item.contentType.isEmpty()) {
                throw new IOException("Unexpected resource reference on text entry");
            }
        } else if (VaultItem.TYPE_IMAGE.equals(item.type)) {
            if (!VaultItem.isHexId(item.attachmentId, 32)
                    || item.attachmentSize < 1
                    || item.attachmentSize > 10L * 1024 * 1024
                    || !"image/jpeg".equals(item.contentType)) {
                throw new IOException("Invalid VLT3 image reference");
            }
            addResourceId(resourceIds, item.attachmentId);
            if (item.thumbnailId != null) {
                if (!VaultItem.isHexId(item.thumbnailId, 32)) {
                    throw new IOException("Invalid VLT3 thumbnail reference");
                }
                addResourceId(resourceIds, item.thumbnailId);
            }
        } else if (VaultItem.TYPE_FILE.equals(item.type)) {
            if (!VaultItem.isHexId(item.attachmentId, 32)
                    || item.attachmentSize < 0
                    || item.attachmentSize > 10L * 1024 * 1024
                    || item.contentType.isEmpty()
                    || item.contentType.length() > VaultObjectRef.MAX_CONTENT_TYPE_LENGTH
                    || item.thumbnailId != null) {
                throw new IOException("Invalid VLT3 file reference");
            }
            addResourceId(resourceIds, item.attachmentId);
        } else {
            throw new IOException("Unknown VLT3 entry type");
        }
        writeStringLengthCheck(item.title);
        writeStringLengthCheck(item.username);
        writeStringLengthCheck(item.password);
        writeStringLengthCheck(item.url);
        writeStringLengthCheck(item.notes);
        writeStringLengthCheck(item.tags);
        writeStringLengthCheck(item.folder);
        writeStringLengthCheck(item.totpSecret);
        writeStringLengthCheck(item.contentType);
        Set<String> historyPasswords = new HashSet<>();
        for (String previousPassword : item.passwordHistory) {
            if (!VaultItem.TYPE_LOGIN.equals(item.type) || previousPassword.isEmpty()
                    || previousPassword.equals(item.password)
                    || !historyPasswords.add(previousPassword)) {
                throw new IOException("Invalid VLT3 password history");
            }
            writeStringLengthCheck(previousPassword);
        }
    }

    private static void addResourceId(Set<String> ids, String id) throws IOException {
        if (!ids.add(id.toLowerCase(java.util.Locale.ROOT))) {
            throw new IOException("Duplicate VLT3 resource reference");
        }
    }

    private static int typeId(String type) throws IOException {
        if (VaultItem.TYPE_LOGIN.equals(type)) {
            return 1;
        }
        if (VaultItem.TYPE_IMAGE.equals(type)) {
            return 2;
        }
        if (VaultItem.TYPE_FILE.equals(type)) {
            return 3;
        }
        throw new IOException("Unknown VLT3 entry type");
    }

    private static String typeName(int type) throws IOException {
        switch (type) {
            case 1:
                return VaultItem.TYPE_LOGIN;
            case 2:
                return VaultItem.TYPE_IMAGE;
            case 3:
                return VaultItem.TYPE_FILE;
            default:
                throw new IOException("Unknown VLT3 entry type");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD_LENGTH) {
            throw new VaultSizeLimitException("VLT3 text field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeStringLengthCheck(String value) throws IOException {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_FIELD_LENGTH) {
            throw new VaultSizeLimitException("VLT3 text field is too large");
        }
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_FIELD_LENGTH) {
            throw new IOException("Invalid VLT3 text field length");
        }
        byte[] bytes = new byte[length];
        try {
            input.readFully(bytes);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (EOFException | CharacterCodingException exception) {
            throw new IOException("Invalid or truncated VLT3 text field", exception);
        } finally {
            Crypto.wipe(bytes);
        }
    }

    private static void writeFixedHex(DataOutputStream output, String value, int byteLength)
            throws IOException {
        if (!VaultItem.isHexId(value, byteLength)) {
            throw new IOException("Invalid VLT3 object reference ID");
        }
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            output.writeByte((high << 4) | low);
        }
    }

    private static String readHex(DataInputStream input, int byteLength) throws IOException {
        byte[] id = new byte[byteLength];
        input.readFully(id);
        StringBuilder output = new StringBuilder(byteLength * 2);
        for (byte item : id) {
            output.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(item & 0x0f, 16));
        }
        Crypto.wipe(id);
        return output.toString();
    }

    private static int roundUp(int value, int blockSize) throws IOException {
        long rounded = ((long) value + blockSize - 1) / blockSize * blockSize;
        if (rounded > MAX_BLOCK_SIZE) {
            throw new VaultSizeLimitException("VLT3 text block exceeds 32 MiB");
        }
        return (int) rounded;
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;

        BoundedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            if (value == null || offset < 0 || length < 0
                    || length > value.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacity(length);
            bytes.write(value, offset, length);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }

        private void ensureCapacity(int additional) throws IOException {
            if (additional > limit - bytes.size()) {
                throw new VaultSizeLimitException("VLT3 text block exceeds 32 MiB");
            }
        }
    }
}
