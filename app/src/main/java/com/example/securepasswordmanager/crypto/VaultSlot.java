package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encrypted key material and ciphertext for one vault format.
 *
 * <p>VLT2 intentionally has no standalone PIN verifier. The DEK wrapper can be opened
 * only with the combined Argon2id(PIN)+QR secret key.</p>
 */
public final class VaultSlot {
    private static final int MAX_HARDWARE_BLOB = 8 * 1024;

    public final byte[] id;
    /** VLT1-only field. VLT2 deliberately has no standalone PIN verifier. */
    public final byte[] pinVerifier;
    public final Crypto.SealedBox pinWrappedDek;
    /** VLT2 name for the combined PIN+QR KEK wrapper. */
    public final Crypto.SealedBox combinedWrappedDek;
    /** Opaque device-key wrapper; VLT2 uses the RSA biometric Keystore public key. */
    public final byte[] hardwareWrappedDek;
    /** VLT2 name for the authentication-bound device wrapper. */
    public final byte[] deviceWrappedDek;
    public final Crypto.SealedBox encryptedData;

    public VaultSlot(byte[] id, Crypto.SealedBox combinedWrappedDek,
                     byte[] deviceWrappedDek, Crypto.SealedBox encryptedData) {
        if (id == null || id.length != 16 || combinedWrappedDek == null
                || encryptedData == null || deviceWrappedDek == null
                || deviceWrappedDek.length < 1 || deviceWrappedDek.length > MAX_HARDWARE_BLOB) {
            throw new IllegalArgumentException("Invalid VLT2 vault slot");
        }
        this.id = id.clone();
        this.pinVerifier = new byte[0];
        this.pinWrappedDek = combinedWrappedDek;
        this.combinedWrappedDek = combinedWrappedDek;
        this.hardwareWrappedDek = deviceWrappedDek.clone();
        this.deviceWrappedDek = this.hardwareWrappedDek.clone();
        this.encryptedData = encryptedData;
    }

    public VaultSlot(byte[] id, byte[] pinVerifier, Crypto.SealedBox pinWrappedDek,
                     byte[] hardwareWrappedDek, Crypto.SealedBox encryptedData) {
        if (id.length != 16 || pinVerifier.length != 32
                || hardwareWrappedDek.length < 1 || hardwareWrappedDek.length > MAX_HARDWARE_BLOB) {
            throw new IllegalArgumentException("Invalid vault slot");
        }
        this.id = id.clone();
        this.pinVerifier = pinVerifier.clone();
        this.pinWrappedDek = pinWrappedDek;
        this.combinedWrappedDek = pinWrappedDek;
        this.hardwareWrappedDek = hardwareWrappedDek.clone();
        this.deviceWrappedDek = this.hardwareWrappedDek.clone();
        this.encryptedData = encryptedData;
    }

    void write(DataOutputStream output) throws IOException {
        output.write(id);
        output.write(pinVerifier);
        writeBox(output, pinWrappedDek);
        output.writeInt(hardwareWrappedDek.length);
        output.write(hardwareWrappedDek);
        writeBox(output, encryptedData);
    }

    static VaultSlot read(DataInputStream input) throws IOException {
        byte[] id = new byte[16];
        byte[] verifier = new byte[32];
        input.readFully(id);
        input.readFully(verifier);
        Crypto.SealedBox pinWrapped = readBox(input);
        int hardwareLength = input.readInt();
        if (hardwareLength < 1 || hardwareLength > MAX_HARDWARE_BLOB) {
            throw new IOException("Invalid hardware-wrapped key");
        }
        byte[] hardware = new byte[hardwareLength];
        input.readFully(hardware);
        Crypto.SealedBox encrypted = readBox(input);
        return new VaultSlot(id, verifier, pinWrapped, hardware, encrypted);
    }

    void writeV2(DataOutputStream output) throws IOException {
        output.write(id);
        writeBox(output, pinWrappedDek);
        output.writeInt(hardwareWrappedDek.length);
        output.write(hardwareWrappedDek);
        writeBox(output, encryptedData);
    }

    static VaultSlot readV2(DataInputStream input) throws IOException {
        byte[] id = new byte[16];
        input.readFully(id);
        Crypto.SealedBox combined = readBox(input);
        int hardwareLength = input.readInt();
        if (hardwareLength < 1 || hardwareLength > MAX_HARDWARE_BLOB) {
            throw new IOException("Invalid VLT2 device wrapper");
        }
        byte[] hardware = new byte[hardwareLength];
        input.readFully(hardware);
        Crypto.SealedBox encrypted = readBox(input);
        return new VaultSlot(id, combined, hardware, encrypted);
    }

    private static void writeBox(DataOutputStream output, Crypto.SealedBox box) throws IOException {
        byte[] encoded = box.encode();
        if (encoded.length > 64 * 1024 * 1024) {
            throw new IOException("Vault component is too large");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static Crypto.SealedBox readBox(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 64 * 1024 * 1024) {
            throw new IOException("Invalid vault component");
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return Crypto.SealedBox.decode(new DataInputStream(new ByteArrayInputStream(encoded)));
    }
}
