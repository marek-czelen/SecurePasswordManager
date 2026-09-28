package com.example.securepasswordmanager;

import com.example.securepasswordmanager.crypto.Crypto;
import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultItem;
import com.example.securepasswordmanager.crypto.VaultTextDataCodec;

import org.junit.Test;

import java.io.IOException;
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
                true, VaultItem.TYPE_LOGIN, null, null, "", 0);
        VaultItem image = VaultItem.imageReference("ID card", repeat("ab", 32),
                repeat("cd", 32), 120_000);
        VaultItem file = VaultItem.fileReference("Document", repeat("ef", 32),
                "application/pdf", 0);
        VaultData decoded = VaultTextDataCodec.decode(VaultTextDataCodec.encode(
                new VaultData(Arrays.asList(login, image, file))));

        assertEquals(3, decoded.items.size());
        assertEquals(entryId, decoded.items.get(0).entryId);
        assertEquals("secret", decoded.items.get(0).password);
        assertEquals("Work", decoded.items.get(0).folder);
        assertEquals("JBSWY3DPEHPK3PXP", decoded.items.get(0).totpSecret);
        assertEquals(repeat("ab", 32), decoded.items.get(1).attachmentId);
        assertEquals(repeat("cd", 32), decoded.items.get(1).thumbnailId);
        assertEquals(VaultItem.TYPE_FILE, decoded.items.get(2).type);
        assertEquals("application/pdf", decoded.items.get(2).contentType);
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
    }

    @Test
    public void rejectsUnsupportedVersionTruncationAndEntryCountOverflow() throws Exception {
        byte[] encoded = VaultTextDataCodec.encode(VaultData.empty());
        encoded[11] ^= 1;
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
}
