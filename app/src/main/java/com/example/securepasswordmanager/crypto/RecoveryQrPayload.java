package com.example.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * RQR2 is a transport for an independent random recovery secret, never for a DEK.
 * The checksum is only an input-integrity aid; the vault's AES-GCM AAD remains the
 * authoritative authentication of every identifier and KDF parameter.
 */
public final class RecoveryQrPayload {
    private static final int VERSION = 2;
    private static final int MAX_QR_LENGTH = 16_384;
    public final byte[] vaultId;
    public final byte[] qrSecret;
    public final byte[] qrId;
    public final KdfParameters kdfParameters;
    public final byte[] wrapSalt;
    public final byte[] checksum;

    /** Source compatibility for the old RQR1 unit fixture; its DEK box is intentionally ignored. */
    @Deprecated
    public RecoveryQrPayload(byte[] vaultId, byte[] ignoredSlotId, KdfParameters legacy,
                             Crypto.SealedBox ignoredWrappedDek) {
        this(vaultId, Crypto.randomBytes(Crypto.KEY_SIZE),
                new KdfParameters(Crypto.randomBytes(KdfParameters.SALT_SIZE),
                        KdfParameters.DEFAULT_MEMORY_KIB, KdfParameters.DEFAULT_TIME_COST,
                        KdfParameters.DEFAULT_PARALLELISM),
                Crypto.randomBytes(16));
    }

    public RecoveryQrPayload(byte[] vaultId, byte[] qrSecret, KdfParameters parameters,
                             byte[] wrapSalt) {
        if (vaultId == null || vaultId.length != 16 || qrSecret == null
                || qrSecret.length != Crypto.KEY_SIZE || wrapSalt == null || wrapSalt.length != 16
                || !KdfParameters.ALGORITHM.equals(parameters.algorithm)) {
            throw new IllegalArgumentException("Invalid RQR2 payload");
        }
        try {
            this.vaultId = vaultId.clone();
            this.qrSecret = qrSecret.clone();
            this.qrId = Crypto.sha256(qrSecret);
            this.kdfParameters = parameters;
            this.wrapSalt = wrapSalt.clone();
            this.checksum = Crypto.sha256(body());
        } catch (java.security.GeneralSecurityException | IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String toQrString() throws IOException {
        byte[] body = body();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.write(body);
        output.write(checksum);
        output.flush();
        String result = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        if (result.length() > MAX_QR_LENGTH) {
            throw new IOException("Recovery QR is too large");
        }
        return result;
    }

    private byte[] body() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x52515232);
        output.writeInt(VERSION);
        output.write(vaultId);
        output.write(qrSecret);
        output.write(qrId);
        kdfParameters.writeV2(output);
        output.write(wrapSalt);
        output.flush();
        return bytes.toByteArray();
    }

    public static RecoveryQrPayload fromQrString(String value) throws IOException {
        if (value == null || value.trim().length() == 0 || value.length() > MAX_QR_LENGTH) {
            throw new IOException("Invalid recovery QR");
        }
        final byte[] encoded;
        try {
            encoded = Base64.getUrlDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid recovery QR encoding", exception);
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != 0x52515232 || input.readInt() != VERSION) {
            throw new IOException("Unsupported recovery QR");
        }
        byte[] vaultId = new byte[16];
        byte[] secret = new byte[Crypto.KEY_SIZE];
        byte[] qrId = new byte[32];
        input.readFully(vaultId);
        input.readFully(secret);
        input.readFully(qrId);
        KdfParameters parameters = KdfParameters.readV2(input);
        byte[] wrapSalt = new byte[16];
        input.readFully(wrapSalt);
        byte[] suppliedChecksum = new byte[32];
        input.readFully(suppliedChecksum);
        if (input.available() != 0) {
            throw new IOException("Trailing recovery QR data");
        }
        RecoveryQrPayload payload = new RecoveryQrPayload(vaultId, secret, parameters, wrapSalt);
        if (!Crypto.constantTimeEquals(qrId, payload.qrId)
                || !Crypto.constantTimeEquals(suppliedChecksum, payload.checksum)) {
            throw new IOException("Corrupt recovery QR");
        }
        return payload;
    }
}
