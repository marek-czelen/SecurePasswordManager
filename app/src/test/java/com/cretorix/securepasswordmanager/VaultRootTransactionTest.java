package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.repository.VaultRootTransaction;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class VaultRootTransactionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rootSwitchHasARecoverableCommitBoundary() throws Exception {
        File parent = temporaryFolder.newFolder("switch");
        File current = new File(parent, "vault");
        File previous = new File(parent, "vault.previous");
        File staging = new File(parent, "vault.import");
        write(current, "old");
        write(staging, "new");

        assertThrows(IOException.class, () -> VaultRootTransaction.install(
                current, previous, staging, new FailOnSync(2)));
        assertEquals("new", read(current));
        assertEquals("old", read(previous));
        assertFalse(staging.exists());

        assertTrue(Files.deleteIfExists(new File(current, "state").toPath()));
        assertTrue(current.delete());
        VaultRootTransaction.recover(current, previous, ignored -> { });
        assertEquals("old", read(current));
        assertFalse(previous.exists());
    }

    @Test
    public void failedFirstRenameSyncRestoresOldRoot() throws Exception {
        File parent = temporaryFolder.newFolder("rollback");
        File current = new File(parent, "vault");
        File previous = new File(parent, "vault.previous");
        File staging = new File(parent, "vault.import");
        write(current, "old");
        write(staging, "new");

        assertThrows(IOException.class, () -> VaultRootTransaction.install(
                current, previous, staging, new FailOnSync(1)));
        assertEquals("old", read(current));
        assertTrue(staging.isDirectory());
        assertFalse(previous.exists());
    }

    private static void write(File directory, String value) throws IOException {
        assertTrue(directory.mkdirs());
        Files.write(new File(directory, "state").toPath(), value.getBytes("UTF-8"));
    }

    private static String read(File directory) throws IOException {
        return new String(Files.readAllBytes(new File(directory, "state").toPath()), "UTF-8");
    }

    private static final class FailOnSync implements VaultRootTransaction.DirectorySync {
        private final int failureCall;
        private int calls;

        FailOnSync(int failureCall) {
            this.failureCall = failureCall;
        }

        @Override
        public void sync(File directory) throws IOException {
            calls++;
            if (calls == failureCall) {
                throw new IOException("Injected directory sync failure");
            }
        }
    }
}
