package com.example.securepasswordmanager.repository;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.example.securepasswordmanager.crypto.Crypto;
import com.example.securepasswordmanager.crypto.KdfParameters;
import com.example.securepasswordmanager.crypto.PinKdf;
import com.example.securepasswordmanager.crypto.PassphraseQrPayload;
import com.example.securepasswordmanager.crypto.RecoveryQrPayload;
import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultDataCodec;
import com.example.securepasswordmanager.crypto.VaultEnvelope;
import com.example.securepasswordmanager.crypto.VaultSlot;
import com.example.securepasswordmanager.security.DeviceKeyManager;
import com.example.securepasswordmanager.security.PinPolicy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;

/**
 * VLT2 persistence boundary.
 *
 * <p>The attacker is assumed to know this source, the APK and a copied vault. VLT2
 * therefore has no PIN verifier and no PIN-only unwrap: recovery needs both the
 * passphrase and the independent 256-bit secret carried by RQR2. The local RSA key
 * is non-exportable and authentication-bound; it is only a daily biometric wrapper.</p>
 */
public final class VaultRepository {
    private static final String VAULT_FILE = "vault.vlt";
    private static final String PREFS = "local_vault_state";
    private static final String PREF_FAILED = "failed_attempts";
    private static final String PREF_LOCKED_UNTIL = "locked_until";
    private static final String PREF_REMOTE_URI = "remote_uri";
    private static final String PREF_QR_WRAPPED = "qr_wrapped";
    private static final int MAX_FAILURES_BEFORE_DELAY = 5;
    private static final int MAX_REMOTE_BYTES = 256 * 1024 * 1024;
    private static final byte[] KEK_INFO = Crypto.utf8("SPM/VLT2/combined-kek");

    private final Context context;
    private final File vaultFile;
    private final SharedPreferences state;
    private final DeviceKeyManager keyManager;
    /* Held only long enough to display the newly generated QR; never persisted or logged. */
    private byte[] lastQrSecret;

    public VaultRepository(Context context) throws GeneralSecurityException, IOException {
        this.context = context.getApplicationContext();
        vaultFile = new File(this.context.getFilesDir(), VAULT_FILE);
        state = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        keyManager = new DeviceKeyManager();
    }

    public boolean exists() {
        return vaultFile.isFile() && vaultFile.length() > 0;
    }

