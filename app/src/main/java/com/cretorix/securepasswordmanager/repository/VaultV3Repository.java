package com.cretorix.securepasswordmanager.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import com.cretorix.securepasswordmanager.crypto.Crypto;
import com.cretorix.securepasswordmanager.crypto.KdfParameters;
import com.cretorix.securepasswordmanager.crypto.PassphraseQrPayload;
import com.cretorix.securepasswordmanager.crypto.PendingVaultAsset;
import com.cretorix.securepasswordmanager.crypto.RecoveryQrPayload;
import com.cretorix.securepasswordmanager.crypto.VaultData;
import com.cretorix.securepasswordmanager.crypto.VaultBackupV3;
import com.cretorix.securepasswordmanager.crypto.VaultDirectorySync;
import com.cretorix.securepasswordmanager.crypto.VaultHeaderStore;
import com.cretorix.securepasswordmanager.crypto.VaultHeaderV3;
import com.cretorix.securepasswordmanager.crypto.VaultItem;
import com.cretorix.securepasswordmanager.crypto.VaultManifest;
import com.cretorix.securepasswordmanager.crypto.VaultManifestCodec;
import com.cretorix.securepasswordmanager.crypto.VaultManifestStore;
import com.cretorix.securepasswordmanager.crypto.VaultObjectIds;
import com.cretorix.securepasswordmanager.crypto.VaultObjectRef;
import com.cretorix.securepasswordmanager.crypto.VaultObjectStore;
import com.cretorix.securepasswordmanager.crypto.VaultSizeLimitException;
import com.cretorix.securepasswordmanager.crypto.VaultTextDataCodec;
import com.cretorix.securepasswordmanager.crypto.VaultV3Format;
import com.cretorix.securepasswordmanager.crypto.VaultV3KeyDerivation;
import com.cretorix.securepasswordmanager.security.DeviceKeyManager;
import com.cretorix.securepasswordmanager.security.PinPolicy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;

/** Persistence and key lifecycle for the copy-on-write VLT3 vault. */
public final class VaultV3Repository {
    public static final class PassphraseQrMismatchException extends GeneralSecurityException {
        PassphraseQrMismatchException(Throwable cause) {
            super("Passphrase does not match the current vault", cause);
        }
    }

    private static final String PREFS = "local_vault_state_v3";
    private static final String PREF_FAILED = "failed_attempts";
    private static final String PREF_LOCKED_UNTIL = "locked_until";
    private static final String PREF_QR_WRAPPED_PREFIX = "qr_wrapped_";
    private static final String PREF_REMOTE_URI = "remote_uri";
    private static final int MAX_FAILURES_BEFORE_DELAY = 5;
    private final Context context;
    private final File root;
    private final File previousRoot;
    private final File importRoot;
    private final VaultHeaderStore headerStore;
    private final VaultManifestStore manifestStore;
    private final SharedPreferences state;
    private final DeviceKeyManager keyManager;
    private final Map<String, Integer> backupPins = new HashMap<>();
    private byte[] lastQrSecret;

    public VaultV3Repository(Context context) throws GeneralSecurityException, IOException {
        this(context, new File(context.getApplicationContext().getFilesDir(), "vault-v3"),
                PREFS);
    }

    public VaultV3Repository(Context context, File rootDirectory, String preferencesName)
            throws GeneralSecurityException, IOException {
        this.context = context.getApplicationContext();
        if (rootDirectory == null || preferencesName == null || preferencesName.isEmpty()) {
            throw new IllegalArgumentException("Invalid VLT3 repository location");
        }
        root = rootDirectory;
        File repositoryParent = root.getParentFile();
        if (repositoryParent == null) {
            throw new IOException("VLT3 repository needs a parent directory");
        }
        previousRoot = new File(repositoryParent, root.getName() + ".previous");
        importRoot = new File(repositoryParent, root.getName() + ".import");
        VaultRootTransaction.recover(root, previousRoot, VaultDirectorySync::sync);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create VLT3 directory");
        }

