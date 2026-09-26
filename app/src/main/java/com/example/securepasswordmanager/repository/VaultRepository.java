package com.example.securepasswordmanager.repository;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.example.securepasswordmanager.crypto.Crypto;
import com.example.securepasswordmanager.crypto.KdfParameters;
import com.example.securepasswordmanager.crypto.PinKdf;
import com.example.securepasswordmanager.crypto.RecoveryQrPayload;
import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultDataCodec;
import com.example.securepasswordmanager.crypto.VaultEnvelope;
import com.example.securepasswordmanager.crypto.VaultSlot;
import com.example.securepasswordmanager.security.DeviceKeyManager;
import com.example.securepasswordmanager.security.PinPolicy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;

public final class VaultRepository {
    private static final String VAULT_FILE = "vault.vlt";
    private static final String PREFS = "local_vault_state";
    private static final String PREF_VAULT_ID = "vault_id";
    private static final String PREF_TRUE_SLOT = "true_slot";
    private static final String PREF_FAILED = "failed_attempts";
    private static final String PREF_LOCKED_UNTIL = "locked_until";
    private static final String PREF_REMOTE_URI = "remote_uri";
    private static final int MAX_FAILURES_BEFORE_DELAY = 5;
    private static final int MAX_REMOTE_BYTES = 256 * 1024 * 1024;

    private final Context context;
    private final File vaultFile;
    private final SharedPreferences state;
    private final DeviceKeyManager keyManager;

    public VaultRepository(Context context) throws GeneralSecurityException, IOException {
        this.context = context.getApplicationContext();
        this.vaultFile = new File(this.context.getFilesDir(), VAULT_FILE);
        this.state = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.keyManager = new DeviceKeyManager();
    }

    public boolean exists() {
        return vaultFile.isFile() && vaultFile.length() > 0;
    }

    public void create(char[] truePin) throws GeneralSecurityException, IOException {
        if (!PinPolicy.isValid(truePin)) {
            throw new IllegalArgumentException("Invalid PIN");
        }
        KdfParameters parameters = new KdfParameters(
                Crypto.randomBytes(KdfParameters.SALT_SIZE), KdfParameters.DEFAULT_ITERATIONS);
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] slotId = Crypto.randomBytes(16);
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        String bioAlias = DeviceKeyManager.alias(vaultId, "bio");
        // Auth-bound biometric key: its public key wraps the DEK now (no auth needed), the
        // private key unwraps it only after a successful BiometricPrompt.
        keyManager.ensureKey(bioAlias, true);