    /** Deletes only local vault material; user-selected external copies remain untouched. */
    public void deleteLocalVault() throws IOException {
        java.io.File temporary = new File(context.getFilesDir(), VAULT_FILE + ".tmp");
        if (vaultFile.exists() && !vaultFile.delete()) {
            throw new IOException("Could not delete local vault");
        }
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Could not delete temporary vault");
        }
        state.edit().clear().apply();
    }

    public boolean isLegacyVault() throws IOException {
        return exists() && VaultEnvelope.decode(readVaultBytes()).version != VaultEnvelope.VERSION;
    }

    public void create(char[] passphrase) throws GeneralSecurityException, IOException {
        requirePassphrase(passphrase);
        KdfParameters parameters = new KdfParameters(Crypto.randomBytes(KdfParameters.SALT_SIZE),
                KdfParameters.DEFAULT_MEMORY_KIB, KdfParameters.DEFAULT_TIME_COST,
                KdfParameters.DEFAULT_PARALLELISM);
        byte[] vaultId = Crypto.randomBytes(16);
        byte[] wrapSalt = Crypto.randomBytes(16);
        byte[] qrSecret = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] qrId = Crypto.sha256(qrSecret);
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        String alias = DeviceKeyManager.alias(vaultId, "bio");
        try {
            keyManager.ensureKey(alias, true);
            VaultData data = VaultData.empty();
            VaultEnvelope envelope = new VaultEnvelope(vaultId, parameters, wrapSalt, qrId,
                    makeSlot(vaultId, parameters, wrapSalt, qrId, passphrase, qrSecret, dek,
                            data, VaultDataCodec.paddedLength(data), alias));
            writeEnvelope(envelope);
            state.edit().putString(PREF_QR_WRAPPED,
                    java.util.Base64.getEncoder().encodeToString(keyManager.wrap(alias, qrSecret)))
                    .apply();
            lastQrSecret = qrSecret.clone();
        } finally {
            Crypto.wipe(qrSecret);
            Crypto.wipe(qrId);
            Crypto.wipe(dek);
        }
    }

    /**
     * Retained as a compatibility entry point, but deliberately cannot perform a PIN-only
     * unlock. New callers must provide RQR2 as well.
     */
    public OpenResult openWithPin(char[] passphrase)
            throws GeneralSecurityException, IOException {
        throw new GeneralSecurityException("VLT2 recovery requires passphrase and QR");
    }

    public OpenResult openWithPinAndQr(char[] passphrase, RecoveryQrPayload recovery)
            throws GeneralSecurityException, IOException {
        enforceRateLimit();
        requirePassphrase(passphrase);
        VaultEnvelope envelope = readEnvelope();
        verifyRecoveryMetadata(envelope, recovery);
        byte[] dek = unwrapWithPassphrase(envelope, passphrase, recovery.qrSecret);
        try {
            VaultData data = decryptData(envelope, dek);
            state.edit().putInt(PREF_FAILED, 0).putLong(PREF_LOCKED_UNTIL, 0).apply();
            return new OpenResult(data, dek);
        } catch (GeneralSecurityException | IOException exception) {
            registerFailure();
            throw exception;
        } finally {
            Crypto.wipe(dek);
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
            return new OpenResult(decryptData(envelope, dek), dek);
        } finally {
            Crypto.wipe(dek);
        }
    }

    public RecoveryQrPayload createRecoveryPayload()
            throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        if (lastQrSecret == null) {
            throw new GeneralSecurityException("QR secret is not available; use the original QR");
        }
        return new RecoveryQrPayload(envelope.vaultId, lastQrSecret, envelope.kdfParameters,
                envelope.wrapSalt);
    }

    public Cipher prepareRecoveryQrCipher() throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        return keyManager.prepareDecrypt(DeviceKeyManager.alias(envelope.vaultId, "bio"));
    }

    public RecoveryQrPayload openRecoveryQr(Cipher authenticatedCipher)
            throws GeneralSecurityException, IOException {
        String encoded = state.getString(PREF_QR_WRAPPED, null);
        if (encoded == null) {
            throw new GeneralSecurityException("Local QR secret is unavailable");
        }
        byte[] wrapped;
        try {
            wrapped = java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Corrupt local QR secret", exception);
        }
        byte[] qrSecret = keyManager.decrypt(authenticatedCipher, wrapped);
        try {
            VaultEnvelope envelope = readEnvelope();
            lastQrSecret = qrSecret.clone();
            return new RecoveryQrPayload(envelope.vaultId, qrSecret,
                    envelope.kdfParameters, envelope.wrapSalt);
        } finally {
            Crypto.wipe(qrSecret);
        }
    }

    public PassphraseQrPayload createPassphraseQrPayload(char[] passphrase)
            throws GeneralSecurityException, IOException {
        requirePassphrase(passphrase);
        VaultEnvelope envelope = readEnvelope();
        return new PassphraseQrPayload(envelope.vaultId, passphrase);
    }

    public byte[] readVaultBytes() throws IOException {
        return java.nio.file.Files.readAllBytes(vaultFile.toPath());
    }

    public void restore(byte[] vaultBytes, RecoveryQrPayload recovery, char[] passphrase)
            throws GeneralSecurityException, IOException {
        requirePassphrase(passphrase);
        VaultEnvelope envelope = decodeV2(vaultBytes);
        verifyRecoveryMetadata(envelope, recovery);
        byte[] dek = unwrapWithPassphrase(envelope, passphrase, recovery.qrSecret);
        try {
            // Authenticate the database before replacing the local device wrapper.
            decryptData(envelope, dek);
            String alias = DeviceKeyManager.alias(envelope.vaultId, "bio");
            keyManager.replaceAuthenticatedKey(alias);
            VaultSlot updated = replaceHardwareBox(envelope.slots.get(0), dek, alias);
            writeEnvelope(new VaultEnvelope(envelope.vaultId, envelope.kdfParameters,
                    envelope.wrapSalt, envelope.qrId, updated));
            state.edit().putString(PREF_QR_WRAPPED,
                    java.util.Base64.getEncoder().encodeToString(
                            keyManager.wrap(alias, recovery.qrSecret)))
                    .apply();
            lastQrSecret = recovery.qrSecret.clone();
        } finally {
            Crypto.wipe(dek);
        }
    }

    public void updateWithTruePin(char[] passphrase, VaultData updated)
            throws GeneralSecurityException, IOException {
        if (lastQrSecret == null) {
            throw new GeneralSecurityException("Passphrase changes require the recovery QR");
        }
        VaultEnvelope envelope = readEnvelope();
        byte[] dek = unwrapWithPassphrase(envelope, passphrase, lastQrSecret);
        try {
            writeEnvelope(withSlot(envelope, replaceData(envelope, dek, updated)));
        } finally {
            Crypto.wipe(dek);
        }
    }

    public void updateWithBiometric(Cipher authenticatedCipher, VaultData updated)
            throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        byte[] dek = keyManager.decrypt(authenticatedCipher,
                envelope.slots.get(0).hardwareWrappedDek);
        try {
            writeEnvelope(withSlot(envelope, replaceData(envelope, dek, updated)));
        } finally {
            Crypto.wipe(dek);
        }
    }

    public void changeTruePin(char[] oldPassphrase, char[] newPassphrase)
            throws GeneralSecurityException, IOException {
        requirePassphrase(oldPassphrase);
        requirePassphrase(newPassphrase);
        if (lastQrSecret == null) {
            throw new GeneralSecurityException("Passphrase changes require the recovery QR");
        }
        VaultEnvelope envelope = readEnvelope();
        byte[] dek = unwrapWithPassphrase(envelope, oldPassphrase, lastQrSecret);
        try {
            byte[] combined = combinedKek(newPassphrase, lastQrSecret, envelope);
            VaultSlot old = envelope.slots.get(0);
            VaultSlot updated = new VaultSlot(old.id,
                    Crypto.seal(combined, dek, aad(envelope, old, "dek-wrap")),
                    old.hardwareWrappedDek, old.encryptedData);
            Crypto.wipe(combined);
            writeEnvelope(new VaultEnvelope(envelope.vaultId, envelope.kdfParameters,
                    envelope.wrapSalt, envelope.qrId, updated));
        } finally {
            Crypto.wipe(dek);
        }
    }

    public boolean isHardwareBacked() throws GeneralSecurityException, IOException {
        VaultEnvelope envelope = readEnvelope();
        return keyManager.isHardwareBacked(DeviceKeyManager.alias(envelope.vaultId, "bio"));
    }

    private VaultSlot makeSlot(byte[] vaultId, KdfParameters parameters, byte[] wrapSalt,
                               byte[] qrId, char[] passphrase, byte[] qrSecret, byte[] dek,
                               VaultData data, int targetLength, String alias)
            throws GeneralSecurityException, IOException {
        byte[] slotId = Crypto.randomBytes(16);
        VaultSlot contextSlot = new VaultSlot(slotId, Crypto.seal(new byte[32], dek,
                        Crypto.utf8("placeholder")), new byte[]{2}, Crypto.seal(dek,
                        VaultDataCodec.encode(data, targetLength), Crypto.utf8("placeholder")));
        VaultEnvelope context = new VaultEnvelope(vaultId, parameters, wrapSalt, qrId, contextSlot);
        byte[] combined = combinedKek(passphrase, qrSecret, context);
        try {
            return new VaultSlot(slotId,
                Crypto.seal(combined, dek, aad(context, contextSlot, "dek-wrap")),
                keyManager.wrap(alias, dek),
                Crypto.seal(dek, VaultDataCodec.encode(data, targetLength),
                        aad(context, contextSlot, "data")));
        } finally {
            Crypto.wipe(combined);
        }
    }

    private VaultData decryptData(VaultEnvelope envelope, byte[] dek)
            throws GeneralSecurityException, IOException {
        VaultSlot slot = envelope.slots.get(0);
        return VaultDataCodec.decode(Crypto.open(dek, slot.encryptedData,
                aad(envelope, slot, "data")));
    }

    private byte[] unwrapWithPassphrase(VaultEnvelope envelope, char[] passphrase,
                                        byte[] qrSecret)
            throws GeneralSecurityException {
        VaultSlot slot = envelope.slots.get(0);
        byte[] combined = combinedKek(passphrase, qrSecret, envelope);
        try {
            return Crypto.open(combined, slot.pinWrappedDek, aad(envelope, slot, "dek-wrap"));
        } finally {
            Crypto.wipe(combined);
        }
    }

    private byte[] combinedKek(char[] passphrase, byte[] qrSecret, VaultEnvelope envelope)
            throws GeneralSecurityException {
        byte[] pinKey = PinKdf.derive(passphrase, envelope.kdfParameters);
        byte[] ikm = concat(Crypto.utf8("SPM/VLT2/ikm"), pinKey, qrSecret);
        try {
            return Crypto.hkdf(ikm, envelope.wrapSalt, KEK_INFO, Crypto.KEY_SIZE);
        } finally {
            Crypto.wipe(pinKey);
            Crypto.wipe(ikm);
        }
    }

    private VaultSlot replaceHardwareBox(VaultSlot slot, byte[] dek, String alias)
            throws GeneralSecurityException {
        return new VaultSlot(slot.id, slot.pinWrappedDek, keyManager.wrap(alias, dek),
                slot.encryptedData);
    }

    private VaultSlot replaceData(VaultEnvelope envelope, byte[] dek, VaultData data)
            throws GeneralSecurityException, IOException {
        VaultSlot slot = envelope.slots.get(0);
        return new VaultSlot(slot.id, slot.pinWrappedDek, slot.hardwareWrappedDek,
                Crypto.seal(dek, VaultDataCodec.encode(data, VaultDataCodec.paddedLength(data)),
                        aad(envelope, slot, "data")));
    }

    private static VaultEnvelope withSlot(VaultEnvelope envelope, VaultSlot slot) {
        return new VaultEnvelope(envelope.vaultId, envelope.kdfParameters,
                envelope.wrapSalt, envelope.qrId, slot);
    }

    private void verifyRecoveryMetadata(VaultEnvelope envelope, RecoveryQrPayload recovery)
            throws GeneralSecurityException {
        if (recovery == null || envelope.version != VaultEnvelope.VERSION
                || !Crypto.constantTimeEquals(envelope.vaultId, recovery.vaultId)
                || !Crypto.constantTimeEquals(envelope.qrId, recovery.qrId)
                || !Crypto.constantTimeEquals(envelope.wrapSalt, recovery.wrapSalt)
                || !Crypto.constantTimeEquals(envelope.kdfParameters.salt,
                recovery.kdfParameters.salt)
                || envelope.kdfParameters.memoryKiB != recovery.kdfParameters.memoryKiB
                || envelope.kdfParameters.timeCost != recovery.kdfParameters.timeCost
                || envelope.kdfParameters.parallelism != recovery.kdfParameters.parallelism) {
            throw new GeneralSecurityException("Recovery data does not match VLT2");
        }
    }

    private VaultEnvelope readEnvelope() throws IOException {
        return decodeV2(readVaultBytes());
    }

    private static VaultEnvelope decodeV2(byte[] bytes) throws IOException {
        VaultEnvelope envelope = VaultEnvelope.decode(bytes);
        if (envelope.version != VaultEnvelope.VERSION) {
            throw new IOException("Legacy VLT1 vault detected; export/recreate or migrate explicitly");
        }
        return envelope;
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

    private static byte[] aad(VaultEnvelope envelope, VaultSlot slot, String purpose) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF("SPM/VLT2/" + purpose);
            output.writeInt(envelope.version);
            output.write(envelope.vaultId);
            output.write(envelope.wrapSalt);
            output.write(envelope.qrId);
            output.writeUTF(envelope.kdfParameters.algorithm);
            output.writeInt(envelope.kdfParameters.memoryKiB);
            output.writeInt(envelope.kdfParameters.timeCost);
            output.writeInt(envelope.kdfParameters.parallelism);
            output.write(envelope.kdfParameters.salt);
            if (slot != null) {
                output.write(slot.id);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) size += value.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static void requirePassphrase(char[] value) {
        if (!PinPolicy.isValid(value)) {
            throw new IllegalArgumentException("Passphrase must be 12-24 printable non-numeric characters");
        }
    }

    private void enforceRateLimit() throws GeneralSecurityException {
        if (state.getLong(PREF_LOCKED_UNTIL, 0) > System.currentTimeMillis()) {
            throw new GeneralSecurityException("Unlock temporarily unavailable");
        }
    }

    private void registerFailure() {
        int failures = state.getInt(PREF_FAILED, 0) + 1;
        SharedPreferences.Editor editor = state.edit().putInt(PREF_FAILED, failures);
        if (failures >= MAX_FAILURES_BEFORE_DELAY) {
            editor.putLong(PREF_LOCKED_UNTIL, System.currentTimeMillis()
                    + Math.min(15 * 60_000L, 1_000L
                    << Math.min(failures - MAX_FAILURES_BEFORE_DELAY, 10)));
        }
        editor.apply();
    }

    // External encrypted backup/sync remains unchanged in behavior and validates VLT2 only.
    public boolean hasRemote() { return state.getString(PREF_REMOTE_URI, null) != null; }
    public String remoteLabel() {
        String value = state.getString(PREF_REMOTE_URI, null);
        if (value == null) return null;
        String decoded = Uri.decode(value);
        int slash = decoded.lastIndexOf('/');
        return slash >= 0 && slash < decoded.length() - 1 ? decoded.substring(slash + 1) : decoded;
    }
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
    public void unlinkRemote() { state.edit().remove(PREF_REMOTE_URI).apply(); }
    public void pushRemote() throws IOException { Uri uri = remoteUri(); if (uri != null) writeUri(uri, readVaultBytes()); }
    public boolean pullRemote() throws IOException, GeneralSecurityException {
        Uri uri = remoteUri(); if (uri == null) return false;
        byte[] bytes = readUri(uri);
        decodeV2(bytes);
        writeLocalBytes(bytes);
        return true;
    }
    public void exportCopy(Uri uri) throws IOException { writeUri(uri, readVaultBytes()); }
    public void importCopy(Uri uri) throws IOException { writeLocalBytes(decodeV2(readUri(uri)).encode()); }
    private Uri remoteUri() {
        String value = state.getString(PREF_REMOTE_URI, null);
        return value == null ? null : Uri.parse(value);
    }
    private void writeLocalBytes(byte[] bytes) throws IOException {
        File temporary = new File(context.getFilesDir(), VAULT_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes); output.getFD().sync();
        }
        if (!temporary.renameTo(vaultFile)) throw new IOException("Could not atomically replace vault");
    }
    private void writeUri(Uri uri, byte[] bytes) throws IOException {
        IOException last = null;
        for (String mode : new String[]{"wt", "rwt", "w"}) {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri, mode)) {
                if (output == null) { last = new IOException("External stream unavailable"); continue; }
                output.write(bytes); output.flush(); return;
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException exception) {
                last = new IOException(exception);
            }
        }
        throw last == null ? new IOException("External resource is not writable") : last;
    }
    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("External location is not readable");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int total = 0; int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_REMOTE_BYTES - total) throw new IOException("External vault is too large");
                output.write(buffer, 0, read); total += read;
            }
            return output.toByteArray();
        }
    }

    public static final class OpenResult {
        public final VaultData data;
        private final byte[] dek;
        private OpenResult(VaultData data, byte[] dek) { this.data = data; this.dek = dek.clone(); }
        public void clear() { Crypto.wipe(dek); }
    }
}
