package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VaultEnvelope {
    public static final int VERSION = 1;
    private static final int MAX_VAULT_SIZE = 256 * 1024 * 1024;

    public final int version;
    public final byte[] vaultId;
    public final KdfParameters kdfParameters;
    public final List<VaultSlot> slots;

    public VaultEnvelope(byte[] vaultId, KdfParameters kdfParameters, List<VaultSlot> slots) {
        if (vaultId.length != 16 || slots.size() != 1) {
            throw new IllegalArgumentException("A vault must contain exactly one slot");
        }
        this.version = VERSION;
        this.vaultId = vaultId.clone();
        this.kdfParameters = kdfParameters;
        this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x564c5431);
        output.writeInt(version);
        output.write(vaultId);
        kdfParameters.write(output);
        output.writeByte(slots.size());
        for (VaultSlot slot : slots) {
            slot.write(output);
        }
        output.flush();
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_VAULT_SIZE) {
            throw new IOException("Vault is too large");
        }
        return encoded;
    }

    public static VaultEnvelope decode(byte[] encoded) throws IOException {
        if (encoded.length < 32 || encoded.length > MAX_VAULT_SIZE) {
            throw new IOException("Invalid vault size");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != 0x564c5431) {
            throw new IOException("Invalid vault magic");
        }
        int version = input.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported vault version");
        }
        byte[] vaultId = new byte[16];
        input.readFully(vaultId);
        KdfParameters parameters = KdfParameters.read(input);
        int slotCount = input.readUnsignedByte();
        if (slotCount != 1) {
            throw new IOException("Invalid vault slot count");
        }
        List<VaultSlot> slots = new ArrayList<>(1);
        slots.add(VaultSlot.read(input));
        if (input.available() != 0) {
            throw new IOException("Trailing vault bytes");
        }
        return new VaultEnvelope(vaultId, parameters, slots);
    }
}