        VaultData data = VaultData.empty();
        int targetLength = VaultDataCodec.paddedLength(data);
        VaultSlot slot = makeSlot(vaultId, slotId, truePin, parameters, dek, data, targetLength, bioAlias);
        VaultEnvelope envelope = new VaultEnvelope(vaultId, parameters,
                java.util.Collections.singletonList(slot));
        writeEnvelope(envelope);
        state.edit().clear()
                .putString(PREF_VAULT_ID, hex(vaultId))
                .putString(PREF_TRUE_SLOT, hex(slotId))
                .apply();
        Crypto.wipe(dek);
    }

    public OpenResult openWithPin(char[] pin) throws GeneralSecurityException, IOException {
        enforceRateLimit();
        VaultEnvelope envelope = readEnvelope();
        byte[] derived = PinKdf.derive(pin, envelope.kdfParameters);
        try {
            VaultSlot slot = envelope.slots.get(0);
            byte[] expected = verifier(derived, slot.id);
            if (Crypto.constantTimeEquals(expected, slot.pinVerifier)) {
                byte[] dek = Crypto.open(derived, slot.pinWrappedDek,
                        aad(envelope.vaultId, slot.id, "pin"));
                try {
                    VaultData data = VaultDataCodec.decode(Crypto.open(dek, slot.encryptedData,
                            aad(envelope.vaultId, slot.id, "data")));
                    state.edit().putInt(PREF_FAILED, 0).putLong(PREF_LOCKED_UNTIL, 0).apply();
                    return new OpenResult(data, dek);
                } finally {
                    Crypto.wipe(dek);
                }
            }
            Crypto.wipe(expected);
            registerFailure();
            throw new GeneralSecurityException("Invalid PIN");
        } finally {
            Crypto.wipe(derived);
        }
    }

    public Cipher prepareBiometricCipher() throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        return keyManager.prepareDecrypt(DeviceKeyManager.alias(envelope.vaultId, "bio"));
    }

    public OpenResult openWithBiometric(Cipher authenticatedCipher)
            throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        VaultSlot slot = envelope.slots.get(0);
        byte[] dek = keyManager.decrypt(authenticatedCipher, slot.hardwareWrappedDek);
        try {
            VaultData data = VaultDataCodec.decode(Crypto.open(dek, slot.encryptedData,
                    aad(envelope.vaultId, slot.id, "data")));
            return new OpenResult(data, dek);
        } finally {
            Crypto.wipe(dek);
        }
    }

    public RecoveryQrPayload createRecoveryPayload() throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        VaultSlot slot = envelope.slots.get(0);
        byte[] pinWrapped = slot.pinWrappedDek.encode();
        // The QR contains only a PIN-encrypted DEK representation, never the DEK itself.
        Crypto.SealedBox recoveryBox = Crypto.SealedBox.decode(
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(pinWrapped)));
        return new RecoveryQrPayload(envelope.vaultId, slot.id, envelope.kdfParameters, recoveryBox);
    }

    public byte[] readVaultBytes() throws IOException {
        return java.nio.file.Files.readAllBytes(vaultFile.toPath());
    }

    // region External storage sync (Storage Access Framework)

    /** True when the vault is linked to an external document (e.g. Google Drive). */
    public boolean hasRemote() {
        return state.getString(PREF_REMOTE_URI, null) != null;
    }

    public String remoteLabel() {
        String value = state.getString(PREF_REMOTE_URI, null);
        if (value == null) {
            return null;
        }
        String decoded = Uri.decode(value);
        int slash = decoded.lastIndexOf('/');
        return slash >= 0 && slash < decoded.length() - 1 ? decoded.substring(slash + 1) : decoded;
    }

    /**
     * Links the vault to an external document and uploads the current encrypted file.
     * The cloud only ever receives ciphertext; it never holds a decryption key.
     */
    public void linkRemote(Uri uri) throws IOException {
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException exception) {
            throw new IOException("Cannot persist access to the selected location", exception);
        }
        writeUri(uri, readVaultBytes());
        state.edit().putString(PREF_REMOTE_URI, uri.toString()).apply();
    }

    public void unlinkRemote() {
        state.edit().remove(PREF_REMOTE_URI).apply();
    }

    /** Uploads the current local vault to the linked external document. */
    public void pushRemote() throws IOException {
        Uri uri = remoteUri();
        if (uri == null) {
            return;
        }
        writeUri(uri, readVaultBytes());
    }

    /**
     * Downloads the linked external document and makes it the operational vault. The bytes
     * are validated as a vault envelope before replacing the local cache.
     */
    public boolean pullRemote() throws IOException, GeneralSecurityException {
        Uri uri = remoteUri();
        if (uri == null) {
            return false;
        }
        byte[] bytes = readUri(uri);
        VaultEnvelope.decode(bytes);
        writeLocalBytes(bytes);
        return true;
    }

    /** Writes a one-off encrypted backup copy to any location without linking it. */
    public void exportCopy(Uri uri) throws IOException {
        writeUri(uri, readVaultBytes());
    }

    private Uri remoteUri() {
        String value = state.getString(PREF_REMOTE_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    private void writeLocalBytes(byte[] bytes) throws IOException {
        File temporary = new File(context.getFilesDir(), VAULT_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (!temporary.renameTo(vaultFile)) {
            throw new IOException("Could not atomically replace vault");
        }
    }

    private void writeUri(Uri uri, byte[] bytes) throws IOException {
        // Providers differ in which write modes they accept. Google Drive in particular is
        // picky, so try truncating modes first and fall back to plain write.
        String[] modes = {"wt", "rwt", "w"};
        IOException last = null;
        for (String mode : modes) {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri, mode)) {
                if (output == null) {
                    last = new IOException("Brak strumienia zapisu (" + mode + ")");
                    continue;
                }
                output.write(bytes);
                output.flush();
                return;
            } catch (IOException exception) {
                last = exception;
            } catch (UnsupportedOperationException | IllegalArgumentException exception) {
                last = new IOException(exception.getMessage(), exception);
            }
        }
        throw last != null ? last : new IOException("Zewnętrzny zasób nie jest zapisywalny");
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("External location is not readable");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_REMOTE_BYTES - total) {
                    throw new IOException("External vault is too large");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        }
    }

    // endregion

    public void restore(byte[] vaultBytes, RecoveryQrPayload recovery, char[] truePin)
            throws GeneralSecurityException, IOException {
        if (!PinPolicy.isValid(truePin)) {
            throw new GeneralSecurityException("Invalid PIN");
        }
        VaultEnvelope envelope = VaultEnvelope.decode(vaultBytes);
        if (!Crypto.constantTimeEquals(envelope.vaultId, recovery.vaultId)
                || !Crypto.constantTimeEquals(envelope.kdfParameters.salt, recovery.kdfParameters.salt)
                || envelope.kdfParameters.iterations != recovery.kdfParameters.iterations) {
            throw new GeneralSecurityException("Recovery data does not match the vault");
        }
        VaultSlot slot = envelope.slots.get(0);
        if (!Crypto.constantTimeEquals(slot.id, recovery.trueSlotId)) {
            throw new GeneralSecurityException("Recovery data does not match the vault");
        }
        byte[] pinKey = PinKdf.derive(truePin, recovery.kdfParameters);
        byte[] dek = Crypto.open(pinKey, recovery.wrappedDek, aad(envelope.vaultId, slot.id, "pin"));
        try {
            if (!Crypto.constantTimeEquals(verifier(pinKey, slot.id), slot.pinVerifier)) {
                throw new GeneralSecurityException("Recovery PIN is invalid");
            }
            String bioAlias = DeviceKeyManager.alias(envelope.vaultId, "bio");
            keyManager.ensureKey(bioAlias, true);
            VaultSlot updated = replaceHardwareBox(slot, dek, bioAlias);
            writeEnvelope(new VaultEnvelope(envelope.vaultId, envelope.kdfParameters,
                    java.util.Collections.singletonList(updated)));
            state.edit().clear()
                    .putString(PREF_VAULT_ID, hex(envelope.vaultId))
                    .putString(PREF_TRUE_SLOT, hex(slot.id))
                    .apply();
        } finally {
            Crypto.wipe(pinKey);
            Crypto.wipe(dek);
        }
    }

    public void updateWithTruePin(char[] truePin, VaultData updated)
            throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        byte[] key = PinKdf.derive(truePin, envelope.kdfParameters);
        try {
            VaultSlot slot = findByPin(envelope, key);
            byte[] dek = unwrapSlot(envelope, key, slot);
            try {
                VaultSlot updatedSlot = replaceData(envelope, slot, dek, updated,
                        VaultDataCodec.paddedLength(updated));
                writeEnvelope(new VaultEnvelope(envelope.vaultId, envelope.kdfParameters,
                        java.util.Collections.singletonList(updatedSlot)));
            } finally {
                Crypto.wipe(dek);
            }
        } finally {
            Crypto.wipe(key);
        }
    }

    public void updateWithBiometric(Cipher authenticatedCipher, VaultData updated)
            throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        VaultSlot slot = envelope.slots.get(0);
        byte[] dek = keyManager.decrypt(authenticatedCipher, slot.hardwareWrappedDek);
        try {
            VaultSlot updatedSlot = replaceData(envelope, slot, dek, updated,
                    VaultDataCodec.paddedLength(updated));
            writeEnvelope(new VaultEnvelope(envelope.vaultId, envelope.kdfParameters,
                    java.util.Collections.singletonList(updatedSlot)));
        } finally {
            Crypto.wipe(dek);
        }
    }

    public void changeTruePin(char[] oldTruePin, char[] newTruePin)
            throws GeneralSecurityException, IOException {
        if (!PinPolicy.isValid(oldTruePin) || !PinPolicy.isValid(newTruePin)) {
            throw new GeneralSecurityException("PIN must contain 6 to 12 digits");
        }
        VaultEnvelope envelope = readEnvelope();
        byte[] oldKey = PinKdf.derive(oldTruePin, envelope.kdfParameters);
        byte[] newKey = PinKdf.derive(newTruePin, envelope.kdfParameters);
        try {
            VaultSlot slot = findByPin(envelope, oldKey);
            byte[] dek = unwrapSlot(envelope, oldKey, slot);
            try {
                VaultSlot updated = new VaultSlot(slot.id, verifier(newKey, slot.id),
                        Crypto.seal(newKey, dek, aad(envelope.vaultId, slot.id, "pin")),
                        slot.hardwareWrappedDek, slot.encryptedData);
                writeEnvelope(new VaultEnvelope(envelope.vaultId, envelope.kdfParameters,
                        java.util.Collections.singletonList(updated)));
            } finally {
                Crypto.wipe(dek);
            }
        } finally {
            Crypto.wipe(oldKey);
            Crypto.wipe(newKey);
        }
    }

    public boolean isHardwareBacked() throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        return keyManager.isHardwareBacked(DeviceKeyManager.alias(envelope.vaultId, "bio"));
    }

    private VaultSlot makeSlot(byte[] vaultId, byte[] slotId, char[] pin, KdfParameters parameters,
                               byte[] dek, VaultData data, int targetLength, String hardwareAlias)
            throws GeneralSecurityException, IOException {
        byte[] pinKey = PinKdf.derive(pin, parameters);
        try {
            Crypto.SealedBox wrapped = Crypto.seal(pinKey, dek, aad(vaultId, slotId, "pin"));
            byte[] hardware = keyManager.wrap(hardwareAlias, dek);
            Crypto.SealedBox encrypted = Crypto.seal(dek,
                    VaultDataCodec.encode(data, targetLength), aad(vaultId, slotId, "data"));
            return new VaultSlot(slotId, verifier(pinKey, slotId), wrapped, hardware, encrypted);
        } finally {
            Crypto.wipe(pinKey);
        }
    }

    private VaultSlot replaceHardwareBox(VaultSlot slot, byte[] dek, String alias)
            throws GeneralSecurityException {
        return new VaultSlot(slot.id, slot.pinVerifier, slot.pinWrappedDek,
                keyManager.wrap(alias, dek), slot.encryptedData);
    }

    private VaultSlot replaceData(VaultEnvelope envelope, VaultSlot slot, byte[] dek,
                                  VaultData data, int targetLength)
            throws GeneralSecurityException, IOException {
        return new VaultSlot(slot.id, slot.pinVerifier, slot.pinWrappedDek, slot.hardwareWrappedDek,
                Crypto.seal(dek, VaultDataCodec.encode(data, targetLength),
                        aad(envelope.vaultId, slot.id, "data")));
    }

    private byte[] unwrapSlot(VaultEnvelope envelope, byte[] key, VaultSlot slot)
            throws GeneralSecurityException {
        return Crypto.open(key, slot.pinWrappedDek, aad(envelope.vaultId, slot.id, "pin"));
    }

    private VaultSlot findByPin(VaultEnvelope envelope, byte[] key) throws GeneralSecurityException {
        for (VaultSlot slot : envelope.slots) {
            if (Crypto.constantTimeEquals(verifier(key, slot.id), slot.pinVerifier)) {
                return slot;
            }
        }
        throw new GeneralSecurityException("PIN does not match the vault");
    }

    private VaultEnvelope readEnvelope() throws IOException {
        return VaultEnvelope.decode(readVaultBytes());
    }

    private void writeEnvelope(VaultEnvelope envelope) throws IOException {
        File temporary = new File(context.getFilesDir(), VAULT_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(envelope.encode());
            output.getFD().sync();
        }
        if (!temporary.renameTo(vaultFile)) {
            throw new IOException("Could not atomically replace vault");
        }
    }

    private void enforceRateLimit() throws GeneralSecurityException {
        long lockedUntil = state.getLong(PREF_LOCKED_UNTIL, 0);
        long now = System.currentTimeMillis();
        if (lockedUntil > now) {
            throw new GeneralSecurityException("Unlock temporarily unavailable");
        }
    }

    private void registerFailure() {
        int failures = state.getInt(PREF_FAILED, 0) + 1;
        SharedPreferences.Editor editor = state.edit().putInt(PREF_FAILED, failures);
        if (failures >= MAX_FAILURES_BEFORE_DELAY) {
            long delay = Math.min(15 * 60_000L, 1_000L << Math.min(failures - MAX_FAILURES_BEFORE_DELAY, 10));
            editor.putLong(PREF_LOCKED_UNTIL, System.currentTimeMillis() + delay);
        }
        editor.apply();
    }

    private static byte[] verifier(byte[] key, byte[] slotId) throws GeneralSecurityException {
        return Crypto.hmacSha256(key, concat(Crypto.utf8("SPM/slot-check/v1"), slotId));
    }

    private static byte[] aad(byte[] vaultId, byte[] slotId, String purpose) {
        return concat(Crypto.utf8("SPM/" + purpose + "/v1"), vaultId, slotId);
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) {
            size += value.length;
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) {
            result.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        }
        return result.toString();
    }

    public static final class OpenResult {
        public final VaultData data;
        private final byte[] dek;

        private OpenResult(VaultData data, byte[] dek) {
            this.data = data;
            this.dek = dek.clone();
            Crypto.wipe(dek);
        }

        public void clear() {
            Crypto.wipe(dek);
        }
    }
}
