package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.KdfParameters;
import com.cretorix.securepasswordmanager.crypto.VaultHeaderV3;
import com.cretorix.securepasswordmanager.crypto.VaultV3Format;
import com.cretorix.securepasswordmanager.crypto.VaultV3KeyDerivation;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public final class VaultV3HeaderTest {
    private static final char[] PASSPHRASE = "correct horse battery".toCharArray();

    @Test
    public void headerRoundTripsWithIndependentVersions() throws Exception {
        VaultHeaderV3 header = createHeader(PASSPHRASE);
        VaultHeaderV3 decoded = VaultHeaderV3.decode(header.encode());

        assertEquals(VaultV3Format.CONTAINER_FORMAT_VERSION, decoded.containerFormatVersion);
        assertEquals(VaultV3Format.HEADER_SCHEMA_VERSION, decoded.headerSchemaVersion);
        assertArrayEquals(header.vaultId, decoded.vaultId);
        assertArrayEquals(header.wrapSalt, decoded.wrapSalt);
        assertArrayEquals(header.qrId, decoded.qrId);
        assertArrayEquals(header.hardwareKeyId, decoded.hardwareKeyId);
        assertArrayEquals(header.wrapperGenerationId, decoded.wrapperGenerationId);
        assertArrayEquals(header.pinWrappedDek.nonce, decoded.pinWrappedDek.nonce);
        assertArrayEquals(header.hardwareWrappedDek, decoded.hardwareWrappedDek);
    }

    @Test
    public void passphraseWrapperCanChangeWithoutChangingDekOrHardwareWrapper()
            throws Exception {
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] qrSecret = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] vaultId = Crypto.randomBytes(VaultHeaderV3.VAULT_ID_SIZE);
        byte[] wrapSalt = Crypto.randomBytes(VaultHeaderV3.WRAP_SALT_SIZE);
        byte[] qrId = Crypto.sha256(qrSecret);
        byte[] hardwareKeyId = Crypto.randomBytes(VaultHeaderV3.HARDWARE_KEY_ID_SIZE);
        byte[] hardware = new byte[]{2, 3, 4, 5};
        KdfParameters parameters = testParameters();
        byte[] firstGeneration = Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE);
        Crypto.SealedBox firstBox = VaultV3KeyDerivation.wrapDek(dek, PASSPHRASE, qrSecret,
                vaultId, parameters, wrapSalt, qrId, hardwareKeyId, firstGeneration);
        VaultHeaderV3 first = new VaultHeaderV3(vaultId, parameters, wrapSalt, qrId,
                hardwareKeyId, firstGeneration, firstBox, hardware);

        char[] nextPassphrase = "strong new passphrase".toCharArray();
        byte[] nextGeneration = Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE);
        Crypto.SealedBox nextBox = VaultV3KeyDerivation.wrapDek(dek, nextPassphrase, qrSecret,
                vaultId, parameters, wrapSalt, qrId, hardwareKeyId, nextGeneration);
        VaultHeaderV3 next = first.withPinWrapper(nextGeneration, nextBox);

        assertArrayEquals(dek, VaultV3KeyDerivation.unwrapDek(next, nextPassphrase, qrSecret));
        assertArrayEquals(hardware, next.hardwareWrappedDek);
        assertThrows(Exception.class,
                () -> VaultV3KeyDerivation.unwrapDek(next, PASSPHRASE, qrSecret));
    }

    @Test
    public void wrapperRejectsWrongRecoverySecretAndChangedAad() throws Exception {
        VaultHeaderV3 header = createHeader(PASSPHRASE);
        byte[] wrongQr = Crypto.randomBytes(Crypto.KEY_SIZE);
        assertThrows(Exception.class,
                () -> VaultV3KeyDerivation.unwrapDek(header, PASSPHRASE, wrongQr));

        byte[] changedSalt = header.wrapSalt.clone();
        changedSalt[0] ^= 1;
        VaultHeaderV3 changed = new VaultHeaderV3(header.vaultId, header.kdfParameters,
                changedSalt, header.qrId, header.hardwareKeyId, header.wrapperGenerationId,
                header.pinWrappedDek, header.hardwareWrappedDek);
        assertThrows(Exception.class,
                () -> VaultV3KeyDerivation.unwrapDek(changed, PASSPHRASE,
                        qrSecretFor(header)));

        byte[] changedHardwareId = header.hardwareKeyId.clone();
        changedHardwareId[0] ^= 1;
        VaultHeaderV3 changedDeviceKey = new VaultHeaderV3(header.vaultId,
                header.kdfParameters, header.wrapSalt, header.qrId, changedHardwareId,
                header.wrapperGenerationId, header.pinWrappedDek, header.hardwareWrappedDek);
        assertThrows(Exception.class, () -> VaultV3KeyDerivation.unwrapDek(
                changedDeviceKey, PASSPHRASE, qrSecretFor(header)));
    }

    @Test
    public void headerRejectsTrailingBytesAndUnsupportedVersions() throws Exception {
        byte[] encoded = createHeader(PASSPHRASE).encode();
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(Exception.class, () -> VaultHeaderV3.decode(trailing));

        encoded[7] = (byte) (encoded[7] + 1);
        assertThrows(Exception.class, () -> VaultHeaderV3.decode(encoded));
    }

    @Test
    public void headerRejectsExcessiveKdfMemory() throws Exception {
        KdfParameters excessive = new KdfParameters(new byte[16], 512 * 1024, 3, 2);
        assertThrows(IllegalArgumentException.class, () -> new VaultHeaderV3(
                Crypto.randomBytes(VaultHeaderV3.VAULT_ID_SIZE), excessive,
                Crypto.randomBytes(VaultHeaderV3.WRAP_SALT_SIZE),
                Crypto.randomBytes(VaultHeaderV3.QR_ID_SIZE),
                Crypto.randomBytes(VaultHeaderV3.HARDWARE_KEY_ID_SIZE),
                Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE),
                Crypto.seal(Crypto.randomBytes(32), Crypto.randomBytes(32), null),
                new byte[]{2, 1, 2}));
    }

    @Test
    public void objectKeysAreSeparatedByPurposeAndGeneration() throws Exception {
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] vaultId = Crypto.randomBytes(VaultHeaderV3.VAULT_ID_SIZE);
        byte[] objectId = Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE);
        byte[] generation = Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE);
        byte[] first = VaultV3KeyDerivation.deriveObjectKey(dek, vaultId, "file",
                objectId, generation, VaultV3Format.OBJECT_SCHEMA_VERSION);
        byte[] nextPurpose = VaultV3KeyDerivation.deriveObjectKey(dek, vaultId, "image",
                objectId, generation, VaultV3Format.OBJECT_SCHEMA_VERSION);
        byte[] nextGeneration = VaultV3KeyDerivation.deriveObjectKey(dek, vaultId, "file",
                objectId, Crypto.randomBytes(32), VaultV3Format.OBJECT_SCHEMA_VERSION);
        assertNotEquals(Arrays.toString(first), Arrays.toString(nextPurpose));
        assertNotEquals(Arrays.toString(first), Arrays.toString(nextGeneration));
    }

    private static VaultHeaderV3 createHeader(char[] passphrase) throws Exception {
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] qrSecret = testQrSecret();
        byte[] vaultId = Crypto.randomBytes(VaultHeaderV3.VAULT_ID_SIZE);
        byte[] wrapSalt = Crypto.randomBytes(VaultHeaderV3.WRAP_SALT_SIZE);
        byte[] qrId = Crypto.sha256(qrSecret);
        byte[] hardwareKeyId = Crypto.randomBytes(VaultHeaderV3.HARDWARE_KEY_ID_SIZE);
        byte[] generation = Crypto.randomBytes(VaultHeaderV3.GENERATION_ID_SIZE);
        KdfParameters parameters = testParameters();
        Crypto.SealedBox box = VaultV3KeyDerivation.wrapDek(dek, passphrase, qrSecret,
                vaultId, parameters, wrapSalt, qrId, hardwareKeyId, generation);
        VaultHeaderV3 header = new VaultHeaderV3(vaultId, parameters, wrapSalt, qrId,
                hardwareKeyId, generation, box, new byte[]{2, 1, 2});
        assertArrayEquals(dek, VaultV3KeyDerivation.unwrapDek(header, passphrase, qrSecret));
        Arrays.fill(dek, (byte) 0);
        return header;
    }

    private static byte[] qrSecretFor(VaultHeaderV3 header) {
        return testQrSecret();
    }

    private static byte[] testQrSecret() {
        return Arrays.copyOf(Crypto.utf8("test-only-recovery-secret"), Crypto.KEY_SIZE);
    }

    private static KdfParameters testParameters() {
        return new KdfParameters(new byte[16], 8 * 1024, 1, 1);
    }
}
