package com.example.securepasswordmanager;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.securepasswordmanager.crypto.Crypto;
import com.example.securepasswordmanager.crypto.PassphraseQrPayload;
import com.example.securepasswordmanager.crypto.PendingVaultAsset;
import com.example.securepasswordmanager.crypto.RecoveryQrPayload;
import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultItem;
import com.example.securepasswordmanager.crypto.VaultObjectIds;
import com.example.securepasswordmanager.crypto.VaultObjectRef;
import com.example.securepasswordmanager.repository.VaultV3Repository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class VaultV3RepositoryTest {
    @Test
    public void textUpdatePreservesMediaAndBackupRestoresCompleteVault() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String testId = UUID.randomUUID().toString();
        File firstRoot = new File(context.getCacheDir(), "vlt3-test-" + testId);
        File secondRoot = new File(context.getCacheDir(), "vlt3-restore-test-" + testId);
        VaultV3Repository source = new VaultV3Repository(context, firstRoot,
                "vlt3-test-prefs-" + testId);
        VaultV3Repository restored = null;
        char[] passphrase = "first secure passphrase".toCharArray();
        char[] nextPassphrase = "second secure passphrase".toCharArray();
        byte[] imageBytes = Crypto.randomBytes(4096);
        byte[] thumbnailBytes = Crypto.randomBytes(256);
        byte[] replacementImageBytes = Crypto.randomBytes(2048);
        RecoveryQrPayload recovery = null;
        byte[] dek = null;
        try {
            source.create(passphrase);
            recovery = source.createRecoveryPayload();
            PassphraseQrPayload passphraseQr = source.createPassphraseQrPayload(passphrase);
            assertArrayEquals(passphrase, passphraseQr.passphrase);
            Arrays.fill(passphraseQr.passphrase, '\0');
            char[] wrongPassphrase = "another secure passphrase".toCharArray();
            try {
                assertThrows(java.security.GeneralSecurityException.class,
                        () -> source.createPassphraseQrPayload(wrongPassphrase));
            } finally {
                Arrays.fill(wrongPassphrase, '\0');
            }
            VaultItem login = new VaultItem("Entry", "login", "password", "https://example",
                    "", "", "Work", "", false);
            String imageId = VaultObjectIds.toHex(VaultObjectIds.randomId());
            String thumbnailId = VaultObjectIds.toHex(VaultObjectIds.randomId());
            VaultItem image = VaultItem.imageReference("Photo", imageId, thumbnailId,
                    imageBytes.length);
            PendingVaultAsset imageAsset = new PendingVaultAsset(imageId, image.entryId,
                    VaultObjectRef.Type.IMAGE, "image/jpeg", imageBytes);
            PendingVaultAsset thumbnailAsset = new PendingVaultAsset(thumbnailId, image.entryId,
                    VaultObjectRef.Type.THUMBNAIL, "image/jpeg", thumbnailBytes);
            VaultData initial = new VaultData(Arrays.asList(login, image));
            source.updateWithTruePin(passphrase, initial,
                    Arrays.asList(imageAsset, thumbnailAsset));

            VaultV3Repository.OpenResult opened =
                    source.openWithPassphraseAndQr(passphrase, recovery);
            dek = opened.copyDek();
            assertEquals(2, opened.data.items.size());
            opened.clear();

            File imageObject = new File(new File(firstRoot, "objects"), imageId + ".obj");
            byte[] originalCiphertext = Files.readAllBytes(imageObject.toPath());
            VaultItem changedLogin = new VaultItem(login.entryId, login.title, login.username,
                    "changed-password", login.url, "updated note", login.tags, login.folder,
                    login.totpSecret, login.favorite, VaultItem.TYPE_LOGIN,
                    null, null, "", 0);
            source.updateWithTruePin(passphrase,
                    new VaultData(Arrays.asList(changedLogin, image)),
                    Collections.emptyList());
            assertArrayEquals(originalCiphertext, Files.readAllBytes(imageObject.toPath()));
            assertArrayEquals(imageBytes, source.readResource(dek, imageId,
                    VaultObjectRef.Type.IMAGE));

            String replacementImageId = VaultObjectIds.toHex(VaultObjectIds.randomId());
            String replacementThumbnailId = VaultObjectIds.toHex(VaultObjectIds.randomId());
            VaultItem replacementImage = VaultItem.imageReference("Photo", replacementImageId,
                    replacementThumbnailId, replacementImageBytes.length);
            PendingVaultAsset replacementAsset = new PendingVaultAsset(replacementImageId,
                    replacementImage.entryId, VaultObjectRef.Type.IMAGE, "image/jpeg",
                    replacementImageBytes);
            PendingVaultAsset replacementThumbnail = new PendingVaultAsset(
                    replacementThumbnailId, replacementImage.entryId,
                    VaultObjectRef.Type.THUMBNAIL, "image/jpeg", thumbnailBytes);
            source.updateWithTruePin(passphrase,
                    new VaultData(Arrays.asList(changedLogin, replacementImage)),
                    Arrays.asList(replacementAsset, replacementThumbnail));
            File replacementObject = new File(new File(firstRoot, "objects"),
                    replacementImageId + ".obj");
            try (RandomAccessFile object = new RandomAccessFile(replacementObject, "rw")) {
                object.seek(object.length() - 1);
                int value = object.read();
                object.seek(object.length() - 1);
                object.write(value ^ 0xff);
            }
            VaultV3Repository.ResourceResult recoveredResource = source.readResource(dek,
                    replacementImageId, replacementImage.entryId, VaultObjectRef.Type.IMAGE);
            assertTrue(recoveredResource.recoveredPrevious);
            assertArrayEquals(imageBytes, recoveredResource.plaintext);
            Crypto.wipe(recoveredResource.plaintext);
            source.repairCurrentFromPrevious(dek);

            source.changeTruePin(passphrase, nextPassphrase);
            VaultV3Repository.OpenResult changed =
                    source.openWithPassphraseAndQr(nextPassphrase, recovery);
            byte[] changedDek = changed.copyDek();
            assertArrayEquals(dek, changedDek);
            Crypto.wipe(changedDek);
            changed.clear();

            ByteArrayOutputStream backup = new ByteArrayOutputStream();
            source.exportBackup(backup, dek);
            restored = new VaultV3Repository(context, secondRoot,
                    "vlt3-restore-prefs-" + testId);
            restored.restoreBackup(new ByteArrayInputStream(backup.toByteArray()),
                    recovery, nextPassphrase);
            VaultV3Repository.OpenResult restoredOpen =
                    restored.openWithPassphraseAndQr(nextPassphrase, recovery);
            assertEquals("changed-password", restoredOpen.data.items.get(0).password);
            byte[] restoredDek = restoredOpen.copyDek();
            restoredOpen.clear();
            assertArrayEquals(imageBytes, restored.readResource(restoredDek, imageId,
                    VaultObjectRef.Type.IMAGE));
            assertArrayEquals(thumbnailBytes, restored.readResource(restoredDek, thumbnailId,
                    VaultObjectRef.Type.THUMBNAIL));
            Crypto.wipe(restoredDek);
        } finally {
            if (restored != null) {
                restored.deleteLocalVault();
            }
            source.deleteLocalVault();
            Arrays.fill(passphrase, '\0');
            Arrays.fill(nextPassphrase, '\0');
            Crypto.wipe(imageBytes);
            Crypto.wipe(thumbnailBytes);
            Crypto.wipe(replacementImageBytes);
            Crypto.wipe(dek);
            if (recovery != null) {
                Crypto.wipe(recovery.qrSecret);
            }
        }
    }
}
