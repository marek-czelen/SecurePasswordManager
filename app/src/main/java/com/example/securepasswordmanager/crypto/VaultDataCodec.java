package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Length-bounded binary serialization for encrypted entry data.
 *
 * <p>VDB2 adds favorite/folder/TOTP fields while decoding VDB1 with safe defaults.
 * Padding is applied before encryption so small changes do not expose exact plaintext
 * database length.</p>
 */
public final class VaultDataCodec {
    private static final int BLOCK_SIZE = 4096;
    private static final int MAX_ITEMS = 10_000;
    private static final int MAX_FIELD_LENGTH = 256 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultDataCodec() {
    }

    public static byte[] encode(VaultData data, int targetLength) throws IOException {
        ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        DataOutputStream raw = new DataOutputStream(rawBytes);
        raw.writeInt(0x56444234);
        raw.writeInt(data.items.size());
        for (VaultItem item : data.items) {
            writeString(raw, item.type);
            writeString(raw, item.title);
            writeString(raw, item.username);
            writeString(raw, item.password);
            writeString(raw, item.url);
            writeString(raw, item.notes);
            writeString(raw, item.tags);
            writeString(raw, item.folder);
            writeString(raw, item.totpSecret);
            raw.writeBoolean(item.favorite);
            if (item.isImage()) {
                if (item.imageJpeg == null || item.imageJpeg.length > 8 * 1024 * 1024) {
                    throw new IOException("Invalid image entry");
                }
                raw.writeInt(item.imageJpeg.length);
                raw.write(item.imageJpeg);
            }
        }
        raw.flush();
        byte[] encoded = rawBytes.toByteArray();
        int required = encoded.length + Integer.BYTES;
        int paddedLength = Math.max(required, targetLength);
        paddedLength = roundUp(paddedLength, BLOCK_SIZE);
        ByteArrayOutputStream paddedBytes = new ByteArrayOutputStream(paddedLength);
        DataOutputStream padded = new DataOutputStream(paddedBytes);
        padded.writeInt(encoded.length);
        padded.write(encoded);
        byte[] padding = new byte[paddedLength - required];
        RANDOM.nextBytes(padding);
        padded.write(padding);
        padded.flush();
        return paddedBytes.toByteArray();
    }

    public static VaultData decode(byte[] padded) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(padded));
        int encodedLength = input.readInt();
        if (encodedLength < Integer.BYTES || encodedLength > padded.length - Integer.BYTES) {
            throw new IOException("Invalid vault payload length");
        }
        byte[] encoded = new byte[encodedLength];
        input.readFully(encoded);
        DataInputStream raw = new DataInputStream(new ByteArrayInputStream(encoded));
        int dataVersion = raw.readInt();
        if (dataVersion != 0x56444231 && dataVersion != 0x56444232
                && dataVersion != 0x56444233 && dataVersion != 0x56444234) {
            throw new IOException("Invalid vault payload");
        }
        int count = raw.readInt();
        if (count < 0 || count > MAX_ITEMS) {
            throw new IOException("Invalid item count");
        }
        List<VaultItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String type = dataVersion == 0x56444234 ? readString(raw) : VaultItem.TYPE_LOGIN;
            String title = readString(raw);
            String username = readString(raw);
            String password = readString(raw);
            String url = readString(raw);
            String notes = readString(raw);
            String tags = readString(raw);
            if (dataVersion == 0x56444231) {
                items.add(new VaultItem(title, username, password, url, notes, tags));
            } else {
                String folder = readString(raw);
                String totp = readString(raw);
                boolean favorite = raw.readBoolean();
                if (dataVersion == 0x56444234 && VaultItem.TYPE_IMAGE.equals(type)) {
                    int length = raw.readInt();
                    if (length < 1 || length > 8 * 1024 * 1024) {
                        throw new IOException("Invalid image entry length");
                    }
                    byte[] jpeg = new byte[length];
                    raw.readFully(jpeg);
                    items.add(VaultItem.image(title, jpeg));
                } else {
                    items.add(new VaultItem(title, username, password, url, notes, tags,
                            folder, totp, favorite));
                }
            }
        }
        List<VaultImage> images = new ArrayList<>();
        if (dataVersion == 0x56444233) {
            int imageCount = raw.readInt();
            if (imageCount < 0 || imageCount > 256) {
                throw new IOException("Invalid image count");
            }
            for (int i = 0; i < imageCount; i++) {
                String id = readString(raw);
                long createdAt = raw.readLong();
                int length = raw.readInt();
                if (length < 1 || length > 8 * 1024 * 1024) {
                    throw new IOException("Invalid image length");
                }
                byte[] jpeg = new byte[length];
                raw.readFully(jpeg);
                images.add(new VaultImage(id, jpeg, createdAt));
            }
        }
        if (raw.available() != 0) {
            throw new IOException("Trailing vault data");
        }
        if (!images.isEmpty()) {
            for (int i = 0; i < images.size(); i++) {
                VaultImage image = images.get(i);
                items.add(VaultItem.image("Obraz " + (i + 1), image.jpeg));
            }
        }
        return new VaultData(items);
    }

    public static int paddedLength(VaultData data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x56444234);
        output.writeInt(data.items.size());
        for (VaultItem item : data.items) {
            writeString(output, item.type);
            writeString(output, item.title);
            writeString(output, item.username);
            writeString(output, item.password);
            writeString(output, item.url);
            writeString(output, item.notes);
            writeString(output, item.tags);
            writeString(output, item.folder);
            writeString(output, item.totpSecret);
            output.writeBoolean(item.favorite);
            if (item.isImage()) {
                if (item.imageJpeg == null || item.imageJpeg.length > 8 * 1024 * 1024) {
                    throw new IOException("Invalid image entry");
                }
                output.writeInt(item.imageJpeg.length);
                output.write(item.imageJpeg);
            }
        }
        output.flush();
        return roundUp(bytes.size() + Integer.BYTES, BLOCK_SIZE);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD_LENGTH) {
            throw new IOException("Vault field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_FIELD_LENGTH) {
            throw new IOException("Invalid vault field length");
        }
        byte[] bytes = new byte[length];
        try {
            input.readFully(bytes);
        } catch (EOFException exception) {
            throw new IOException("Truncated vault field", exception);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int roundUp(int value, int block) {
        long rounded = ((long) value + block - 1L) / block * block;
        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Vault is too large");
        }
        return (int) rounded;
    }
}
