package com.cretorix.securepasswordmanager.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.cretorix.securepasswordmanager.security.PinPolicy;

/**
 * Printable transport for the user's passphrase.
 *
 * <p>This QR intentionally contains the passphrase in recoverable form. It is therefore
 * equivalent to writing the passphrase on paper and must be stored separately from RQR2.
 * Possession of this QR alone still cannot open VLT3 because the independent RQR2 secret
 * is also required.</p>
 */
public final class PassphraseQrPayload {
    private static final int VERSION = 1;
    private static final int MAX_LENGTH = 256;
    public final byte[] vaultId;
    public final char[] passphrase;
    public final byte[] checksum;

    public PassphraseQrPayload(byte[] vaultId, char[] passphrase) {
        if (vaultId == null || vaultId.length != 16 || passphrase == null
                || !PinPolicy.isValid(passphrase)) {
            throw new IllegalArgumentException("Invalid passphrase QR");
        }
        this.vaultId = vaultId.clone();
        this.passphrase = passphrase.clone();
        try {
            this.checksum = Crypto.sha256(body());
        } catch (java.security.GeneralSecurityException | IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String toQrString() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.write(body());
        output.write(checksum);
        output.flush();
        String result = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes.toByteArray());
        if (result.length() > MAX_LENGTH) {
            throw new IOException("Passphrase QR is too large");
        }
        return result;
    }

    private byte[] body() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x52504e31); // RPN1
        output.writeInt(VERSION);
        output.write(vaultId);
        byte[] encoded = new String(passphrase).getBytes(StandardCharsets.UTF_8);
        output.writeShort(encoded.length);
        output.write(encoded);
        output.flush();
        return bytes.toByteArray();
    }

    public static PassphraseQrPayload fromQrString(String value) throws IOException {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_LENGTH) {
            throw new IOException("Invalid passphrase QR");
        }
        final byte[] encoded;
        try {
            encoded = Base64.getUrlDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid passphrase QR encoding", exception);
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        if (input.readInt() != 0x52504e31 || input.readInt() != VERSION) {
            throw new IOException("Unsupported passphrase QR");
        }
        byte[] vaultId = new byte[16];
        input.readFully(vaultId);
        int length = input.readUnsignedShort();
        if (length < 12 || length > 128) {
            throw new IOException("Invalid passphrase QR length");
        }
        byte[] passphraseBytes = new byte[length];
        input.readFully(passphraseBytes);
        byte[] checksum = new byte[32];
        input.readFully(checksum);
        if (input.available() != 0) {
            Crypto.wipe(passphraseBytes);
            throw new IOException("Trailing passphrase QR data");
        }
        char[] passphrase = new String(passphraseBytes, StandardCharsets.UTF_8).toCharArray();
        final PassphraseQrPayload payload;
        try {
            payload = new PassphraseQrPayload(vaultId, passphrase);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid passphrase QR passphrase", exception);
        } finally {
            java.util.Arrays.fill(passphrase, '\0');
            Crypto.wipe(passphraseBytes);
        }
        if (!Crypto.constantTimeEquals(checksum, payload.checksum)) {
            java.util.Arrays.fill(payload.passphrase, '\0');
            throw new IOException("Corrupt passphrase QR");
        }
        return payload;
    }
}
