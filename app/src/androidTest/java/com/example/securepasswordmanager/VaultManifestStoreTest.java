package com.example.securepasswordmanager;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.securepasswordmanager.crypto.VaultManifestStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class VaultManifestStoreTest {
    @Test
    public void commitKeepsPreviousUntilCurrentIsReplaced() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "vlt3-manifest-store-test");
        if (!directory.isDirectory()) {
            assertTrue(directory.mkdirs());
        }
        VaultManifestStore store = new VaultManifestStore(directory);
        store.deleteAll();
        byte[] first = new byte[]{1, 2, 3};
        byte[] second = new byte[]{4, 5, 6};
        store.writeInitial(first);
        store.commit(second);
        assertArrayEquals(second, store.readCurrent());
        assertArrayEquals(first, store.readPrevious());

        store.recoverCurrentFromPrevious();
        assertArrayEquals(first, store.readCurrent());
        store.deleteAll();
        assertTrue(directory.delete());
    }
}
