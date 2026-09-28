package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** Domain-separated VLT3 key derivation. */
public final class VaultV3KeyDerivation {
    private static final byte[] WRAP_INFO =
            Crypto.utf8("SPM/VLT3/combined-kek/header-wrapper");

    private VaultV3KeyDerivation() {
    }

    public static Crypto.SealedBox wrapDek(byte[] dek, char[] passphrase, byte[] qrSecret,
                                           byte[] vaultId, KdfParameters parameters,
                                           byte[] wrapSalt, byte[] qrId,
                                           byte[] hardwareKeyId,
                                           byte[] wrapperGenerationId)
            throws GeneralSecurityException {
        validateSecretInputs(dek, qrSecret, vaultId, wrapSalt, qrId, hardwareKeyId,
                wrapperGenerationId);
        if (!VaultV3Format.isSupportedKdf(parameters)) {
            throw new IllegalArgumentException("Unsupported VLT3 KDF parameters");
        }
        byte[] pinKey = PinKdf.derive(passphrase, parameters);
        byte[] ikm = concat(Crypto.utf8("SPM/VLT3/ikm"), pinKey, qrSecret);
        byte[] kek = null;
        byte[] info = null;
        try {
            info = wrapperInfo(wrapperGenerationId);
            kek = Crypto.hkdf(ikm, wrapSalt, info, Crypto.KEY_SIZE);
            byte[] aad = VaultHeaderV3.associatedData(vaultId, parameters, wrapSalt,
                    qrId, hardwareKeyId, wrapperGenerationId, "dek-wrap");
            return Crypto.seal(kek, dek, aad);
        } finally {
            Crypto.wipe(pinKey);
            Crypto.wipe(ikm);
            Crypto.wipe(kek);
            Crypto.wipe(info);
        }
    }

    public static byte[] unwrapDek(VaultHeaderV3 header, char[] passphrase, byte[] qrSecret)
            throws GeneralSecurityException {
        if (header == null || qrSecret == null || qrSecret.length != Crypto.KEY_SIZE
                || !Crypto.constantTimeEquals(Crypto.sha256(qrSecret), header.qrId)) {
            throw new GeneralSecurityException("Recovery secret does not match VLT3 header");
        }
        byte[] pinKey = PinKdf.derive(passphrase, header.kdfParameters);
        byte[] ikm = concat(Crypto.utf8("SPM/VLT3/ikm"), pinKey, qrSecret);
        byte[] kek = null;
        byte[] info = null;
        try {
            info = wrapperInfo(header.wrapperGenerationId);
            kek = Crypto.hkdf(ikm, header.wrapSalt, info, Crypto.KEY_SIZE);
            byte[] dek = Crypto.open(kek, header.pinWrappedDek,
                    header.associatedData("dek-wrap"));
            if (dek.length != Crypto.KEY_SIZE) {
                Crypto.wipe(dek);
                throw new GeneralSecurityException("Invalid VLT3 DEK length");
            }
            return dek;
        } finally {
            Crypto.wipe(pinKey);
            Crypto.wipe(ikm);
            Crypto.wipe(kek);
            Crypto.wipe(info);
        }
    }

    public static byte[] deriveManifestKey(byte[] dek, byte[] vaultId, byte[] generationId)
            throws GeneralSecurityException {
        return deriveObjectKey(dek, vaultId, "manifest", generationId,
                generationId, VaultV3Format.MANIFEST_SCHEMA_VERSION);
    }

    public static byte[] deriveObjectKey(byte[] dek, byte[] vaultId, String type,
                                         byte[] objectId, byte[] generationId,
                                         int schemaVersion)
            throws GeneralSecurityException {
        if (dek == null || dek.length != Crypto.KEY_SIZE || vaultId == null
                || vaultId.length != VaultHeaderV3.VAULT_ID_SIZE
                || type == null || type.isEmpty() || objectId == null
                || objectId.length != VaultHeaderV3.GENERATION_ID_SIZE
                || generationId == null
                || generationId.length != VaultHeaderV3.GENERATION_ID_SIZE
                || schemaVersion < 1) {
            throw new IllegalArgumentException("Invalid VLT3 key derivation context");
        }
        byte[] info = objectInfo(type, objectId, generationId, schemaVersion);
        return Crypto.hkdf(dek, vaultId, info, Crypto.KEY_SIZE);
    }

    private static byte[] objectInfo(String type, byte[] objectId, byte[] generationId,
                                     int schemaVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.write("SPM/VLT3/object-key".getBytes(StandardCharsets.UTF_8));
            output.writeUTF(type);
            output.write(objectId);
            output.write(generationId);
            output.writeInt(schemaVersion);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void validateSecretInputs(byte[] dek, byte[] qrSecret, byte[] vaultId,
                                             byte[] wrapSalt, byte[] qrId,
                                             byte[] hardwareKeyId,
                                             byte[] generationId) {
        if (dek == null || dek.length != Crypto.KEY_SIZE || qrSecret == null
                || qrSecret.length != Crypto.KEY_SIZE || vaultId == null
                || vaultId.length != VaultHeaderV3.VAULT_ID_SIZE
                || wrapSalt == null || wrapSalt.length != VaultHeaderV3.WRAP_SALT_SIZE
                || qrId == null || qrId.length != VaultHeaderV3.QR_ID_SIZE
                || hardwareKeyId == null
                || hardwareKeyId.length != VaultHeaderV3.HARDWARE_KEY_ID_SIZE
                || generationId == null
                || generationId.length != VaultHeaderV3.GENERATION_ID_SIZE) {
            throw new IllegalArgumentException("Invalid VLT3 key material");
        }
        try {
            if (!Crypto.constantTimeEquals(Crypto.sha256(qrSecret), qrId)) {
                throw new IllegalArgumentException("Recovery secret does not match VLT3 header");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length = Math.addExact(length, value.length);
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static byte[] wrapperInfo(byte[] generationId) {
        return concat(WRAP_INFO, Crypto.utf8("/generation/"), generationId);
    }
}
