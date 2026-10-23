package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.VaultData;
import com.cretorix.securepasswordmanager.crypto.VaultItem;
import com.cretorix.securepasswordmanager.crypto.VaultTextDataCodec;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class VaultTextDataCodecTest {
    @Test
    public void textAndMediaReferencesRoundTripWithoutInlineBytes() throws Exception {
        String entryId = repeat("01", 16);
        VaultItem login = new VaultItem(entryId, "Example", "user", "secret", "https://site",
                "private note", "work,important", "Work", "JBSWY3DPEHPK3PXP",
                true, VaultItem.TYPE_LOGIN, null, null, "", 0,
                Arrays.asList("old-secret", "older-secret"));
        VaultItem image = VaultItem.imageReference("ID card", repeat("ab", 32),
                repeat("cd", 32), 120_000);
        VaultItem file = VaultItem.fileReference("Document", repeat("ef", 32),
                "application/pdf", 0);
        VaultItem document = VaultItem.documentReference("Passport",
                Arrays.asList(repeat("12", 32), repeat("34", 32)));
        VaultData decoded = VaultTextDataCodec.decode(VaultTextDataCodec.encode(
                new VaultData(Arrays.asList(login, image, file, document))));

        assertEquals(4, decoded.items.size());
        assertEquals(entryId, decoded.items.get(0).entryId);
        assertEquals("secret", decoded.items.get(0).password);
        assertEquals("Work", decoded.items.get(0).folder);
        assertEquals("JBSWY3DPEHPK3PXP", decoded.items.get(0).totpSecret);
        assertEquals(Arrays.asList("old-secret", "older-secret"),
                decoded.items.get(0).passwordHistory);
        assertEquals(repeat("ab", 32), decoded.items.get(1).attachmentId);
        assertEquals(repeat("cd", 32), decoded.items.get(1).thumbnailId);
        assertEquals(VaultItem.TYPE_FILE, decoded.items.get(2).type);
        assertEquals("application/pdf", decoded.items.get(2).contentType);
        assertEquals(VaultItem.TYPE_DOCUMENT, decoded.items.get(3).type);
        assertEquals(Arrays.asList(repeat("12", 32), repeat("34", 32)),
                decoded.items.get(3).documentPageIds);
    }

    @Test
    public void decodesTextSchemaOneWithoutPasswordHistory() throws Exception {
        byte[] legacy = legacySchemaOneLoginBlock();
        VaultData decoded = VaultTextDataCodec.decode(legacy);

        assertEquals(1, decoded.items.size());
        assertEquals("legacy-secret", decoded.items.get(0).password);
        assertTrue(decoded.items.get(0).passwordHistory.isEmpty());
        Crypto.wipe(legacy);
    }

    @Test
    public void decodesTextSchemaTwoWithPasswordHistory() throws Exception {
        byte[] legacy = legacySchemaTwoLoginBlock();
        VaultData decoded = VaultTextDataCodec.decode(legacy);

        assertEquals(Collections.singletonList("legacy-old"),
                decoded.items.get(0).passwordHistory);
        Crypto.wipe(legacy);
    }

    @Test
    public void keepsFiveDistinctPreviousPasswordsNewestFirst() {
        VaultItem item = new VaultItem("Example", "user", "current", "", "", "");
        for (int i = 1; i <= 6; i++) {
            item = item.withPreviousPassword("password-" + i);
        }
        assertEquals(Arrays.asList("password-6", "password-5", "password-4",
                "password-3", "password-2"), item.passwordHistory);

        item = item.withPreviousPassword("password-4");
        assertEquals(Arrays.asList("password-4", "password-6", "password-5",
                "password-3", "password-2"), item.passwordHistory);
        assertEquals(item.passwordHistory, item.withFavorite(true).passwordHistory);
    }

    @Test
    public void removesNewCurrentPasswordAndRecordsPreviousOnlyWhenEnabled() {
        VaultItem item = new VaultItem(repeat("02", 16), "Example", "user", "current",
                "", "", "", "", "", false, VaultItem.TYPE_LOGIN,
                null, null, "", 0, Arrays.asList("older", "oldest"));

        assertEquals(Arrays.asList("current", "oldest"),
                item.passwordHistoryAfterChange("older", true));
        assertEquals(Collections.singletonList("oldest"),
                item.passwordHistoryAfterChange("older", false));
    }

    @Test
    public void rejectsInlineMediaDuplicateIdsAndInvalidReferences() {
        VaultItem missingImageRef = new VaultItem(repeat("44", 16), "no-image-ref",
                "", "", "", "", "", "", "", false, VaultItem.TYPE_IMAGE,
                null, null, "image/jpeg", 3);
        assertThrows(IOException.class, () -> VaultTextDataCodec.encode(
                new VaultData(Collections.singletonList(missingImageRef))));

        VaultItem duplicateId = new VaultItem(repeat("11", 16), "one", "", "", "", "",
                "", "", "", false, VaultItem.TYPE_LOGIN, null, null, "", 0);
        VaultItem duplicateIdAgain = new VaultItem(repeat("11", 16), "two", "", "", "", "",
                "", "", "", false, VaultItem.TYPE_LOGIN, null, null, "", 0);
        assertThrows(IOException.class, () -> VaultTextDataCodec.encode(
                new VaultData(Arrays.asList(duplicateId, duplicateIdAgain))));

        VaultItem duplicateResource = VaultItem.imageReference("bad", repeat("22", 32),
                repeat("22", 32), 1);
        assertThrows(IOException.class, () -> VaultTextDataCodec.encode(
                new VaultData(Collections.singletonList(duplicateResource))));

        VaultItem missingRef = new VaultItem(repeat("33", 16), "bad", "", "", "", "", "",
                "", "", false, VaultItem.TYPE_IMAGE, null, null, "image/jpeg", 12);
        assertThrows(IOException.class, () -> VaultTextDataCodec.encode(
                new VaultData(Collections.singletonList(missingRef))));

        VaultItem duplicateDocumentPages = VaultItem.documentReference("bad",
                Arrays.asList(repeat("55", 32), repeat("55", 32)));
        assertThrows(IOException.class, () -> VaultTextDataCodec.encode(
                new VaultData(Collections.singletonList(duplicateDocumentPages))));
    }

    @Test
    public void rejectsUnsupportedVersionTruncationAndEntryCountOverflow() throws Exception {
        byte[] encoded = VaultTextDataCodec.encode(VaultData.empty());
        encoded[11] = 99;
        assertThrows(IOException.class, () -> VaultTextDataCodec.decode(encoded));
        assertThrows(IOException.class,
                () -> VaultTextDataCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)));

        List<VaultItem> items = new ArrayList<>(VaultTextDataCodec.MAX_ITEMS + 1);
        for (int i = 0; i <= VaultTextDataCodec.MAX_ITEMS; i++) {
            items.add(new VaultItem("entry " + i, "", "", "", "", ""));
        }
        assertThrows(IOException.class, () -> VaultTextDataCodec.encode(new VaultData(items)));
    }

    @Test
    public void rejectsFieldsLargerThanTheDocumentedLimit() {
        String oversized = repeat("n", VaultTextDataCodec.MAX_FIELD_LENGTH + 1);
        VaultItem item = new VaultItem("title", "", "", "", oversized, "");
        assertThrows(IOException.class,
                () -> VaultTextDataCodec.encode(new VaultData(Collections.singletonList(item))));
    }

    @Test
    public void rejectsAggregateTextBlockOver32Mib() {
        char[] chars = new char[VaultTextDataCodec.MAX_FIELD_LENGTH];
        Arrays.fill(chars, 'n');
        String note = new String(chars);
        List<VaultItem> items = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            items.add(new VaultItem("entry " + i, "", "", "", note, ""));
        }
        assertThrows(IOException.class,
                () -> VaultTextDataCodec.encode(new VaultData(items)));
        Arrays.fill(chars, '\0');
    }

    @Test
    public void encodesAndDecodesTenThousandEntriesWithinTheConfiguredBlockLimit()
            throws Exception {
        List<VaultItem> items = new ArrayList<>(VaultTextDataCodec.MAX_ITEMS);
        for (int i = 0; i < VaultTextDataCodec.MAX_ITEMS; i++) {
            items.add(new VaultItem("service-" + i, "user-" + i, "secret-" + i,
                    "https://example.test/" + i, "notes", "tag", "folder", "", i % 2 == 0));
        }
        long started = System.nanoTime();
        byte[] encoded = VaultTextDataCodec.encode(new VaultData(items));
        VaultData decoded = VaultTextDataCodec.decode(encoded);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(VaultTextDataCodec.MAX_ITEMS, decoded.items.size());
        assertTrue(encoded.length <= VaultTextDataCodec.MAX_BLOCK_SIZE);
        assertTrue("10,000-entry codec should complete in a bounded time", elapsedMillis < 30_000);
        Crypto.wipe(encoded);
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            output.append(value);
        }
        return output.toString();
    }

    private static byte[] legacySchemaOneLoginBlock() throws IOException {
        ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        DataOutputStream raw = new DataOutputStream(rawBytes);
        raw.writeInt(0x56544433);
        raw.writeInt(1);
        raw.writeInt(1);
        writeHex(raw, repeat("01", 16));
        raw.writeByte(1);
        writeString(raw, "Old entry");
        writeString(raw, "old-user");
        writeString(raw, "legacy-secret");
        writeString(raw, "");
        writeString(raw, "");
        writeString(raw, "");
        writeString(raw, "");
        writeString(raw, "");
        raw.writeBoolean(false);
        raw.flush();

        byte[] record = rawBytes.toByteArray();
        ByteArrayOutputStream paddedBytes = new ByteArrayOutputStream(4096);
        DataOutputStream padded = new DataOutputStream(paddedBytes);
        padded.writeInt(record.length);
        padded.write(record);
        padded.write(new byte[4096 - Integer.BYTES - record.length]);
        padded.flush();
        return paddedBytes.toByteArray();
    }

    private static byte[] legacySchemaTwoLoginBlock() throws IOException {
        ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        DataOutputStream raw = new DataOutputStream(rawBytes);
        raw.writeInt(0x56544433);
        raw.writeInt(2);
        raw.writeInt(1);
        writeHex(raw, repeat("02", 16));
        raw.writeByte(1);
        writeString(raw, "Old entry");
        writeString(raw, "old-user");
        writeString(raw, "current-secret");
        writeString(raw, "");
        writeString(raw, "");
        writeString(raw, "");
        writeString(raw, "");
        writeString(raw, "");
        raw.writeBoolean(false);
        raw.writeInt(1);
        writeString(raw, "legacy-old");
        raw.flush();

        byte[] record = rawBytes.toByteArray();
        ByteArrayOutputStream paddedBytes = new ByteArrayOutputStream(4096);
        DataOutputStream padded = new DataOutputStream(paddedBytes);
        padded.writeInt(record.length);
        padded.write(record);
        padded.write(new byte[4096 - Integer.BYTES - record.length]);
        padded.flush();
        return paddedBytes.toByteArray();
    }

    private static void writeHex(DataOutputStream output, String value) throws IOException {
        for (int i = 0; i < value.length(); i += 2) {
            output.writeByte((Character.digit(value.charAt(i), 16) << 4)
                    | Character.digit(value.charAt(i + 1), 16));
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
