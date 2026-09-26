package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outer versioned vault container.
 *
 * <p>VLT2 is the active format. VLT1 is parsed only to identify legacy data and is
 * rejected by the VLT2 repository until an explicit migration/recreation path is used.</p>
 */
public final class VaultEnvelope {
    public static final int VERSION = 2;
    public static final int LEGACY_VERSION = 1;
    private static final int MAX_VAULT_SIZE = 256 * 1024 * 1024;

    public final int version;
    public final byte[] vaultId;
    public final KdfParameters kdfParameters;
    public final List<VaultSlot> slots;
    public final byte[] wrapSalt;
    public final byte[] qrId;

    /** Legacy constructor. It writes VLT1 and exists only to make migration explicit. */
    public VaultEnvelope(byte[] vaultId, KdfParameters kdfParameters, List<VaultSlot> slots) {
        if (vaultId.length != 16 || (slots.size() != 1 && slots.size() != 2)) {
            throw new IllegalArgumentException("Invalid vault slot count");
        }
        this.version = LEGACY_VERSION;
        this.vaultId = vaultId.clone();
        this.kdfParameters = kdfParameters;
        this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
        this.wrapSalt = null;
        this.qrId = null;
    }

    public VaultEnvelope(byte[] vaultId, KdfParameters kdfParameters, byte[] wrapSalt,
                         byte[] qrId, VaultSlot slot) {
        if (vaultId == null || vaultId.length != 16 || wrapSalt == null || wrapSalt.length != 16
                || qrId == null || qrId.length != 32 || slot == null
                || !KdfParameters.ALGORITHM.equals(kdfParameters.algorithm)) {
            throw new IllegalArgumentException("Invalid VLT2 envelope");
        }
        this.version = VERSION;
        this.vaultId = vaultId.clone();
        this.kdfParameters = kdfParameters;
        this.wrapSalt = wrapSalt.clone();
        this.qrId = qrId.clone();
        this.slots = Collections.singletonList(slot);
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(version == VERSION ? 0x564c5432 : 0x564c5431);
        output.writeInt(version);
        output.write(vaultId);
        if (version == VERSION) {
            kdfParameters.writeV2(output);
            output.write(wrapSalt);
            output.write(qrId);
            slots.get(0).writeV2(output);
        } else {
            kdfParameters.write(output);
            output.writeByte(slots.size());
            for (VaultSlot slot : slots) {
                slot.write(output);
            }
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
        int magic = input.readInt();
        if (magic != 0x564c5431 && magic != 0x564c5432) {
            throw new IOException("Invalid vault magic");
        }
        int version = input.readInt();
        if (magic == 0x564c5431 && version != LEGACY_VERSION) {
            throw new IOException("Unsupported legacy vault version");
        }
        if (magic == 0x564c5432 && version != VERSION) {
            throw new IOException("Unsupported vault version");
        }
        byte[] vaultId = new byte[16];
        input.readFully(vaultId);
        KdfParameters parameters = magic == 0x564c5432
                ? KdfParameters.readV2(input) : KdfParameters.read(input);
        if (magic == 0x564c5432) {
            byte[] wrapSalt = new byte[16];
            byte[] qrId = new byte[32];
            input.readFully(wrapSalt);
            input.readFully(qrId);
            VaultSlot slot = VaultSlot.readV2(input);
            if (input.available() != 0) {
                throw new IOException("Trailing vault bytes");
            }
            return new VaultEnvelope(vaultId, parameters, wrapSalt, qrId, slot);
        }
        int slotCount = input.readUnsignedByte();
        if (slotCount != 1 && slotCount != 2) {
            throw new IOException("Invalid vault slot count");
        }
        List<VaultSlot> slots = new ArrayList<>(slotCount);
        slots.add(VaultSlot.read(input));
        if (slotCount == 2) {
            slots.add(VaultSlot.read(input));
        }
        if (input.available() != 0) {
            throw new IOException("Trailing vault bytes");
        }
        return new VaultEnvelope(vaultId, parameters, slots);
    }
}
