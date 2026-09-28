package com.example.securepasswordmanager.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Public VLT3 metadata and opaque wrappers for the existing random DEK. */
public final class VaultHeaderV3 {
    public static final int VAULT_ID_SIZE = 16;
    public static final int QR_ID_SIZE = 32;
    public static final int HARDWARE_KEY_ID_SIZE = 16;
    public static final int GENERATION_ID_SIZE = 32;
    public static final int WRAP_SALT_SIZE = 16;
    public static final int MAX_HARDWARE_WRAPPER_SIZE = 8 * 1024;

    public final int containerFormatVersion;
    public final int headerSchemaVersion;
    public final byte[] vaultId;
    public final KdfParameters kdfParameters;
    public final byte[] wrapSalt;
    public final byte[] qrId;
    public final byte[] hardwareKeyId;
    public final byte[] wrapperGenerationId;
    public final Crypto.SealedBox pinWrappedDek;
    public final byte[] hardwareWrappedDek;

    public VaultHeaderV3(byte[] vaultId, KdfParameters kdfParameters, byte[] wrapSalt,
                         byte[] qrId, byte[] hardwareKeyId, byte[] wrapperGenerationId,
                         Crypto.SealedBox pinWrappedDek, byte[] hardwareWrappedDek) {
        if (vaultId == null || vaultId.length != VAULT_ID_SIZE
                || !VaultV3Format.isSupportedKdf(kdfParameters)
                || wrapSalt == null || wrapSalt.length != WRAP_SALT_SIZE
                || qrId == null || qrId.length != QR_ID_SIZE
                || hardwareKeyId == null || hardwareKeyId.length != HARDWARE_KEY_ID_SIZE
                || wrapperGenerationId == null
                || wrapperGenerationId.length != GENERATION_ID_SIZE
                || pinWrappedDek == null
                || hardwareWrappedDek == null || hardwareWrappedDek.length < 1
                || hardwareWrappedDek.length > MAX_HARDWARE_WRAPPER_SIZE) {
            throw new IllegalArgumentException("Invalid VLT3 header");
        }
        containerFormatVersion = VaultV3Format.CONTAINER_FORMAT_VERSION;
        headerSchemaVersion = VaultV3Format.HEADER_SCHEMA_VERSION;
        this.vaultId = vaultId.clone();
        this.kdfParameters = kdfParameters;
        this.wrapSalt = wrapSalt.clone();
        this.qrId = qrId.clone();
        this.hardwareKeyId = hardwareKeyId.clone();
        this.wrapperGenerationId = wrapperGenerationId.clone();
        this.pinWrappedDek = pinWrappedDek;
        this.hardwareWrappedDek = hardwareWrappedDek.clone();
    }

    public byte[] associatedData(String purpose) {
        return associatedData(vaultId, kdfParameters, wrapSalt, qrId,
                hardwareKeyId, wrapperGenerationId, purpose);
    }

    public static byte[] associatedData(byte[] vaultId, KdfParameters kdfParameters,
                                        byte[] wrapSalt, byte[] qrId,
                                        byte[] hardwareKeyId,
                                        byte[] wrapperGenerationId, String purpose) {
        if (!"dek-wrap".equals(purpose) || vaultId == null
                || vaultId.length != VAULT_ID_SIZE || kdfParameters == null
                || wrapSalt == null || wrapSalt.length != WRAP_SALT_SIZE
                || qrId == null || qrId.length != QR_ID_SIZE
                || hardwareKeyId == null || hardwareKeyId.length != HARDWARE_KEY_ID_SIZE
                || wrapperGenerationId == null
                || wrapperGenerationId.length != GENERATION_ID_SIZE) {
            throw new IllegalArgumentException("Invalid header AAD context");
        }
        byte[] domain = "SPM/VLT3/header/dek-wrap".getBytes(StandardCharsets.UTF_8);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(domain.length + 4 * 6
                + vaultId.length + wrapSalt.length + qrId.length
                + hardwareKeyId.length
                + wrapperGenerationId.length + kdfParameters.salt.length
                + KdfParameters.ALGORITHM.getBytes(StandardCharsets.UTF_8).length);
        buffer.put(domain);
        buffer.putInt(VaultV3Format.CONTAINER_FORMAT_VERSION);
        buffer.putInt(VaultV3Format.HEADER_SCHEMA_VERSION);
        buffer.put(vaultId);
        buffer.putInt(KdfParameters.ALGORITHM.length());
        buffer.put(KdfParameters.ALGORITHM.getBytes(StandardCharsets.UTF_8));
        buffer.putInt(kdfParameters.memoryKiB);
        buffer.putInt(kdfParameters.timeCost);
        buffer.putInt(kdfParameters.parallelism);
        buffer.put(kdfParameters.salt);
        buffer.put(wrapSalt);
        buffer.put(qrId);
        buffer.put(hardwareKeyId);
        buffer.put(wrapperGenerationId);
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    public VaultHeaderV3 withPinWrapper(byte[] generationId, Crypto.SealedBox newPinWrappedDek) {
        return new VaultHeaderV3(vaultId, kdfParameters, wrapSalt, qrId, hardwareKeyId,
                generationId, newPinWrappedDek, hardwareWrappedDek);
    }

    public byte[] encode() throws IOException {
        return VaultHeaderCodec.encode(this);
    }

    public static VaultHeaderV3 decode(byte[] encoded) throws IOException {
        return VaultHeaderCodec.decode(encoded);
    }
}
