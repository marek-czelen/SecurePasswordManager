package com.cretorix.securepasswordmanager.repository;

import com.cretorix.securepasswordmanager.crypto.VaultData;
import com.cretorix.securepasswordmanager.crypto.VaultItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VaultDataMergeTest {
    @Test
    public void mergesRemoteUpdatesAndAdditionsWhilePreservingLocalOnlyEntries() {
        VaultItem localOnly = login("00000000000000000000000000000001", "Local only", "one");
        VaultItem oldShared = login("00000000000000000000000000000002", "Shared", "old");
        VaultItem newShared = login("00000000000000000000000000000002", "Shared", "new");
        VaultItem remoteOnly = login("00000000000000000000000000000003", "Remote only", "three");

        VaultV3Repository.ItemMergeResult merged = VaultV3Repository.mergeRemoteItems(
                new VaultData(Arrays.asList(localOnly, oldShared)),
                new VaultData(Arrays.asList(newShared, remoteOnly)));

        assertTrue(merged.changed);
        assertEquals(3, merged.data.items.size());
        assertEquals(localOnly, merged.data.items.get(0));
        assertEquals(newShared, merged.data.items.get(1));
        assertEquals(remoteOnly, merged.data.items.get(2));
        assertFalse(VaultV3Repository.sameVaultData(merged.data,
                new VaultData(Arrays.asList(newShared, remoteOnly))));
    }

    @Test
    public void identicalRemoteEntriesDoNotMarkLocalDataChanged() {
        VaultItem item = login("00000000000000000000000000000001", "Example", "secret");
        VaultData local = new VaultData(Collections.singletonList(item));
        VaultV3Repository.ItemMergeResult merged = VaultV3Repository.mergeRemoteItems(
                local, new VaultData(Collections.singletonList(item)));

        assertFalse(merged.changed);
        assertTrue(VaultV3Repository.sameVaultData(local, merged.data));
    }

    private static VaultItem login(String id, String title, String password) {
        return new VaultItem(id, title, "user", password, "", "", "", "", "",
                false, VaultItem.TYPE_LOGIN, null, null, "", 0);
    }
}
