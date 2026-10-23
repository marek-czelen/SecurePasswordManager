package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.VaultObjectIds;
import com.cretorix.securepasswordmanager.crypto.VaultObjectRef;
import com.cretorix.securepasswordmanager.crypto.VaultObjectStore;

import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class VaultObjectStoreTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void objectIsImmutableAuthenticatedAndAddressedByOpaqueId() throws Exception {
        File directory = temporaryFolder.newFolder("objects");
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] ownerId = Crypto.randomBytes(16);
        byte[] image = Crypto.randomBytes(4096);
        VaultObjectStore store = new VaultObjectStore(directory, vaultId, ignored -> { });

        VaultObjectRef first = store.write(dek, VaultObjectRef.Type.IMAGE, ownerId,
                "image/jpeg", image);
        assertArrayEquals(image, store.read(first, dek));
        assertTrue(store.fileFor(first.objectId).getName().matches("[0-9a-f]{64}\\.obj"));

        VaultObjectRef second = store.write(dek, VaultObjectRef.Type.IMAGE, ownerId,
                "image/jpeg", image);
        assertNotEquals(hex(first.objectId), hex(second.objectId));
        assertThrows(Exception.class, () -> store.writeWithId(dek, first.objectId,
                VaultObjectRef.Type.IMAGE, ownerId, "image/jpeg", image));

        File object = store.fileFor(first.objectId);
        try (RandomAccessFile file = new RandomAccessFile(object, "rw")) {
            file.seek(file.length() - 1);
            int value = file.read();
            file.seek(file.length() - 1);
            file.write(value ^ 0xff);
        }
        assertThrows(Exception.class, () -> store.read(first, dek));

        store.delete(first);
        assertFalse(object.exists());
        Crypto.wipe(dek);
        Crypto.wipe(image);
    }

    @Test
    public void rejectsOversizedAndInvalidObjectData() throws Exception {
        File directory = temporaryFolder.newFolder("limits");
        VaultObjectStore store = new VaultObjectStore(directory, Crypto.randomBytes(16),
                ignored -> { });
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        assertThrows(Exception.class, () -> store.write(dek, VaultObjectRef.Type.IMAGE,
                Crypto.randomBytes(16), "image/jpeg", new byte[10 * 1024 * 1024 + 1]));
        assertThrows(Exception.class, () -> store.write(dek, VaultObjectRef.Type.TEXT_BLOCK,
                Crypto.randomBytes(16), "", new byte[]{1}));
        Crypto.wipe(dek);
    }

    @Test
    public void garbageCollectionKeepsReachableObjectsAndRemovesOnlyOrphans() throws Exception {
        File directory = temporaryFolder.newFolder("gc");
        VaultObjectStore store = new VaultObjectStore(directory, Crypto.randomBytes(16),
                ignored -> { });
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] owner = Crypto.randomBytes(16);
        VaultObjectRef retained = store.write(dek, VaultObjectRef.Type.FILE, owner,
                "application/octet-stream", new byte[]{1, 2, 3});
        VaultObjectRef orphan = store.write(dek, VaultObjectRef.Type.FILE, owner,
                "application/octet-stream", new byte[]{4, 5, 6});
        File staging = new File(directory, ".unfinished.tmp-test");
        assertTrue(staging.createNewFile());
        Set<String> reachable = new HashSet<>();
        reachable.add(VaultObjectIds.toHex(retained.objectId));

        assertEquals(2, store.collectUnreferenced(reachable));
        assertTrue(store.fileFor(retained.objectId).isFile());
        assertFalse(store.fileFor(orphan.objectId).exists());
        assertFalse(staging.exists());
        Crypto.wipe(dek);
        Crypto.wipe(owner);
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(item & 0x0f, 16));
        }
        return output.toString();
    }
}
