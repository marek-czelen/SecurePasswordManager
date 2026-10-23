package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.KdfParameters;
import com.cretorix.securepasswordmanager.crypto.VaultBackupV3;
import com.cretorix.securepasswordmanager.crypto.VaultData;
import com.cretorix.securepasswordmanager.crypto.VaultHeaderV3;
import com.cretorix.securepasswordmanager.crypto.VaultItem;
import com.cretorix.securepasswordmanager.crypto.VaultManifest;
import com.cretorix.securepasswordmanager.crypto.VaultManifestCodec;
import com.cretorix.securepasswordmanager.crypto.VaultObjectIds;
import com.cretorix.securepasswordmanager.crypto.VaultObjectRef;
import com.cretorix.securepasswordmanager.crypto.VaultObjectStore;
import com.cretorix.securepasswordmanager.crypto.VaultTextDataCodec;
import com.cretorix.securepasswordmanager.crypto.VaultV3KeyDerivation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class VaultBackupV3Test {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void backupStreamsEncryptedManifestAndObjectsAsOneContainer() throws Exception {
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] qrSecret = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] vaultId = Crypto.randomBytes(VaultHeaderV3.VAULT_ID_SIZE);
        byte[] imageBytes = Crypto.randomBytes(3072);
        byte[] thumbnailBytes = Crypto.randomBytes(128);
        VaultItem image = VaultItem.imageReference("Photo",
                VaultObjectIds.toHex(VaultObjectIds.randomId()),
                VaultObjectIds.toHex(VaultObjectIds.randomId()), imageBytes.length);
        byte[] ownerId = hex(image.entryId);
        VaultObjectStore sourceStore = new VaultObjectStore(
                temporaryFolder.newFolder("source-objects"), vaultId, ignored -> { });
        VaultObjectRef imageRef = sourceStore.writeWithId(dek,
                VaultObjectIds.fromHex(image.attachmentId), VaultObjectRef.Type.IMAGE,
                ownerId, "image/jpeg", imageBytes);
        VaultObjectRef thumbnailRef = sourceStore.writeWithId(dek,
                VaultObjectIds.fromHex(image.thumbnailId), VaultObjectRef.Type.THUMBNAIL,
                ownerId, "image/jpeg", thumbnailBytes);
        byte[] textBytes = VaultTextDataCodec.encode(
                new VaultData(Collections.singletonList(image)));
        VaultObjectRef textRef = sourceStore.write(dek, VaultObjectRef.Type.TEXT_BLOCK,
                null, "", textBytes);
        VaultManifest manifest = new VaultManifest(VaultObjectIds.randomId(), textRef,
                Arrays.asList(imageRef, thumbnailRef));

        byte[] wrapSalt = Crypto.randomBytes(VaultHeaderV3.WRAP_SALT_SIZE);
        byte[] qrId = Crypto.sha256(qrSecret);
        byte[] hardwareKeyId = Crypto.randomBytes(VaultHeaderV3.HARDWARE_KEY_ID_SIZE);
        byte[] wrapperGeneration = VaultObjectIds.randomId();
        KdfParameters parameters = new KdfParameters(new byte[16], 8 * 1024, 1, 1);
        Crypto.SealedBox wrapper = VaultV3KeyDerivation.wrapDek(dek,
                "test passphrase".toCharArray(), qrSecret, vaultId, parameters,
                wrapSalt, qrId, hardwareKeyId, wrapperGeneration);
        VaultHeaderV3 header = new VaultHeaderV3(vaultId, parameters, wrapSalt, qrId,
                hardwareKeyId, wrapperGeneration, wrapper, new byte[]{2, 1, 2});
        byte[] encodedHeader = header.encode();
        byte[] encodedManifest = VaultManifestCodec.seal(manifest, dek, vaultId);

        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        VaultBackupV3.write(backup, encodedHeader, encodedManifest, manifest, sourceStore);
        byte[] backupBytes = backup.toByteArray();

        VaultBackupV3.BackupReader reader =
                VaultBackupV3.open(new ByteArrayInputStream(backupBytes));
        VaultHeaderV3 restoredHeader = VaultHeaderV3.decode(reader.encodedHeader());
        byte[] restoredDek = VaultV3KeyDerivation.unwrapDek(restoredHeader,
                "test passphrase".toCharArray(), qrSecret);
        VaultManifest restoredManifest = VaultManifestCodec.open(
                reader.encodedManifest(), restoredDek, restoredHeader.vaultId);
        VaultObjectStore destinationStore = new VaultObjectStore(
                temporaryFolder.newFolder("restored-objects"), vaultId, ignored -> { });
        reader.importObjects(restoredManifest, destinationStore);

        byte[] restoredText = destinationStore.read(restoredManifest.textBlock, restoredDek);
        VaultData restoredData = VaultTextDataCodec.decode(restoredText);
        assertEquals(image.title, restoredData.items.get(0).title);
        assertArrayEquals(imageBytes, destinationStore.read(imageRef, restoredDek));
        assertArrayEquals(thumbnailBytes, destinationStore.read(thumbnailRef, restoredDek));

        byte[] corrupted = backupBytes.clone();
        ByteBuffer framing = ByteBuffer.wrap(corrupted);
        framing.position(12);
        int headerLength = framing.getInt();
        framing.position(framing.position() + headerLength);
        int manifestLength = framing.getInt();
        framing.position(framing.position() + manifestLength);
        framing.getInt();
        framing.position(framing.position() + 32);
        framing.getLong();
        int objectHeaderLength = (int) (VaultObjectStore.encodedLength(
                restoredManifest.textBlock) - restoredManifest.textBlock.ciphertextLength);
        corrupted[framing.position() + objectHeaderLength] ^= 1;
        VaultBackupV3.BackupReader corruptReader =
                VaultBackupV3.open(new ByteArrayInputStream(corrupted));
        VaultObjectStore corruptDestination = new VaultObjectStore(
                temporaryFolder.newFolder("corrupt-import"), vaultId, ignored -> { });
        assertThrows(IOException.class,
                () -> corruptReader.importObjects(restoredManifest, corruptDestination));

        assertThrows(IOException.class, () -> VaultBackupV3.open(
                new ByteArrayInputStream(Arrays.copyOf(backupBytes, backupBytes.length - 1)))
                .importObjects(restoredManifest, new VaultObjectStore(
                        temporaryFolder.newFolder("truncated-import"), vaultId, ignored -> { })));
        Crypto.wipe(dek);
        Crypto.wipe(qrSecret);
        Crypto.wipe(ownerId);
        Crypto.wipe(imageBytes);
        Crypto.wipe(thumbnailBytes);
        Crypto.wipe(textBytes);
        Crypto.wipe(restoredDek);
        Crypto.wipe(restoredText);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(value.charAt(i * 2), 16) << 4)
                    | Character.digit(value.charAt(i * 2 + 1), 16));
        }
        return result;
    }
}
