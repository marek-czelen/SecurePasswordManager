package com.cretorix.securepasswordmanager;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.KdfParameters;
import com.cretorix.securepasswordmanager.crypto.VaultHeaderStore;
import com.cretorix.securepasswordmanager.crypto.VaultHeaderV3;
import com.cretorix.securepasswordmanager.crypto.VaultV3KeyDerivation;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;

@RunWith(AndroidJUnit4.class)
public final class VaultHeaderStoreTest {
    @Test
    public void atomicallyReplacesAndReadsV3Header() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = new File(context.getCacheDir(), "vlt3-header-store-test.bin");
        File backup = new File(file.getPath() + ".bak");
        File newer = new File(file.getPath() + ".new");
        file.delete();
        backup.delete();
        newer.delete();

        VaultHeaderStore store = new VaultHeaderStore(file);
        VaultHeaderV3 first = createHeader();
        store.write(first);
        assertArrayEquals(first.vaultId, store.read().vaultId);

        VaultHeaderV3 second = createHeader();
        store.write(second);
        assertArrayEquals(second.vaultId, store.read().vaultId);

        file.delete();
        backup.delete();
        newer.delete();
    }

    private static VaultHeaderV3 createHeader() throws Exception {
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] qrSecret = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] vaultId = Crypto.randomBytes(VaultHeaderV3.VAULT_ID_SIZE);
        byte[] wrapSalt = Crypto.randomBytes(VaultHeaderV3.WRAP_SALT_SIZE);
        byte[] qrId = Crypto.sha256(qrSecret);
        byte[] hardwareKeyId = Crypto.randomBytes(VaultHeaderV3.HARDWARE_KEY_ID_SIZE);
        byte[] generation = Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE);
        KdfParameters parameters = new KdfParameters(new byte[16], 8 * 1024, 1, 1);
        Crypto.SealedBox pinWrapper = VaultV3KeyDerivation.wrapDek(dek,
                "test passphrase".toCharArray(), qrSecret, vaultId, parameters, wrapSalt,
                qrId, hardwareKeyId, generation);
        VaultHeaderV3 result = new VaultHeaderV3(vaultId, parameters, wrapSalt, qrId,
                hardwareKeyId, generation, pinWrapper, new byte[]{2, 1, 2});
        Crypto.wipe(dek);
        Crypto.wipe(qrSecret);
        return result;
    }
}