        if (importRoot.exists()) {
            deleteManagedDirectory(importRoot);
        }
        headerStore = new VaultHeaderStore(new File(root, "header.bin"));
        manifestStore = new VaultManifestStore(root);
        state = this.context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        keyManager = new DeviceKeyManager();
    }

    public boolean exists() {
        return headerStore.exists() && manifestStore.hasCurrent();
    }

    public synchronized void create(char[] passphrase)
            throws GeneralSecurityException, IOException {
        requirePassphrase(passphrase);
        if (headerStore.exists()) {
            throw new IOException("A VLT3 vault already exists");
        }
        cleanupIncompleteCreation();
        KdfParameters parameters = new KdfParameters(Crypto.randomBytes(KdfParameters.SALT_SIZE),
                KdfParameters.DEFAULT_MEMORY_KIB, KdfParameters.DEFAULT_TIME_COST,
                KdfParameters.DEFAULT_PARALLELISM);
        byte[] vaultId = Crypto.randomBytes(VaultHeaderV3.VAULT_ID_SIZE);
        byte[] wrapSalt = Crypto.randomBytes(VaultHeaderV3.WRAP_SALT_SIZE);
        byte[] qrSecret = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] qrId = Crypto.sha256(qrSecret);
        byte[] hardwareKeyId = Crypto.randomBytes(VaultHeaderV3.HARDWARE_KEY_ID_SIZE);
        byte[] dek = Crypto.randomBytes(Crypto.KEY_SIZE);
        byte[] headerGeneration = VaultObjectIds.randomId();
        String alias = deviceAlias(vaultId, hardwareKeyId);
        boolean headerCommitted = false;
        try {
            keyManager.ensureKey(alias, true);
            if (!keyManager.isHardwareBacked(alias)) {
                throw new GeneralSecurityException("Hardware-backed key unavailable");
            }
            Crypto.SealedBox pinWrapper = VaultV3KeyDerivation.wrapDek(dek, passphrase,
                    qrSecret, vaultId, parameters, wrapSalt, qrId, hardwareKeyId,
                    headerGeneration);
            VaultHeaderV3 header = new VaultHeaderV3(vaultId, parameters, wrapSalt, qrId,
                    hardwareKeyId, headerGeneration, pinWrapper, keyManager.wrap(alias, dek));

            byte[] encodedText = VaultTextDataCodec.encode(VaultData.empty());
            VaultObjectRef textRef;
            try {
                textRef = objectStore(header).write(dek,
                        VaultObjectRef.Type.TEXT_BLOCK, null, "", encodedText);
            } finally {
                Crypto.wipe(encodedText);
            }
            VaultManifest manifest = new VaultManifest(VaultObjectIds.randomId(), textRef,
                    new ArrayList<>());
            manifestStore.writeInitial(VaultManifestCodec.seal(manifest, dek, vaultId));
            String wrappedQr = java.util.Base64.getEncoder().encodeToString(
                    keyManager.wrap(alias, qrSecret));
            if (!state.edit().putString(qrPreferenceKey(header), wrappedQr)
                    .putInt(PREF_FAILED, 0).putLong(PREF_LOCKED_UNTIL, 0).commit()) {
                throw new IOException("Could not persist local recovery QR wrapper");
            }
            headerStore.write(header);
            headerCommitted = true;
            replaceLastQrSecret(qrSecret);
        } finally {
            if (!headerCommitted && !headerStore.exists()) {
                try {
                    keyManager.deleteKey(alias);
                } catch (GeneralSecurityException exception) {
                    android.util.Log.w("SPM_VLT3", "Could not remove incomplete device key",
                            exception);
                }
            }
            Crypto.wipe(vaultId);
            Crypto.wipe(wrapSalt);
            Crypto.wipe(qrSecret);
            Crypto.wipe(qrId);
            Crypto.wipe(hardwareKeyId);
            Crypto.wipe(dek);
            Crypto.wipe(headerGeneration);
        }
    }

    public Cipher prepareDeviceAuthCipher() throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        return keyManager.prepareDecrypt(deviceAlias(header.vaultId, header.hardwareKeyId));
    }

    public OpenResult openWithDeviceAuth(Cipher authenticatedCipher)
            throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        byte[] dek = keyManager.decrypt(authenticatedCipher, header.hardwareWrappedDek);
        try {
            return openWithDek(header, dek);
        } finally {
            Crypto.wipe(dek);
        }
    }

    public OpenResult openWithPassphraseAndQr(char[] passphrase, RecoveryQrPayload recovery)
            throws IOException, GeneralSecurityException {
        enforceRateLimit();
        requirePassphrase(passphrase);
        VaultHeaderV3 header = headerStore.read();
        verifyRecoveryMetadata(header, recovery);
        byte[] dek;
        try {
            dek = VaultV3KeyDerivation.unwrapDek(header, passphrase, recovery.qrSecret);
        } catch (GeneralSecurityException exception) {
            registerFailure();
            throw exception;
        }
        try {
            OpenResult result = openWithDek(header, dek);
            state.edit().putInt(PREF_FAILED, 0).putLong(PREF_LOCKED_UNTIL, 0).apply();
            replaceLastQrSecret(recovery.qrSecret);
            return result;
        } catch (GeneralSecurityException | IOException exception) {
            registerFailure();
            throw exception;
        } finally {
            Crypto.wipe(dek);
        }
    }

    public RecoveryQrPayload createRecoveryPayload()
            throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        if (lastQrSecret == null) {
            throw new GeneralSecurityException("Recovery QR secret is unavailable");
        }
        return new RecoveryQrPayload(header.vaultId, lastQrSecret,
                header.kdfParameters, header.wrapSalt);
    }

    public void rememberRecoverySecret(byte[] secret) throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        if (secret == null || secret.length != Crypto.KEY_SIZE
                || !Crypto.constantTimeEquals(Crypto.sha256(secret), header.qrId)) {
            throw new GeneralSecurityException("Recovery secret does not match VLT3 header");
        }
        replaceLastQrSecret(secret);
    }

    public synchronized byte[] copyRecoverySecret() {
        return lastQrSecret == null ? null : lastQrSecret.clone();
    }

    public synchronized void clearInMemorySecrets() {
        Crypto.wipe(lastQrSecret);
        lastQrSecret = null;
    }

    public Cipher prepareRecoveryQrCipher() throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        return keyManager.prepareDecrypt(deviceAlias(header.vaultId, header.hardwareKeyId));
    }

    public RecoveryQrPayload openRecoveryQr(Cipher authenticatedCipher)
            throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        String encoded = state.getString(qrPreferenceKey(header), null);
        if (encoded == null) {
            throw new GeneralSecurityException("Local recovery QR is unavailable");
        }
        final byte[] wrapped;
        try {
            wrapped = java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Corrupt local recovery QR", exception);
        }
        byte[] qrSecret = keyManager.decrypt(authenticatedCipher, wrapped);
        try {
            if (!Crypto.constantTimeEquals(Crypto.sha256(qrSecret), header.qrId)) {
                throw new GeneralSecurityException("Recovery QR does not match VLT3 header");
            }
            replaceLastQrSecret(qrSecret);
            return new RecoveryQrPayload(header.vaultId, qrSecret,
                    header.kdfParameters, header.wrapSalt);
        } finally {
            Crypto.wipe(qrSecret);
            Crypto.wipe(wrapped);
        }
    }

    public synchronized PassphraseQrPayload createPassphraseQrPayload(char[] passphrase)
            throws IOException, GeneralSecurityException {
        enforceRateLimit();
        requirePassphrase(passphrase);
        VaultHeaderV3 header = headerStore.read();
        byte[] qrSecret = copyRecoverySecret();
        if (qrSecret == null) {
            throw new GeneralSecurityException("Recovery QR secret is unavailable");
        }
        byte[] dek = null;
        try {
            try {
                dek = VaultV3KeyDerivation.unwrapDek(header, passphrase, qrSecret);
            } catch (javax.crypto.BadPaddingException exception) {
                registerFailure();
                throw new PassphraseQrMismatchException(exception);
            }
            state.edit().putInt(PREF_FAILED, 0).putLong(PREF_LOCKED_UNTIL, 0).apply();
            return new PassphraseQrPayload(header.vaultId, passphrase);
        } finally {
            Crypto.wipe(dek);
            Crypto.wipe(qrSecret);
        }
    }

    public void updateWithTruePin(char[] passphrase, VaultData updated,
                                  List<PendingVaultAsset> pending)
            throws IOException, GeneralSecurityException {
        requirePassphrase(passphrase);
        if (lastQrSecret == null) {
            throw new GeneralSecurityException("Passphrase update requires the recovery QR");
        }
        VaultHeaderV3 header = headerStore.read();
        byte[] dek = VaultV3KeyDerivation.unwrapDek(header, passphrase, lastQrSecret);
        try {
            commitUpdated(header, dek, updated, pending);
        } finally {
            Crypto.wipe(dek);
        }
    }

    public void updateWithDeviceAuth(Cipher authenticatedCipher, VaultData updated,
                                    List<PendingVaultAsset> pending)
            throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        byte[] dek = keyManager.decrypt(authenticatedCipher, header.hardwareWrappedDek);
        try {
            commitUpdated(header, dek, updated, pending);
        } finally {
            Crypto.wipe(dek);
        }
    }

    public synchronized void changeTruePin(char[] oldPassphrase, char[] newPassphrase)
            throws IOException, GeneralSecurityException {
        requirePassphrase(oldPassphrase);
        requirePassphrase(newPassphrase);
        if (lastQrSecret == null) {
            throw new GeneralSecurityException("Passphrase change requires the recovery QR");
        }
        VaultHeaderV3 old = headerStore.read();
        byte[] dek = VaultV3KeyDerivation.unwrapDek(old, oldPassphrase, lastQrSecret);
        byte[] nextGeneration = VaultObjectIds.randomId();
        try {
            Crypto.SealedBox wrapper = VaultV3KeyDerivation.wrapDek(dek, newPassphrase,
                    lastQrSecret, old.vaultId, old.kdfParameters, old.wrapSalt, old.qrId,
                    old.hardwareKeyId, nextGeneration);
            headerStore.write(old.withPinWrapper(nextGeneration, wrapper));
        } finally {
            Crypto.wipe(dek);
            Crypto.wipe(nextGeneration);
        }
    }

    public synchronized byte[] readResource(byte[] dek, String objectId, VaultObjectRef.Type type)
            throws IOException, GeneralSecurityException {
        return readResource(dek, objectId, null, type).plaintext;
    }

    public synchronized ResourceResult readResource(byte[] dek, String objectId,
                                                    String entryId,
                                                    VaultObjectRef.Type type)
            throws IOException, GeneralSecurityException {
        if (!VaultObjectIds.isValidHex(objectId, 32)
                || (entryId != null && !VaultObjectIds.isValidHex(entryId, 16))) {
            throw new IOException("Invalid VLT3 resource lookup");
        }
        VaultHeaderV3 header = headerStore.read();
        byte[] id = VaultObjectIds.fromHex(objectId);
        byte[] owner = entryId == null ? null : parseEntryId(entryId);
        IOException ioFailure = null;
        GeneralSecurityException securityFailure = null;
        try {
            VaultManifest current = readManifest(header, dek, false);
            VaultObjectRef currentRef = findResource(current, id, owner, type, true);
            return new ResourceResult(objectStore(header).read(currentRef, dek), false);
        } catch (IOException currentFailure) {
            ioFailure = currentFailure;
        } catch (GeneralSecurityException currentFailure) {
            securityFailure = currentFailure;
        }
        try {
            VaultManifest previous = readManifest(header, dek, true);
            VaultObjectRef previousRef = findResource(previous, id, owner, type,
                    type == VaultObjectRef.Type.DOCUMENT_PAGE);
            return new ResourceResult(objectStore(header).read(previousRef, dek), true);
        } catch (IOException | GeneralSecurityException previousFailure) {
            if (ioFailure != null) {
                ioFailure.addSuppressed(previousFailure);
                throw ioFailure;
            }
            if (securityFailure != null) {
                securityFailure.addSuppressed(previousFailure);
                throw securityFailure;
            }
            throw previousFailure;
        } finally {
            Crypto.wipe(id);
            Crypto.wipe(owner);
        }
    }

    private static VaultObjectRef findResource(VaultManifest manifest, byte[] id, byte[] owner,
                                               VaultObjectRef.Type type, boolean exactId)
            throws IOException {
        for (VaultObjectRef ref : manifest.resources) {
            if (ref.type != type) {
                continue;
            }
            boolean matchesId = Crypto.constantTimeEquals(ref.objectId, id);
            boolean matchesOwner = owner != null && ref.ownerEntryId != null
                    && Crypto.constantTimeEquals(ref.ownerEntryId, owner);
            if ((exactId && matchesId) || (!exactId && (matchesOwner || matchesId))) {
                if (owner != null && !Crypto.constantTimeEquals(ref.ownerEntryId, owner)) {
                    continue;
                }
                return ref;
            }
        }
        throw new IOException("VLT3 resource is not referenced");
    }

    public synchronized int collectOrphans(byte[] dek)
            throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        Set<String> reachable = new HashSet<>();
        OpenResult current = openGeneration(header, dek, false);
        try {
            addManifestObjects(reachable, readManifest(header, dek, false));
        } finally {
            current.clear();
        }
        if (manifestStore.hasPrevious()) {
            OpenResult previous = openGeneration(header, dek, true);
            try {
                addManifestObjects(reachable, readManifest(header, dek, true));
            } finally {
                previous.clear();
            }
        }
        reachable.addAll(backupPins.keySet());
        return objectStore(header).collectUnreferenced(reachable);
    }

    public void exportBackup(OutputStream destination, byte[] dek)
            throws IOException, GeneralSecurityException {
        BackupSnapshot snapshot = captureBackupSnapshot(dek);
        try {
            VaultBackupV3.write(destination, snapshot.headerBytes, snapshot.manifestBytes,
                    snapshot.manifest, snapshot.objectStore);
        } finally {
            releaseBackupSnapshot(snapshot);
            Crypto.wipe(snapshot.headerBytes);
            Crypto.wipe(snapshot.manifestBytes);
        }
    }

    public void exportCopy(Uri uri, byte[] dek)
            throws IOException, GeneralSecurityException {
        File staged = File.createTempFile("vlt3-backup-", ".tmp", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(staged, false)) {
                exportBackup(output, dek);
                output.getFD().sync();
            }
            try (InputStream input = new FileInputStream(staged);
                 OutputStream output = openOutput(uri)) {
                copyStream(input, output);
                output.flush();
            }
        } finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    public void linkRemote(Uri uri, byte[] dek)
            throws IOException, GeneralSecurityException {
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException exception) {
            throw new IOException("Cannot persist access to the selected location", exception);
        }
        exportCopy(uri, dek);
        if (!state.edit().putString(PREF_REMOTE_URI, uri.toString()).commit()) {
            throw new IOException("Could not save the backup location");
        }
    }

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
        return slash >= 0 && slash < decoded.length() - 1
                ? decoded.substring(slash + 1) : decoded;
    }

    public void unlinkRemote() {
        state.edit().remove(PREF_REMOTE_URI).apply();
    }

    public void pushRemote(byte[] dek) throws IOException, GeneralSecurityException {
        String value = state.getString(PREF_REMOTE_URI, null);
        if (value != null) {
            exportCopy(Uri.parse(value), dek);
        }
    }

    public synchronized void importCopy(Uri uri, RecoveryQrPayload recovery, char[] passphrase)
            throws IOException, GeneralSecurityException {
        android.util.Log.i("SPM_RECOVERY", "Opening selected VLT3 backup");
        final InputStream input;
        try {
            input = context.getContentResolver().openInputStream(uri);
        } catch (IOException | SecurityException | IllegalArgumentException exception) {
            android.util.Log.e("SPM_RECOVERY", "Could not open selected backup ("
                    + exception.getClass().getSimpleName() + ")");
            throw exception;
        }
        if (input == null) {
            android.util.Log.e("SPM_RECOVERY", "Selected backup stream is unavailable");
            throw new IOException("Backup is unavailable");
        }
        try (InputStream source = input) {
            restoreBackup(source, recovery, passphrase);
        }
    }

    public synchronized void restoreBackup(InputStream input, RecoveryQrPayload recovery,
                                           char[] passphrase)
            throws IOException, GeneralSecurityException {
        boolean installed = false;
        String newAlias = null;
        byte[] encodedHeader = null;
        byte[] encodedManifest = null;
        byte[] dek = null;
        byte[] textData = null;
        byte[] newHardwareKeyId = null;
        byte[] newWrapperGeneration = null;
        String restoreStage = "validating recovery passphrase";
        try {
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            requirePassphrase(passphrase);
            restoreStage = "preparing staging directory";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            if (importRoot.exists()) {
                deleteManagedDirectory(importRoot);
            }
            if (!importRoot.mkdirs()) {
                throw new IOException("Could not create VLT3 import staging directory");
            }

            restoreStage = "opening backup container";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            VaultBackupV3.BackupReader reader = VaultBackupV3.open(input);
            encodedHeader = reader.encodedHeader();
            encodedManifest = reader.encodedManifest();
            restoreStage = "validating recovery QR metadata";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            VaultHeaderV3 importedHeader = VaultHeaderV3.decode(encodedHeader);
            verifyRecoveryMetadata(importedHeader, recovery);
            restoreStage = "decrypting vault key with passphrase and recovery QR";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            dek = VaultV3KeyDerivation.unwrapDek(importedHeader, passphrase,
                    recovery.qrSecret);
            restoreStage = "validating encrypted manifest";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            VaultManifest manifest = VaultManifestCodec.open(encodedManifest, dek,
                    importedHeader.vaultId);
            VaultObjectStore stagingObjects = new VaultObjectStore(
                    new File(importRoot, "objects"), importedHeader.vaultId);
            restoreStage = "copying and validating vault objects";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            reader.importObjects(manifest, stagingObjects);
            restoreStage = "decrypting vault text block";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            textData = stagingObjects.read(manifest.textBlock, dek);
            restoreStage = "decoding vault entries";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            VaultData decoded = VaultTextDataCodec.decode(textData);
            restoreStage = "validating resource references";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            validateResourceLinks(decoded, manifest.resources);

            restoreStage = "generating device-bound key identifiers";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            newHardwareKeyId = Crypto.randomBytes(VaultHeaderV3.HARDWARE_KEY_ID_SIZE);
            newWrapperGeneration = VaultObjectIds.randomId();
            newAlias = deviceAlias(importedHeader.vaultId, newHardwareKeyId);
            restoreStage = "creating device-bound vault key";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            keyManager.ensureKey(newAlias, true);
            if (!keyManager.isHardwareBacked(newAlias)) {
                throw new GeneralSecurityException("Hardware-backed key unavailable");
            }
            restoreStage = "wrapping vault keys for this device";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            Crypto.SealedBox newPinWrapper = VaultV3KeyDerivation.wrapDek(dek, passphrase,
                    recovery.qrSecret, importedHeader.vaultId,
                    importedHeader.kdfParameters, importedHeader.wrapSalt,
                    importedHeader.qrId, newHardwareKeyId, newWrapperGeneration);
            VaultHeaderV3 localHeader = new VaultHeaderV3(importedHeader.vaultId,
                    importedHeader.kdfParameters, importedHeader.wrapSalt, importedHeader.qrId,
                    newHardwareKeyId, newWrapperGeneration, newPinWrapper,
                    keyManager.wrap(newAlias, dek));
            String wrappedQr = java.util.Base64.getEncoder().encodeToString(
                    keyManager.wrap(newAlias, recovery.qrSecret));

            restoreStage = "installing restored vault";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            VaultManifestStore stagingManifests = new VaultManifestStore(importRoot);
            stagingManifests.writeInitial(encodedManifest);
            stagingManifests.writePrevious(encodedManifest);
            new VaultHeaderStore(new File(importRoot, "header.bin")).write(localHeader);
            VaultDirectorySync.sync(importRoot);
            restoreStage = "persisting local recovery key wrapper";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            if (!state.edit().putString(qrPreferenceKey(localHeader), wrappedQr).commit()) {
                throw new IOException("Could not persist local recovery QR wrapper");
            }
            restoreStage = "committing staged vault";
            android.util.Log.i("SPM_RECOVERY", "VLT3 restore stage: " + restoreStage);
            installStagedVault();
            installed = true;

            android.util.Log.i("SPM_RECOVERY", "VLT3 restore completed");
            state.edit().putInt(PREF_FAILED, 0).putLong(PREF_LOCKED_UNTIL, 0).apply();
            replaceLastQrSecret(recovery.qrSecret);
        } catch (IOException | GeneralSecurityException | RuntimeException exception) {
            Throwable cause = exception.getCause();
            String failure = exception.getClass().getSimpleName();
            if (cause != null) {
                failure += " caused by " + cause.getClass().getSimpleName();
            }
            android.util.Log.e("SPM_RECOVERY", "VLT3 restore failed during " + restoreStage
                    + " (" + failure + ")");
            if (!installed && !importRoot.exists() && new File(root, "header.bin").isFile()) {
                installed = true;
            }
            if (!installed && newAlias != null) {
                keyManager.deleteKey(newAlias);
            }
            throw exception;
        } finally {
            Crypto.wipe(encodedHeader);
            Crypto.wipe(encodedManifest);
            Crypto.wipe(dek);
            Crypto.wipe(textData);
            Crypto.wipe(newHardwareKeyId);
            Crypto.wipe(newWrapperGeneration);
            if (!installed && importRoot.exists()) {
                deleteManagedDirectory(importRoot);
            }
        }
    }

    private OpenResult openWithDek(VaultHeaderV3 header, byte[] dek)
            throws IOException, GeneralSecurityException {
        try {
            OpenResult result = openGeneration(header, dek, false);
            cleanupPreviousRoot();
            return result;
        } catch (IOException | GeneralSecurityException currentFailure) {
            try {
                OpenResult recovered = openGeneration(header, dek, true);
                try {
                    return new OpenResult(recovered.data, dek, true);
                } finally {
                    recovered.clear();
                }
            } catch (IOException | GeneralSecurityException previousFailure) {
                currentFailure.addSuppressed(previousFailure);
                throw currentFailure;
            }
        }
    }

    private OpenResult openGeneration(VaultHeaderV3 header, byte[] dek, boolean previous)
            throws IOException, GeneralSecurityException {
        VaultManifest manifest = readManifest(header, dek, previous);
        VaultObjectStore store = objectStore(header);
        for (VaultObjectRef ref : manifest.resources) {
            store.checkPresent(ref);
        }
        byte[] encodedText = store.read(manifest.textBlock, dek);
        try {
            VaultData data = VaultTextDataCodec.decode(encodedText);
            validateResourceLinks(data, manifest.resources);
            return new OpenResult(data, dek, previous);
        } finally {
            Crypto.wipe(encodedText);
        }
    }

    public VaultData repairCurrentFromPrevious(byte[] dek)
            throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        OpenResult recovered = openGeneration(header, dek, true);
        try {
            manifestStore.recoverCurrentFromPrevious();
            return recovered.data;
        } finally {
            recovered.clear();
        }
    }

    private synchronized void commitUpdated(VaultHeaderV3 header, byte[] dek, VaultData updated,
                                            List<PendingVaultAsset> pending)
            throws IOException, GeneralSecurityException {
        if (updated == null || pending == null) {
            throw new IOException("Invalid VLT3 update");
        }
        byte[] encodedText = VaultTextDataCodec.encode(updated);
        try {
            VaultManifest current = readManifest(header, dek, false);
            VaultObjectStore objectStore = objectStore(header);
            Map<String, VaultObjectRef> available = new HashMap<>();
            for (VaultObjectRef ref : current.resources) {
                available.put(VaultObjectIds.toHex(ref.objectId), ref);
            }
            Set<String> pendingIds = new HashSet<>();
            try {
                for (PendingVaultAsset asset : pending) {
                    String id = VaultObjectIds.toHex(asset.objectId);
                    if (!pendingIds.add(id) || available.containsKey(id)) {
                        throw new IOException("Duplicate VLT3 resource ID");
                    }
                    VaultObjectRef ref = objectStore.writeWithId(dek, asset.objectId,
                            asset.type, asset.ownerEntryId, asset.contentType, asset.plaintext);
                    available.put(id, ref);
                }

                List<VaultObjectRef> resources = referencesFor(updated, available, pendingIds);
                long activeBytes = encodedText.length;
                for (VaultObjectRef ref : resources) {
                    activeBytes = Math.addExact(activeBytes, ref.plaintextLength);
                    if (activeBytes > VaultManifest.MAX_ACTIVE_BYTES) {
                        throw new VaultSizeLimitException("VLT3 active data exceeds 100 MiB");
                    }
                }
                VaultObjectRef textRef = objectStore.write(dek,
                        VaultObjectRef.Type.TEXT_BLOCK, null, "", encodedText);
                VaultManifest next = new VaultManifest(VaultObjectIds.randomId(), textRef,
                        resources);
                byte[] encodedManifest = VaultManifestCodec.seal(next, dek, header.vaultId);
                try {
                    manifestStore.commit(encodedManifest);
                } finally {
                    Crypto.wipe(encodedManifest);
                }
            } finally {
                for (PendingVaultAsset asset : pending) {
                    asset.clear();
                    Crypto.wipe(asset.objectId);
                    Crypto.wipe(asset.ownerEntryId);
                }
            }
        } finally {
            Crypto.wipe(encodedText);
        }
    }

    private List<VaultObjectRef> referencesFor(VaultData data,
                                                Map<String, VaultObjectRef> available,
                                                Set<String> pendingIds) throws IOException {
        List<VaultObjectRef> result = new ArrayList<>();
        Set<String> referenced = new HashSet<>();
        for (VaultItem item : data.items) {
            if (item.isImage()) {
                addExpectedResource(result, referenced, available, pendingIds,
                        item.attachmentId, VaultObjectRef.Type.IMAGE, item.entryId,
                        item.attachmentSize, item.contentType);
                if (item.thumbnailId != null) {
                    addExpectedResource(result, referenced, available, pendingIds,
                            item.thumbnailId, VaultObjectRef.Type.THUMBNAIL, item.entryId,
                            -1, null);
                }
            } else if (item.isFile()) {
                addExpectedResource(result, referenced, available, pendingIds,
                        item.attachmentId, VaultObjectRef.Type.FILE, item.entryId,
                        item.attachmentSize, item.contentType);
            } else if (item.isDocument()) {
                for (String pageId : item.documentPageIds) {
                    addExpectedResource(result, referenced, available, pendingIds,
                            pageId, VaultObjectRef.Type.DOCUMENT_PAGE, item.entryId,
                            -1, "image/jpeg");
                }
            }
        }
        if (!referenced.containsAll(pendingIds)) {
            throw new IOException("VLT3 update contains an unreferenced new object");
        }
        validateResourceLinks(data, result);
        return result;
    }

    private static void addExpectedResource(List<VaultObjectRef> result, Set<String> referenced,
                                            Map<String, VaultObjectRef> available,
                                            Set<String> pendingIds, String id,
                                            VaultObjectRef.Type type, String owner,
                                            long expectedSize, String contentType)
            throws IOException {
        if (!VaultObjectIds.isValidHex(id, 32)) {
            throw new IOException("Invalid VLT3 resource ID");
        }
        String key = id.toLowerCase(java.util.Locale.ROOT);
        VaultObjectRef ref = available.get(key);
        byte[] expectedOwner = parseEntryId(owner);
        try {
            if (ref == null || ref.type != type || ref.ownerEntryId == null
                    || !Crypto.constantTimeEquals(ref.ownerEntryId, expectedOwner)
                    || (expectedSize >= 0 && ref.plaintextLength != expectedSize)
                    || (contentType != null && !contentType.equals(ref.contentType))
                    || !referenced.add(key)) {
                throw new IOException("Missing or mismatched VLT3 resource reference");
            }
        } finally {
            Crypto.wipe(expectedOwner);
        }
        result.add(ref);
    }

    private static void validateResourceLinks(VaultData data, List<VaultObjectRef> resources)
            throws IOException {
        Map<String, VaultObjectRef> indexed = new HashMap<>();
        for (VaultObjectRef ref : resources) {
            indexed.put(VaultObjectIds.toHex(ref.objectId), ref);
        }
        Set<String> referenced = new HashSet<>();
        for (VaultItem item : data.items) {
            if (item.isImage()) {
                validateOneLink(indexed, referenced, item, item.attachmentId,
                        VaultObjectRef.Type.IMAGE, item.attachmentSize, item.contentType);
                if (item.thumbnailId != null) {
                    validateOneLink(indexed, referenced, item, item.thumbnailId,
                            VaultObjectRef.Type.THUMBNAIL, -1, null);
                }
            } else if (item.isFile()) {
                validateOneLink(indexed, referenced, item, item.attachmentId,
                        VaultObjectRef.Type.FILE, item.attachmentSize, item.contentType);
            } else if (item.isDocument()) {
                for (String pageId : item.documentPageIds) {
                    validateOneLink(indexed, referenced, item, pageId,
                            VaultObjectRef.Type.DOCUMENT_PAGE, -1, "image/jpeg");
                }
            }
        }
        if (referenced.size() != resources.size()) {
            throw new IOException("VLT3 manifest contains unreferenced resources");
        }
    }

    private static void validateOneLink(Map<String, VaultObjectRef> indexed,
                                        Set<String> referenced, VaultItem item,
                                        String resourceId, VaultObjectRef.Type type,
                                        long size, String contentType) throws IOException {
        if (!VaultObjectIds.isValidHex(resourceId, 32)) {
            throw new IOException("Invalid VLT3 resource link");
        }
        String id = resourceId.toLowerCase(java.util.Locale.ROOT);
        VaultObjectRef ref = indexed.get(id);
        byte[] owner = parseEntryId(item.entryId);
        try {
            if (ref == null || ref.type != type || ref.plaintextLength != size
                    && size >= 0
                    || (type == VaultObjectRef.Type.DOCUMENT_PAGE
                    && ref.plaintextLength < 1)
                    || !Crypto.constantTimeEquals(ref.ownerEntryId, owner)
                    || (contentType != null && !contentType.equals(ref.contentType))
                    || !referenced.add(id)) {
                throw new IOException("VLT3 manifest/resource mismatch");
            }
        } finally {
            Crypto.wipe(owner);
        }
    }

    private VaultManifest readManifest(VaultHeaderV3 header, byte[] dek, boolean previous)
            throws IOException, GeneralSecurityException {
        byte[] encoded = previous ? manifestStore.readPrevious() : manifestStore.readCurrent();
        try {
            return VaultManifestCodec.open(encoded, dek, header.vaultId);
        } finally {
            Crypto.wipe(encoded);
        }
    }

    private static void addManifestObjects(Set<String> reachable, VaultManifest manifest) {
        reachable.add(VaultObjectIds.toHex(manifest.textBlock.objectId));
        for (VaultObjectRef resource : manifest.resources) {
            reachable.add(VaultObjectIds.toHex(resource.objectId));
        }
    }

    private static void verifyRecoveryMetadata(VaultHeaderV3 header,
                                               RecoveryQrPayload recovery)
            throws GeneralSecurityException {
        if (recovery == null
                || !Crypto.constantTimeEquals(header.vaultId, recovery.vaultId)
                || !Crypto.constantTimeEquals(header.qrId, recovery.qrId)
                || !Crypto.constantTimeEquals(header.wrapSalt, recovery.wrapSalt)
                || !Crypto.constantTimeEquals(header.kdfParameters.salt,
                recovery.kdfParameters.salt)
                || header.kdfParameters.memoryKiB != recovery.kdfParameters.memoryKiB
                || header.kdfParameters.timeCost != recovery.kdfParameters.timeCost
                || header.kdfParameters.parallelism != recovery.kdfParameters.parallelism) {
            throw new GeneralSecurityException("Recovery data does not match VLT3");
        }
    }

    private void replaceLastQrSecret(byte[] value) {
        Crypto.wipe(lastQrSecret);
        lastQrSecret = value.clone();
    }

    private VaultObjectStore objectStore(VaultHeaderV3 header) throws IOException {
        return new VaultObjectStore(new File(root, "objects"), header.vaultId);
    }

    private synchronized BackupSnapshot captureBackupSnapshot(byte[] dek)
            throws IOException, GeneralSecurityException {
        VaultHeaderV3 header = headerStore.read();
        byte[] headerBytes = header.encode();
        byte[] manifestBytes = manifestStore.readCurrent();
        VaultManifest manifest;
        try {
            manifest = VaultManifestCodec.open(manifestBytes, dek, header.vaultId);
        } catch (GeneralSecurityException | IOException exception) {
            Crypto.wipe(headerBytes);
            Crypto.wipe(manifestBytes);
            throw exception;
        }
        VaultObjectStore store = objectStore(header);
        List<VaultObjectRef> refs = new ArrayList<>(manifest.resources.size() + 1);
        refs.add(manifest.textBlock);
        refs.addAll(manifest.resources);
        for (VaultObjectRef ref : refs) {
            String id = VaultObjectIds.toHex(ref.objectId);
            backupPins.put(id, backupPins.getOrDefault(id, 0) + 1);
        }
        return new BackupSnapshot(headerBytes, manifestBytes, manifest, store, refs);
    }

    private synchronized void releaseBackupSnapshot(BackupSnapshot snapshot) {
        for (VaultObjectRef ref : snapshot.refs) {
            String id = VaultObjectIds.toHex(ref.objectId);
            Integer count = backupPins.get(id);
            if (count == null || count <= 1) {
                backupPins.remove(id);
            } else {
                backupPins.put(id, count - 1);
            }
        }
    }

    private OutputStream openOutput(Uri uri) throws IOException {
        IOException last = null;
        for (String mode : new String[]{"wt", "rwt", "w"}) {
            try {
                OutputStream output = context.getContentResolver().openOutputStream(uri, mode);
                if (output != null) {
                    return output;
                }
                last = new IOException("External backup stream unavailable");
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException exception) {
                last = new IOException(exception);
            }
        }
        throw last == null ? new IOException("External backup is not writable") : last;
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private void installStagedVault() throws IOException {
        if (!importRoot.isDirectory()) {
            throw new IOException("VLT3 import staging directory is unavailable");
        }
        if (previousRoot.exists()) {
            deleteManagedDirectory(previousRoot);
        }
        VaultRootTransaction.install(root, previousRoot, importRoot,
                VaultDirectorySync::sync);
    }

    private static void deleteManagedDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }
        if (!directory.isDirectory()) {
            throw new IOException("VLT3 managed path is not a directory");
        }
        new VaultHeaderStore(new File(directory, "header.bin")).delete();
        new VaultManifestStore(directory).deleteAll();
        File objects = new File(directory, "objects");
        VaultObjectStore.deleteAllInDirectory(objects);
        if (objects.exists() && !objects.delete()) {
            throw new IOException("Could not remove VLT3 object directory");
        }
        File[] leftovers = directory.listFiles();
        if (leftovers == null) {
            throw new IOException("Could not list VLT3 directory");
        }
        for (File leftover : leftovers) {
            if (leftover.isDirectory() || !leftover.delete()) {
                throw new IOException("Unexpected content in VLT3 directory");
            }
        }
        if (!directory.delete()) {
            throw new IOException("Could not remove VLT3 directory");
        }
        File parent = directory.getParentFile();
        if (parent != null) {
            VaultDirectorySync.sync(parent);
        }
    }

    private void cleanupPreviousRoot() {
        if (!previousRoot.exists()) {
            return;
        }
        VaultHeaderV3 previousHeaderForQr = null;
        String oldAlias = null;
        try {
            previousHeaderForQr =
                    new VaultHeaderStore(new File(previousRoot, "header.bin")).read();
            oldAlias = deviceAlias(previousHeaderForQr.vaultId,
                    previousHeaderForQr.hardwareKeyId);
        } catch (IOException exception) {
            android.util.Log.w("SPM_VLT3", "Could not read previous root header", exception);
        }
        try {
            deleteManagedDirectory(previousRoot);
        } catch (IOException exception) {
            android.util.Log.w("SPM_VLT3", "Could not remove previous vault root", exception);
            return;
        }
        if (oldAlias != null) {
            try {
                keyManager.deleteKey(oldAlias);
            } catch (GeneralSecurityException exception) {
                android.util.Log.w("SPM_VLT3", "Could not remove previous device key", exception);
            }
        }
        if (oldAlias != null && previousHeaderForQr != null) {
            state.edit().remove(qrPreferenceKey(previousHeaderForQr)).apply();
        }
    }

    public synchronized void deleteLocalVault() throws IOException, GeneralSecurityException {
        String alias = null;
        if (headerStore.exists()) {
            try {
                VaultHeaderV3 header = headerStore.read();
                alias = deviceAlias(header.vaultId, header.hardwareKeyId);
            } catch (IOException exception) {
                android.util.Log.w("SPM_VLT3", "Could not read header while deleting vault",
                        exception);
            }
        }
        deleteManagedDirectory(root);
        deleteManagedDirectory(previousRoot);
        deleteManagedDirectory(importRoot);
        if (alias != null) {
            keyManager.deleteKey(alias);
        }
        if (!state.edit().clear().commit()) {
            throw new IOException("Could not clear local VLT3 preferences");
        }
        Crypto.wipe(lastQrSecret);
        lastQrSecret = null;
    }

    private static final class BackupSnapshot {
        final byte[] headerBytes;
        final byte[] manifestBytes;
        final VaultManifest manifest;
        final VaultObjectStore objectStore;
        final List<VaultObjectRef> refs;

        BackupSnapshot(byte[] headerBytes, byte[] manifestBytes, VaultManifest manifest,
                       VaultObjectStore objectStore, List<VaultObjectRef> refs) {
            this.headerBytes = headerBytes;
            this.manifestBytes = manifestBytes;
            this.manifest = manifest;
            this.objectStore = objectStore;
            this.refs = refs;
        }
    }

    private static String deviceAlias(byte[] vaultId, byte[] hardwareKeyId) {
        StringBuilder suffix = new StringBuilder(hardwareKeyId.length * 2);
        for (byte value : hardwareKeyId) {
            suffix.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            suffix.append(Character.forDigit(value & 0x0f, 16));
        }
        return DeviceKeyManager.alias(vaultId, "bio_v3_" + suffix);
    }

    private static String qrPreferenceKey(VaultHeaderV3 header) {
        return PREF_QR_WRAPPED_PREFIX + hexBytes(header.vaultId) + "_"
                + hexBytes(header.hardwareKeyId);
    }

    private static String hexBytes(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(item & 0x0f, 16));
        }
        return output.toString();
    }

    private void cleanupIncompleteCreation() throws IOException {
        headerStore.delete();
        manifestStore.deleteAll();
        VaultObjectStore.deleteAllInDirectory(new File(root, "objects"));
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

    private static void requirePassphrase(char[] value) {
        if (!PinPolicy.isValid(value)) {
            throw new IllegalArgumentException(
                    "Passphrase must be 12-24 printable non-numeric characters");
        }
    }

    private static byte[] parseEntryId(String value) {
        byte[] result = new byte[16];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(value.charAt(2 * i), 16) << 4)
                    | Character.digit(value.charAt(2 * i + 1), 16));
        }
        return result;
    }

    public static final class OpenResult {
        public final VaultData data;
        public final boolean recoveredPrevious;
        private final byte[] dek;

        private OpenResult(VaultData data, byte[] dek) {
            this(data, dek, false);
        }

        private OpenResult(VaultData data, byte[] dek, boolean recoveredPrevious) {
            this.data = data;
            this.dek = dek.clone();
            this.recoveredPrevious = recoveredPrevious;
        }

        public byte[] copyDek() {
            return dek.clone();
        }

        public void clear() {
            Crypto.wipe(dek);
        }
    }

    public static final class ResourceResult {
        public final byte[] plaintext;
        public final boolean recoveredPrevious;

        private ResourceResult(byte[] plaintext, boolean recoveredPrevious) {
            this.plaintext = plaintext;
            this.recoveredPrevious = recoveredPrevious;
        }
    }
}
