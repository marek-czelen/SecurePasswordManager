package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class RecoveryQrPayload {
    private static final int VERSION = 1;
    public final byte[] vaultId;
    public final byte[] trueSlotId;
    public final KdfParameters kdfParameters;
    public final Crypto.SealedBox wrappedDek;

    public RecoveryQrPayload(byte[] vaultId, byte[] trueSlotId, KdfParameters kdfParameters,
                             Crypto.SealedBox wrappedDek) {
        if (vaultId.length != 16 || trueSlotId.length != 16) {
            throw new IllegalArgumentException("Invalid recovery identifiers");
        }
        this.vaultId = vaultId.clone();
        this.trueSlotId = trueSlotId.clone();
        this.kdfParameters = kdfParameters;
        this.wrappedDek = wrappedDek;
    }

    public String toQrString() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x52515231);
        output.writeInt(VERSION);
        output.write(vaultId);
        output.write(trueSlotId);
        kdfParameters.write(output);
        byte[] box = wrappedDek.encode();
        output.writeInt(box.length);
        output.write(box);
        output.flush();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    public static RecoveryQrPayload fromQrString(String value) throws IOException {
        if (value == null || value.length() > 16_384) {
            throw new IOException("Invalid recovery QR");
        }
        final byte[] encoded;
        try {
            encoded = Base64.getUrlDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid recovery QR encoding", exception);
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != 0x52515231 || input.readInt() != VERSION) {
            throw new IOException("Unsupported recovery QR");
        }
        byte[] vaultId = new byte[16];
        byte[] slotId = new byte[16];
        input.readFully(vaultId);
        input.readFully(slotId);
        KdfParameters parameters = KdfParameters.read(input);
        int boxLength = input.readInt();
        if (boxLength < 1 || boxLength > 4096) {
            throw new IOException("Invalid recovery key material");
        }
        byte[] box = new byte[boxLength];
        input.readFully(box);
        if (input.available() != 0) {
            throw new IOException("Trailing recovery QR data");
        }
        return new RecoveryQrPayload(vaultId, slotId, parameters,
                Crypto.SealedBox.decode(new DataInputStream(new ByteArrayInputStream(box))));
    }
}
