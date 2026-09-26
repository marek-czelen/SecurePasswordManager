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
        raw.writeInt(0x56444232);
        raw.writeInt(data.items.size());
        for (VaultItem item : data.items) {
            writeString(raw, item.title);
            writeString(raw, item.username);
            writeString(raw, item.password);
            writeString(raw, item.url);
            writeString(raw, item.notes);
            writeString(raw, item.tags);
            writeString(raw, item.folder);
            writeString(raw, item.totpSecret);
            raw.writeBoolean(item.favorite);
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
        if (dataVersion != 0x56444231 && dataVersion != 0x56444232) {
            throw new IOException("Invalid vault payload");
        }
        int count = raw.readInt();
        if (count < 0 || count > MAX_ITEMS) {
            throw new IOException("Invalid item count");
        }
        List<VaultItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String title = readString(raw);
            String username = readString(raw);
            String password = readString(raw);
            String url = readString(raw);
            String notes = readString(raw);
            String tags = readString(raw);
            if (dataVersion == 0x56444231) {
                items.add(new VaultItem(title, username, password, url, notes, tags));
            } else {
                items.add(new VaultItem(title, username, password, url, notes, tags,
                        readString(raw), readString(raw), raw.readBoolean()));
            }
        }
        if (raw.available() != 0) {
            throw new IOException("Trailing vault data");
        }
        return new VaultData(items);
    }

    public static int paddedLength(VaultData data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x56444232);
        output.writeInt(data.items.size());
        for (VaultItem item : data.items) {
            writeString(output, item.title);
            writeString(output, item.username);
            writeString(output, item.password);
            writeString(output, item.url);
            writeString(output, item.notes);
            writeString(output, item.tags);
            writeString(output, item.folder);
            writeString(output, item.totpSecret);
            output.writeBoolean(item.favorite);
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
