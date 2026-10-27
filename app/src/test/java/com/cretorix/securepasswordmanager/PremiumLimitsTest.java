package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PremiumLimitsTest {
    @Test
    public void freeAllowsOneFileAndDocumentWhileProAllowsMore() {
        assertTrue(PremiumLimits.canAddFile(false, 0));
        assertFalse(PremiumLimits.canAddFile(false, 1));
        assertTrue(PremiumLimits.canAddFile(true, 1));
        assertTrue(PremiumLimits.canAddFile(true, 20));

        assertTrue(PremiumLimits.canAddDocument(false, 0));
        assertFalse(PremiumLimits.canAddDocument(false, 1));
        assertTrue(PremiumLimits.canAddDocument(true, 1));
        assertTrue(PremiumLimits.canAddDocument(true, 20));
    }

    @Test
    public void freeHistoryIsLimitedToTwoAndProKeepsTheFormatLimit() {
        assertEquals(2, PremiumLimits.passwordHistoryLimit(false));
        assertEquals(VaultItem.MAX_PASSWORD_HISTORY_ENTRIES,
                PremiumLimits.passwordHistoryLimit(true));
    }

    @Test
    public void freeImportAndExportAreCappedPerOperation() {
        List<Integer> entries = Arrays.asList(1, 2, 3, 4, 5, 6, 7);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5),
                PremiumLimits.limitImportExportEntries(entries, false));
        assertEquals(entries, PremiumLimits.limitImportExportEntries(entries, true));
    }

    @Test
    public void csvCapCountsOnlyPasswordEntries() {
        List<VaultItem> items = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            items.add(new VaultItem("Login " + index, "user", "password", "", "", ""));
            if (index == 0) {
                items.add(VaultItem.imageReference("Image", repeat("ab", 32),
                        repeat("cd", 32), 1));
            }
        }

        assertEquals(5, PremiumLimits.csvExportEntries(items, false).size());
        assertEquals(7, PremiumLimits.csvExportEntries(items, true).size());
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
