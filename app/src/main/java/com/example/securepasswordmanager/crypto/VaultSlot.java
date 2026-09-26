package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class VaultSlot {
    private static final int MAX_HARDWARE_BLOB = 8 * 1024;

    public final byte[] id;
    public final byte[] pinVerifier;
    public final Crypto.SealedBox pinWrappedDek;
    /**
     * DEK wrapped by an Android Keystore key. The encoding is opaque to the vault format:
     * an AES-GCM device key stores nonce+ciphertext, an RSA biometric key stores the
     * asymmetric ciphertext. Only DeviceKeyManager interprets these bytes.
     */
    public final byte[] hardwareWrappedDek;
    public final Crypto.SealedBox encryptedData;

    public VaultSlot(byte[] id, byte[] pinVerifier, Crypto.SealedBox pinWrappedDek,
                     byte[] hardwareWrappedDek, Crypto.SealedBox encryptedData) {
        if (id.length != 16 || pinVerifier.length != 32
                || hardwareWrappedDek.length < 1 || hardwareWrappedDek.length > MAX_HARDWARE_BLOB) {
            throw new IllegalArgumentException("Invalid vault slot");
        }
        this.id = id.clone();
        this.pinVerifier = pinVerifier.clone();
        this.pinWrappedDek = pinWrappedDek;
        this.hardwareWrappedDek = hardwareWrappedDek.clone();
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
