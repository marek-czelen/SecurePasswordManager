package com.cretorix.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Bounded binary encoding for the VLT3 header. */
public final class VaultHeaderCodec {
    private static final int MAGIC = 0x564c5433;
    private static final int MAX_HEADER_SIZE = 64 * 1024;

    private VaultHeaderCodec() {
    }

    public static byte[] encode(VaultHeaderV3 header) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeInt(header.containerFormatVersion);
        output.writeInt(header.headerSchemaVersion);
        output.write(header.vaultId);
        output.writeUTF(header.kdfParameters.algorithm);
        output.writeInt(header.kdfParameters.memoryKiB);
        output.writeInt(header.kdfParameters.timeCost);
        output.writeInt(header.kdfParameters.parallelism);
        output.writeByte(header.kdfParameters.salt.length);
        output.write(header.kdfParameters.salt);
        output.write(header.wrapSalt);
        output.write(header.qrId);
        output.write(header.hardwareKeyId);
        output.write(header.wrapperGenerationId);
        byte[] pinBox = header.pinWrappedDek.encode();
        output.writeInt(pinBox.length);
        output.write(pinBox);
        output.writeInt(header.hardwareWrappedDek.length);
        output.write(header.hardwareWrappedDek);
        output.flush();
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_HEADER_SIZE) {
            throw new IOException("VLT3 header is too large");
        }
        return result;
    }

    public static VaultHeaderV3 decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 32 || encoded.length > MAX_HEADER_SIZE) {
            throw new IOException("Invalid VLT3 header size");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != MAGIC) {
            throw new IOException("Invalid VLT3 header magic");
        }
        int containerVersion = input.readInt();
        int headerVersion = input.readInt();
        if (containerVersion != VaultV3Format.CONTAINER_FORMAT_VERSION
                || headerVersion != VaultV3Format.HEADER_SCHEMA_VERSION) {
            throw new IOException("Unsupported VLT3 header version");
        }
        byte[] vaultId = new byte[VaultHeaderV3.VAULT_ID_SIZE];
        input.readFully(vaultId);
        String algorithm = input.readUTF();
        if (!KdfParameters.ALGORITHM.equals(algorithm)) {
            throw new IOException("Unsupported VLT3 KDF");
        }
        int memoryKiB = input.readInt();
        int timeCost = input.readInt();
        int parallelism = input.readInt();
        int saltLength = input.readUnsignedByte();
        if (saltLength != KdfParameters.SALT_SIZE) {
            throw new IOException("Invalid VLT3 KDF salt");
        }
        byte[] salt = new byte[saltLength];
        input.readFully(salt);
        KdfParameters parameters;
        try {
            parameters = new KdfParameters(salt, memoryKiB, timeCost, parallelism);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid VLT3 KDF parameters", exception);
        }
        if (!VaultV3Format.isSupportedKdf(parameters)) {
            throw new IOException("VLT3 KDF parameters exceed supported resource limits");
        }
        byte[] wrapSalt = new byte[VaultHeaderV3.WRAP_SALT_SIZE];
        byte[] qrId = new byte[VaultHeaderV3.QR_ID_SIZE];
        byte[] hardwareKeyId = new byte[VaultHeaderV3.HARDWARE_KEY_ID_SIZE];
        byte[] generationId = new byte[VaultHeaderV3.GENERATION_ID_SIZE];
        input.readFully(wrapSalt);
        input.readFully(qrId);
        input.readFully(hardwareKeyId);
        input.readFully(generationId);
        int pinBoxLength = input.readInt();
        if (pinBoxLength < 1 || pinBoxLength > 1024) {
            throw new IOException("Invalid VLT3 DEK wrapper");
        }
        byte[] encodedBox = new byte[pinBoxLength];
        input.readFully(encodedBox);
        DataInputStream boxInput = new DataInputStream(new ByteArrayInputStream(encodedBox));
        Crypto.SealedBox pinWrappedDek = Crypto.SealedBox.decode(boxInput);
        if (boxInput.available() != 0) {
            throw new IOException("Trailing VLT3 DEK wrapper bytes");
        }
        int hardwareLength = input.readInt();
        if (hardwareLength < 1
                || hardwareLength > VaultHeaderV3.MAX_HARDWARE_WRAPPER_SIZE) {
            throw new IOException("Invalid VLT3 hardware wrapper");
        }
        byte[] hardwareWrappedDek = new byte[hardwareLength];
        input.readFully(hardwareWrappedDek);
        if (input.available() != 0) {
            throw new IOException("Trailing VLT3 header bytes");
        }
        try {
            return new VaultHeaderV3(vaultId, parameters, wrapSalt, qrId, hardwareKeyId,
                    generationId, pinWrappedDek, hardwareWrappedDek);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid VLT3 header", exception);
        }
    }
}
