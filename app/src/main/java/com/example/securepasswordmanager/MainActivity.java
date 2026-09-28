package com.example.securepasswordmanager;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.securepasswordmanager.crypto.RecoveryQrPayload;
import com.example.securepasswordmanager.crypto.PassphraseQrPayload;
import com.example.securepasswordmanager.crypto.Crypto;
import com.example.securepasswordmanager.crypto.PendingVaultAsset;
import com.example.securepasswordmanager.crypto.PasswordHealth;
import com.example.securepasswordmanager.crypto.Totp;
import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultImageProcessor;
import com.example.securepasswordmanager.crypto.VaultItem;
import com.example.securepasswordmanager.crypto.VaultObjectIds;
import com.example.securepasswordmanager.crypto.VaultObjectRef;
import com.example.securepasswordmanager.crypto.VaultSizeLimitException;
import com.example.securepasswordmanager.repository.VaultV3Repository;
import com.example.securepasswordmanager.security.PinPolicy;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

public final class MainActivity extends AppCompatActivity {
    private static final String IMAGE_LOG = "SPM_IMAGE_FLOW";
    private static final int REQUEST_VAULT_FILE = 4101;
    private static final int MAX_FILE_ATTACHMENT_BYTES = 10 * 1024 * 1024;
    private static final long CLIPBOARD_TIMEOUT_MS = 30_000L;
    private static final String KEY_PENDING_PHOTO = "pending_photo";
    private static final String KEY_AWAITING = "awaiting_result";
    private static final String APP_PREFS = "app_settings";
    private static final String PREF_AUTOLOCK_MIN = "autolock_min";
    private static final String PREF_AUTOLOCK_ENABLED = "autolock_enabled";
    private static final String PREF_LOCK_BACKGROUND = "lock_background";
    private static final int DEFAULT_AUTOLOCK_MIN = 5;
    private static boolean sImageFlowActive;
    // Survives Activity recreation so background time is measured across the OCR/camera flow.
    private static long sBackgroundedElapsed;

    private VaultV3Repository repository;
    private PremiumManager premiumManager;
    private char[] sessionTruePin;
    private byte[] sessionDek;
    private boolean biometricSession;
    private boolean sessionRecoveredPrevious;
    private boolean saveInProgress;
    private boolean unlocked;
    private VaultData currentData = VaultData.empty();

    private PasswordAdapter adapter;
    private String searchQuery = "";
    private boolean favoritesOnly;

    private TextInputEditText recoveryPayloadField;
    private TextInputEditText recoveryPinField;
    private TextView recoveryFileStatus;
    private Uri selectedRecoveryVaultUri;
    private VaultItem pendingFileExport;
    private File pendingPreviewFile;
    private boolean pendingPreviewRecoveredPrevious;
    private boolean scanningPassphraseQr;

    private final Handler clipboardHandler = new Handler(Looper.getMainLooper());
    private final Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockRunnable = () -> {
        if (unlocked) {
            lock();
        }
    };
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private Uri pendingPhotoUri;
    private Uri pendingContainerPhotoUri;
    // True while an intentional in-app flow (camera, gallery, SAF picker, OCR review,
    // biometric prompt) is foreground, so onStop does not auto-lock during those flows.
    private boolean awaitingResult;

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                awaitingResult = false;
                if (uri != null) {
                    launchOcrReview(uri);
                }
            });

    private final ActivityResultLauncher<Uri> takePhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                awaitingResult = false;
                if (Boolean.TRUE.equals(success) && pendingPhotoUri != null) {
                    launchOcrReview(pendingPhotoUri);
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchContainerCamera();
                } else {
                    awaitingResult = false;
                    sImageFlowActive = false;
                    toast(R.string.err_camera_permission);
                }
            });

    private final ActivityResultLauncher<String> containerCameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchContainerCamera();
                } else {
                    awaitingResult = false;
                    sImageFlowActive = false;
                    toast(R.string.err_camera_permission);
                }
            });

    private final ActivityResultLauncher<String> containerPickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    launchImageCrop(uri);
                } else {
                    awaitingResult = false;
                    sImageFlowActive = false;
                }
            });

    private final ActivityResultLauncher<String[]> pickFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                awaitingResult = false;
                if (uri != null) {
                    addSelectedFile(uri);
                }
            });

    private final ActivityResultLauncher<String> exportFileLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(
                    "application/octet-stream"), uri -> {
                awaitingResult = false;
                if (uri != null && pendingFileExport != null) {
                    exportAttachment(uri, pendingFileExport);
                }
                pendingFileExport = null;
            });

    private final ActivityResultLauncher<Uri> containerTakePhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && pendingContainerPhotoUri != null) {
                    launchImageCrop(pendingContainerPhotoUri);
                } else {
                    awaitingResult = false;
                    sImageFlowActive = false;
                }
            });

    private final ActivityResultLauncher<Intent> imageCropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Log.d(IMAGE_LOG, "crop result received: code=" + result.getResultCode()
                        + ", data=" + (result.getData() != null));
                awaitingResult = false;
                sImageFlowActive = false;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getParcelableExtra(
                            ImageCropActivity.EXTRA_CROPPED_URI);
                    Log.d(IMAGE_LOG, "crop URI present=" + (uri != null));
                    if (uri != null) {
                        addCroppedImage(uri);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> ocrReviewLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                awaitingResult = false;
                clearOcrCache();
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    VaultItem prefill = new VaultItem(
                            data.getStringExtra(OcrReviewActivity.EXTRA_NAME),
                            data.getStringExtra(OcrReviewActivity.EXTRA_LOGIN),
                            data.getStringExtra(OcrReviewActivity.EXTRA_PASSWORD),
                            "", "", "");
                    showEntryDialog(prefill, null);
                }
            });

    private final ActivityResultLauncher<String> linkRemoteLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(
                    "application/octet-stream"), uri -> {
                awaitingResult = false;
                if (uri != null) {
                    linkRemote(uri);
                }
            });

    private final ActivityResultLauncher<String> backupLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(
                    "application/octet-stream"), uri -> {
                awaitingResult = false;
                if (uri != null) {
                    exportBackup(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // We handle edge-to-edge insets ourselves so no control is ever hidden behind the
        // status bar or the navigation bar.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        if (savedInstanceState != null) {
            // Restore in-flight capture state so a camera-triggered Activity recreation does
            // not lose the photo URI or wrongly auto-lock during the OCR flow.
            pendingPhotoUri = savedInstanceState.getParcelable(KEY_PENDING_PHOTO);
            awaitingResult = savedInstanceState.getBoolean(KEY_AWAITING, false);
        }
        try {
            repository = new VaultV3Repository(this);
            premiumManager = new PremiumManager(this);
            if (Session.isActive()) {
                restoreSession();
            } else if (!repository.exists()
                    && new File(getFilesDir(), "vault.vlt").isFile()) {
                showFatalError(getString(R.string.err_legacy_vault));
            } else if (repository.exists()) {
                showUnlock();
            } else {
                showFirstRun();
            }
        } catch (GeneralSecurityException | java.io.IOException exception) {
            showFatalError(getString(R.string.err_init));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(KEY_PENDING_PHOTO, pendingPhotoUri);
        outState.putBoolean(KEY_AWAITING, awaitingResult);
    }

    /** Restores an in-memory session that survived Activity recreation (e.g. after OCR). */
    private void restoreSession() throws GeneralSecurityException, IOException {
        sessionTruePin = Session.pin();
        biometricSession = Session.isBiometricSession();
        sessionRecoveredPrevious = Session.isRecoveredPrevious();
        currentData = Session.data();
        sessionDek = Session.dek();
        byte[] qrSecret = Session.recoverySecret();
        if (qrSecret != null) {
            try {
                repository.rememberRecoverySecret(qrSecret);
            } finally {
                Arrays.fill(qrSecret, (byte) 0);
            }
        }
        unlocked = true;
        showVault();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(IMAGE_LOG, "MainActivity.onStop unlocked=" + unlocked
                + ", awaitingResult=" + awaitingResult
                + ", imageFlowActive=" + sImageFlowActive
                + ", lockOnBackground=" + lockOnBackground());
        if (unlocked && lockOnBackground() && !awaitingResult && !sImageFlowActive) {
            Log.d(IMAGE_LOG, "locking because activity stopped outside an intentional flow");
            lock();
            return;
        }
        // Remember when we went to background so the inactivity timeout can apply.
        sBackgroundedElapsed = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(IMAGE_LOG, "MainActivity.onStart sessionActive=" + Session.isActive()
                + ", unlocked=" + unlocked);
        if (unlocked) {
            long backgrounded = sBackgroundedElapsed;
            int minutes = autoLockMinutes();
            if (minutes > 0 && backgrounded > 0
                    && SystemClock.elapsedRealtime() - backgrounded >= minutes * 60_000L) {
                lock();
            } else {
                scheduleAutoLock();
            }
        }
        sBackgroundedElapsed = 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingPreviewFile != null) {
            cleanupPendingPreview();
            awaitingResult = false;
            if (pendingPreviewRecoveredPrevious) {
                pendingPreviewRecoveredPrevious = false;
                toast(R.string.msg_recovered_previous_resource);
                notifyPreviousResourceRecovered();
            }
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (unlocked) {
            scheduleAutoLock();
        }
    }

    /** (Re)starts the inactivity timer according to the configured timeout. */
    private void scheduleAutoLock() {
        inactivityHandler.removeCallbacks(lockRunnable);
        int minutes = autoLockMinutes();
        if (minutes > 0 && unlocked) {
            inactivityHandler.postDelayed(lockRunnable, minutes * 60_000L);
        }
    }

    private int autoLockMinutes() {
        if (!isAutoLockEnabled()) {
            return 0;
        }
        return getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getInt(PREF_AUTOLOCK_MIN, DEFAULT_AUTOLOCK_MIN);
    }

    private void setAutoLockMinutes(int minutes) {
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_AUTOLOCK_MIN, minutes)
                .putBoolean(PREF_AUTOLOCK_ENABLED, true)
                .apply();
        scheduleAutoLock();
    }

    private boolean isAutoLockEnabled() {
        return getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_AUTOLOCK_ENABLED, true);
    }

    private void setAutoLockEnabled(boolean enabled) {
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_AUTOLOCK_ENABLED, enabled).apply();
        scheduleAutoLock();
    }

    private boolean lockOnBackground() {
        return getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_LOCK_BACKGROUND, true);
    }

    private void setLockOnBackground(boolean enabled) {
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_LOCK_BACKGROUND, enabled).apply();
    }

    @Override
    protected void onDestroy() {
        Log.d(IMAGE_LOG, "MainActivity.onDestroy finishing=" + isFinishing());
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        awaitingResult = false;
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() != null && recoveryPayloadField != null) {
                if (scanningPassphraseQr) {
                    try {
                        PassphraseQrPayload payload =
                                PassphraseQrPayload.fromQrString(scan.getContents());
                        recoveryPinField.setText(new String(payload.passphrase));
                        Arrays.fill(payload.passphrase, '\0');
                    } catch (IOException | IllegalArgumentException exception) {
                        toast(R.string.err_recovery);
                    }
                } else {
                    recoveryPayloadField.setText(scan.getContents());
                }
            }
            scanningPassphraseQr = false;
            return;
        }
        if (requestCode == REQUEST_VAULT_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            selectedRecoveryVaultUri = data.getData();
            if (recoveryFileStatus != null) {
                recoveryFileStatus.setVisibility(View.VISIBLE);
                recoveryFileStatus.setText(R.string.action_pick_file);
            }
        }
    }

    // region First run / unlock

    private void showFirstRun() {
        unlocked = false;
        setScreen(R.layout.screen_first_run);
        TextInputEditText pin = findViewById(R.id.editPin);
        TextInputEditText confirm = findViewById(R.id.editConfirm);
        findViewById(R.id.btnCreate).setOnClickListener(view -> {
            char[] first = text(pin);
            char[] second = text(confirm);
            if (!PinPolicy.isValid(first) || !Arrays.equals(first, second)) {
                toast(R.string.err_pin_mismatch);
                clear(first);
                clear(second);
                return;
            }
            try {
                repository.create(first);
                sessionTruePin = first.clone();
                showRecoveryQrDialog();
                showUnlock();
            } catch (GeneralSecurityException | java.io.IOException exception) {
                toast(R.string.err_create);
            } finally {
                clear(first);
                clear(second);
            }
        });
    }

    private void showUnlock() {
        unlocked = false;
        setScreen(R.layout.screen_unlock);
        // The primary unlock is biometric. PIN + RQR2 is exposed by the
        // explicit recovery flow below; daily unlock is intentionally biometric-first.
        findViewById(R.id.tilPin).setVisibility(View.GONE);
        findViewById(R.id.btnUnlock).setVisibility(View.GONE);
        findViewById(R.id.btnBiometric).setOnClickListener(view -> authenticateBiometric());
        findViewById(R.id.btnRecovery).setOnClickListener(view -> showRecoveryImport());
    }

    private void authenticateBiometric() {
        try {
            Cipher cipher = repository.prepareBiometricCipher();
            Executor executor = this::runOnUiThread;
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.action_biometric))
                    .setSubtitle(getString(R.string.app_tagline))
                    .setNegativeButton(getString(R.string.action_cancel), executor, (d, w) -> { })
                    .build();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),
                    new CancellationSignal(), executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            try {
                                VaultV3Repository.OpenResult opened =
                                        repository.openWithBiometric(result.getCryptoObject().getCipher());
                                clearSession();
                                biometricSession = true;
                                currentData = opened.data;
                                sessionDek = opened.copyDek();
                                sessionRecoveredPrevious = opened.recoveredPrevious;
                                showVault();
                                opened.clear();
                            } catch (GeneralSecurityException | java.io.IOException exception) {
                                toast(R.string.err_open);
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            // Daily unlock remains biometric-first; recovery is explicit.
                        }
                    });
        } catch (GeneralSecurityException | java.io.IOException exception) {
            toast(R.string.err_biometric);
        }
    }

    // endregion

    // region Vault list

    private void showVault() {
        unlocked = true;
        setScreen(R.layout.screen_list);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PasswordAdapter(new PasswordAdapter.Listener() {
            @Override
            public void onOpen(VaultItem item) {
                showDetails(item);
            }

            @Override
            public void onCopyPassword(VaultItem item) {
                copyToClipboard(item.title, item.password);
            }

            @Override
            public void onToggleFavorite(VaultItem item) {
                toggleFavorite(item);
            }

            @Override
            public void onLoadThumbnail(VaultItem item, ImageView target) {
                loadThumbnail(item, target);
            }
        });
        recycler.setAdapter(adapter);

        TextInputEditText search = findViewById(R.id.editSearch);
        search.addTextChangedListener(new SimpleWatcher(value -> {
            searchQuery = value;
            renderList();
        }));

        findViewById(R.id.fabAdd).setOnClickListener(view -> showAddChooser());
        renderList();
        Session.set(sessionTruePin, currentData, biometricSession, sessionDek);
        Session.setRecoveredPrevious(sessionRecoveredPrevious);
        byte[] qrSecret = repository.copyRecoverySecret();
        Session.setRecoverySecret(qrSecret);
        Crypto.wipe(qrSecret);
        scheduleAutoLock();
        if (sessionRecoveredPrevious) {
            showRecoveredPreviousDialog();
        } else {
            scheduleOrphanCollection();
        }
    }

    private void showRecoveredPreviousDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_recovery)
                .setMessage(R.string.msg_recovered_previous)
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton(R.string.action_repair_vault, (dialog, which) -> {
                    if (sessionDek == null) {
                        toast(R.string.err_unlock);
                        return;
                    }
                    byte[] key = sessionDek.clone();
                    awaitingResult = true;
                    ioExecutor.execute(() -> {
                        try {
                            VaultData repaired = repository.repairCurrentFromPrevious(key);
                            runOnUiThread(() -> {
                                currentData = repaired;
                                Session.updateData(currentData);
                                renderList();
                                sessionRecoveredPrevious = false;
                                Session.setRecoveredPrevious(false);
                                awaitingResult = false;
                                toast(R.string.msg_vault_repaired);
                            });
                        } catch (IOException | GeneralSecurityException exception) {
                            String reason = exception.getMessage();
                            runOnUiThread(() -> {
                                awaitingResult = false;
                                showBackupError(getString(R.string.err_recovery), reason);
                            });
                        } finally {
                            Crypto.wipe(key);
                        }
                    });
                })
                .show();
    }

    private boolean onMenuItem(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_generator) {
            showGenerator(null);
            return true;
        }
        if (id == R.id.action_health) {
            showHealth();
            return true;
        }
        if (id == R.id.action_favorites) {
            favoritesOnly = !favoritesOnly;
            item.setChecked(favoritesOnly);
            renderList();
            return true;
        }
        if (id == R.id.action_settings) {
            showSettings();
            return true;
        }
        if (id == R.id.action_tutorial) {
            showTutorial();
            return true;
        }
        if (id == R.id.action_lock) {
            lock();
            return true;
        }
        return false;
    }

    private void renderList() {
        List<VaultItem> filtered = new ArrayList<>();
        String query = searchQuery.trim().toLowerCase(Locale.getDefault());
        for (VaultItem item : currentData.items) {
            if ((!favoritesOnly || item.favorite)
                    && (query.isEmpty() || matches(item, query))) {
                filtered.add(item);
            }
        }
        adapter.submit(filtered);
        View empty = findViewById(R.id.emptyState);
        View recycler = findViewById(R.id.recycler);
        boolean isEmpty = filtered.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private static boolean matches(VaultItem item, String query) {
        return item.title.toLowerCase(Locale.getDefault()).contains(query)
                || item.username.toLowerCase(Locale.getDefault()).contains(query)
                || item.url.toLowerCase(Locale.getDefault()).contains(query)
                || item.folder.toLowerCase(Locale.getDefault()).contains(query)
                || item.tags.toLowerCase(Locale.getDefault()).contains(query);
    }

    private void toggleFavorite(VaultItem item) {
        List<VaultItem> items = new ArrayList<>(currentData.items);
        int index = items.indexOf(item);
        if (index < 0) {
            return;
        }
        items.set(index, item.withFavorite(!item.favorite));
        saveData(new VaultData(items));
    }

    private void showHealth() {
        PasswordHealth health = PasswordHealth.analyze(currentData.items);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.health_title)
                .setMessage(getString(R.string.health_summary, health.total,
                        health.weak, health.reused) + "\n\n"
                        + getString(R.string.health_private_note))
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    // endregion

    // region Entry / details

    private void showDetails(VaultItem item) {
        if (item.isImage()) {
            showImageDetails(item);
            return;
        }
        if (item.isFile()) {
            showFileDetails(item);
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_details, null);
        TextView username = view.findViewById(R.id.valueUsername);
        TextView password = view.findViewById(R.id.valuePassword);
        TextView url = view.findViewById(R.id.valueUrl);
        TextView notes = view.findViewById(R.id.valueNotes);
        TextView totp = view.findViewById(R.id.valueTotp);
        username.setText(item.username.isEmpty() ? "—" : item.username);
        url.setText(item.url.isEmpty() ? "—" : item.url);
        notes.setText(item.notes.isEmpty() ? "—" : item.notes);
        totp.setText("••••••");
        view.findViewById(R.id.rowUrl).setVisibility(item.url.isEmpty() ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.rowNotes).setVisibility(item.notes.isEmpty() ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.rowTotp).setVisibility(item.totpSecret.isEmpty() ? View.GONE : View.VISIBLE);

        final boolean[] shown = {false};
        password.setText(mask(item.password));
        MaterialButton toggle = view.findViewById(R.id.togglePassword);
        toggle.setOnClickListener(v -> {
            shown[0] = !shown[0];
            password.setText(shown[0] ? item.password : mask(item.password));
            toggle.setIconResource(shown[0] ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        });
        view.findViewById(R.id.copyUsername).setOnClickListener(v ->
                copyToClipboard(getString(R.string.hint_username), item.username));
        view.findViewById(R.id.copyPassword).setOnClickListener(v ->
                copyToClipboard(item.title, item.password));
        final String[] visibleTotp = {null};
        view.findViewById(R.id.showTotp).setOnClickListener(v -> {
            try {
                visibleTotp[0] = Totp.code(item.totpSecret, System.currentTimeMillis());
                totp.setText(visibleTotp[0]);
            } catch (GeneralSecurityException | IllegalArgumentException exception) {
                totp.setText("—");
                toast(R.string.totp_invalid);
            }
        });
        view.findViewById(R.id.copyTotp).setOnClickListener(v -> {
            if (visibleTotp[0] == null) {
                toast(R.string.action_show_totp);
            } else {
                copyToClipboard(getString(R.string.totp_label), visibleTotp[0]);
            }
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle(item.title.isEmpty() ? "—" : item.title)
                .setView(view)
                .setNeutralButton(R.string.action_edit, (d, w) -> showEntryDialog(item, item))
                .setNegativeButton(R.string.action_delete, (d, w) -> confirmDelete(item))
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private void showImageDetails(VaultItem item) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_image_details, null);
        ImageView image = view.findViewById(R.id.imagePreview);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(item.title)
                .setView(view)
                .setNegativeButton(R.string.action_delete, (d, which) -> {
                    List<VaultItem> items = new ArrayList<>(currentData.items);
                    items.remove(item);
                    saveData(new VaultData(items));
                })
                .setPositiveButton(R.string.action_close, null)
                .create();
        dialog.show();
        loadImagePreview(item, image, dialog);
    }

    private void loadImagePreview(VaultItem item, ImageView target,
                                  androidx.appcompat.app.AlertDialog dialog) {
        if (sessionDek == null) {
            toast(R.string.err_unlock);
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            byte[] jpeg = null;
            Bitmap bitmap = null;
            try {
                VaultV3Repository.ResourceResult resource = repository.readResource(key,
                        item.attachmentId, item.entryId, VaultObjectRef.Type.IMAGE);
                jpeg = resource.plaintext;
                bitmap = VaultImageProcessor.decodeForDisplay(jpeg,
                        VaultImageProcessor.MAX_IMAGE_BYTES, 2048);
                Bitmap decoded = bitmap;
                runOnUiThread(() -> {
                    if (unlocked && dialog.isShowing()) {
                        target.setImageBitmap(decoded);
                        if (resource.recoveredPrevious) {
                            notifyPreviousResourceRecovered();
                        }
                    } else {
                        decoded.recycle();
                    }
                });
            } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        toast(R.string.err_image);
                    }
                });
                if (bitmap != null) {
                    bitmap.recycle();
                }
            } finally {
                Crypto.wipe(jpeg);
                Crypto.wipe(key);
            }
        });
    }

    private void loadThumbnail(VaultItem item, ImageView target) {
        if (sessionDek == null || item.thumbnailId == null) {
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            byte[] jpeg = null;
            Bitmap bitmap = null;
            try {
                VaultV3Repository.ResourceResult resource = repository.readResource(key,
                        item.thumbnailId, item.entryId, VaultObjectRef.Type.THUMBNAIL);
                jpeg = resource.plaintext;
                bitmap = VaultImageProcessor.decodeForDisplay(jpeg,
                        VaultImageProcessor.MAX_THUMBNAIL_BYTES, 256);
                Bitmap decoded = bitmap;
                runOnUiThread(() -> {
                    Object tag = target.getTag();
                    if (unlocked && item.entryId.equals(tag)) {
                        androidx.core.widget.ImageViewCompat.setImageTintList(target, null);
                        target.setImageBitmap(decoded);
                        if (resource.recoveredPrevious) {
                            notifyPreviousResourceRecovered();
                        }
                    } else {
                        decoded.recycle();
                    }
                });
            } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
                Log.w(IMAGE_LOG, "thumbnail load failed: "
                        + exception.getClass().getSimpleName());
                if (bitmap != null) {
                    bitmap.recycle();
                }
            } finally {
                Crypto.wipe(jpeg);
                Crypto.wipe(key);
            }
        });
    }

    private void showFileDetails(VaultItem item) {
        String message = getString(R.string.file_details_message, item.contentType,
                android.text.format.Formatter.formatShortFileSize(this, item.attachmentSize));
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.title)
                .setMessage(message)
                .setNeutralButton(R.string.action_export, (dialog, which) -> {
                    pendingFileExport = item;
                    awaitingResult = true;
                    exportFileLauncher.launch(safeFileName(item.title));
                })
                .setNegativeButton(R.string.action_delete, (dialog, which) -> {
                    List<VaultItem> items = new ArrayList<>(currentData.items);
                    items.remove(item);
                    saveData(new VaultData(items));
                })
                .setPositiveButton(R.string.action_preview_file, (dialog, which) ->
                        previewAttachment(item))
                .show();
    }

    private void previewAttachment(VaultItem item) {
        if (sessionDek == null) {
            toast(R.string.err_unlock);
            return;
        }
        byte[] key = sessionDek.clone();
        awaitingResult = true;
        ioExecutor.execute(() -> {
            byte[] plaintext = null;
            File previewFile = null;
            try {
                VaultV3Repository.ResourceResult resource = repository.readResource(key,
                        item.attachmentId, item.entryId, VaultObjectRef.Type.FILE);
                plaintext = resource.plaintext;
                File directory = new File(getCacheDir(), "preview");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("Cannot create preview directory");
                }
                previewFile = File.createTempFile("attachment-",
                        previewFileSuffix(item), directory);
                try (java.io.FileOutputStream output =
                             new java.io.FileOutputStream(previewFile)) {
                    output.write(plaintext);
                    output.flush();
                }
                File readyFile = previewFile;
                previewFile = null;
                runOnUiThread(() -> launchAttachmentPreview(item, readyFile,
                        previewMimeType(item), resource.recoveredPrevious));
            } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
                Log.e(IMAGE_LOG, "file preview failed: "
                        + exception.getClass().getSimpleName());
                runOnUiThread(() -> {
                    awaitingResult = false;
                    toast(R.string.err_file);
                });
            } finally {
                Crypto.wipe(plaintext);
                Crypto.wipe(key);
                if (previewFile != null && !previewFile.delete()) {
                    Log.w(IMAGE_LOG, "could not remove incomplete file preview");
                }
            }
        });
    }

    private void launchAttachmentPreview(VaultItem item, File file, String mimeType,
                                         boolean recoveredPrevious) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mimeType);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setClipData(ClipData.newUri(getContentResolver(), item.title, uri));
            pendingPreviewFile = file;
            pendingPreviewRecoveredPrevious = recoveredPrevious;
            awaitingResult = true;
            startActivity(Intent.createChooser(view, getString(R.string.action_preview_file)));
        } catch (ActivityNotFoundException exception) {
            Log.w(IMAGE_LOG, "no installed app can preview " + mimeType);
            pendingPreviewFile = null;
            if (!file.delete()) {
                Log.w(IMAGE_LOG, "could not remove unavailable file preview");
            }
            pendingPreviewRecoveredPrevious = false;
            awaitingResult = false;
            toast(R.string.err_file_preview_unavailable);
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.e(IMAGE_LOG, "could not launch file preview: "
                    + exception.getClass().getSimpleName());
            pendingPreviewFile = null;
            if (!file.delete()) {
                Log.w(IMAGE_LOG, "could not remove failed file preview");
            }
            pendingPreviewRecoveredPrevious = false;
            awaitingResult = false;
            toast(R.string.err_file);
        }
    }

    private void cleanupPendingPreview() {
        File file = pendingPreviewFile;
        pendingPreviewFile = null;
        if (file.exists() && !file.delete()) {
            Log.w(IMAGE_LOG, "could not remove temporary file preview");
        }
    }

    private static String previewMimeType(VaultItem item) {
        String mimeType = validMimeType(item.contentType);
        if (!"application/octet-stream".equals(mimeType)) {
            return mimeType;
        }
        String extension = fileExtension(item.title);
        String inferred = extension.isEmpty() ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return inferred == null ? mimeType : inferred;
    }

    private static String previewFileSuffix(VaultItem item) {
        String extension = fileExtension(item.title);
        if (extension.isEmpty()) {
            String mimeExtension = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(previewMimeType(item));
            if (mimeExtension != null && mimeExtension.length() <= 16) {
                extension = fileExtension("file." + mimeExtension);
            }
        }
        return extension.isEmpty() ? ".tmp" : "." + extension;
    }

    private static String fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.length() > 16) {
            return "";
        }
        for (int i = 0; i < extension.length(); i++) {
            char character = extension.charAt(i);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')) {
                return "";
            }
        }
        return extension;
    }

    private void exportAttachment(Uri uri, VaultItem item) {
        if (sessionDek == null) {
            toast(R.string.err_unlock);
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            byte[] plaintext = null;
            try {
                VaultV3Repository.ResourceResult resource = repository.readResource(key,
                        item.attachmentId, item.entryId, VaultObjectRef.Type.FILE);
                plaintext = resource.plaintext;
                try (java.io.OutputStream output =
                             getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) {
                        throw new IOException("Selected export location is unavailable");
                    }
                    output.write(plaintext);
                    output.flush();
                }
                int message = resource.recoveredPrevious
                        ? R.string.msg_recovered_previous_resource : R.string.msg_file_exported;
                runOnUiThread(() -> {
                    toast(message);
                    if (resource.recoveredPrevious) {
                        notifyPreviousResourceRecovered();
                    }
                });
            } catch (IOException | GeneralSecurityException exception) {
                runOnUiThread(() -> toast(R.string.err_file));
            } finally {
                Crypto.wipe(plaintext);
                Crypto.wipe(key);
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty()
                ? getString(R.string.item_file_kind) : segment;
    }

    private static String validMimeType(String mimeType) {
        if (mimeType == null || mimeType.length() > VaultObjectRef.MAX_CONTENT_TYPE_LENGTH
                || !mimeType.contains("/")
                || mimeType.contains(" ") || mimeType.contains("\n")) {
            return "application/octet-stream";
        }
        for (int i = 0; i < mimeType.length(); i++) {
            char value = mimeType.charAt(i);
            if (!(value >= 'a' && value <= 'z') && !(value >= 'A' && value <= 'Z')
                    && !(value >= '0' && value <= '9') && value != '/' && value != '-'
                    && value != '+' && value != '.' && value != '_') {
                return "application/octet-stream";
            }
        }
        return mimeType;
    }

    private static String safeFileName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            return "attachment";
        }
        return name.replace('/', '_').replace('\\', '_');
    }

    private static String newObjectId() {
        byte[] id = VaultObjectIds.randomId();
        try {
            return VaultObjectIds.toHex(id);
        } finally {
            Crypto.wipe(id);
        }
    }

    private void notifyPreviousResourceRecovered() {
        if (!sessionRecoveredPrevious) {
            sessionRecoveredPrevious = true;
            Session.setRecoveredPrevious(true);
            showRecoveredPreviousDialog();
        }
    }

    private static void clearPending(List<PendingVaultAsset> pending) {
        for (PendingVaultAsset asset : pending) {
            asset.clear();
            Crypto.wipe(asset.objectId);
            Crypto.wipe(asset.ownerEntryId);
        }
    }

    private void confirmDelete(VaultItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_message,
                        item.title.isEmpty() ? "—" : item.title))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    List<VaultItem> items = new ArrayList<>(currentData.items);
                    items.remove(item);
                    saveData(new VaultData(items));
                })
                .show();
    }

    private void showEntryDialog(VaultItem prefill, VaultItem replaceTarget) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_entry, null);
        TextInputEditText title = view.findViewById(R.id.editTitle);
        TextInputEditText username = view.findViewById(R.id.editUsername);
        TextInputEditText password = view.findViewById(R.id.editPassword);
        TextInputEditText url = view.findViewById(R.id.editUrl);
        TextInputEditText notes = view.findViewById(R.id.editNotes);
        TextInputEditText folder = view.findViewById(R.id.editFolder);
        TextInputEditText tags = view.findViewById(R.id.editTags);
        TextInputEditText totp = view.findViewById(R.id.editTotp);
        com.google.android.material.materialswitch.MaterialSwitch favorite =
                view.findViewById(R.id.switchFavorite);
        if (prefill != null) {
            title.setText(prefill.title);
            username.setText(prefill.username);
            password.setText(prefill.password);
            url.setText(prefill.url);
            notes.setText(prefill.notes);
            folder.setText(prefill.folder);
            tags.setText(prefill.tags);
            totp.setText(prefill.totpSecret);
            favorite.setChecked(prefill.favorite);
        }
        view.findViewById(R.id.btnGeneratePassword).setOnClickListener(v ->
                showGenerator(password::setText));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(replaceTarget == null ? R.string.entry_new : R.string.entry_edit)
                .setView(view)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    VaultItem edited = replaceTarget == null
                            ? new VaultItem(str(title), str(username), str(password),
                            str(url), str(notes), str(tags), str(folder), str(totp),
                            favorite.isChecked())
                            : new VaultItem(replaceTarget.entryId, str(title), str(username),
                            str(password), str(url), str(notes), str(tags), str(folder),
                            str(totp), favorite.isChecked(), VaultItem.TYPE_LOGIN,
                            null, null, "", 0);
                    List<VaultItem> items = new ArrayList<>(currentData.items);
                    if (replaceTarget != null) {
                        items.remove(replaceTarget);
                    }
                    items.add(edited);
                    saveData(new VaultData(items));
                });
        if (replaceTarget != null) {
            builder.setNeutralButton(R.string.action_delete, (d, w) -> {
                List<VaultItem> items = new ArrayList<>(currentData.items);
                items.remove(replaceTarget);
                saveData(new VaultData(items));
            });
        }
        builder.show();
    }

    // endregion

    // region Generator

    private void showGenerator(java.util.function.Consumer<String> onUse) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_generator, null);
        TextView output = view.findViewById(R.id.generatedPassword);
        TextView lengthValue = view.findViewById(R.id.lengthValue);
        Slider slider = view.findViewById(R.id.sliderLength);
        MaterialSwitch upper = view.findViewById(R.id.switchUpper);
        MaterialSwitch lower = view.findViewById(R.id.switchLower);
        MaterialSwitch digits = view.findViewById(R.id.switchDigits);
        MaterialSwitch symbols = view.findViewById(R.id.switchSymbols);

        Runnable regenerate = () -> {
            int length = (int) slider.getValue();
            lengthValue.setText(String.valueOf(length));
            output.setText(generatePassword(length, upper.isChecked(), lower.isChecked(),
                    digits.isChecked(), symbols.isChecked()));
        };
        slider.addOnChangeListener((s, value, fromUser) -> regenerate.run());
        upper.setOnCheckedChangeListener((b, c) -> regenerate.run());
        lower.setOnCheckedChangeListener((b, c) -> regenerate.run());
        digits.setOnCheckedChangeListener((b, c) -> regenerate.run());
        symbols.setOnCheckedChangeListener((b, c) -> regenerate.run());
        view.findViewById(R.id.btnRegenerate).setOnClickListener(v -> regenerate.run());
        view.findViewById(R.id.btnCopyGenerated).setOnClickListener(v ->
                copyToClipboard(getString(R.string.generator_title), output.getText().toString()));
        regenerate.run();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.generator_title)
                .setView(view);
        if (onUse != null) {
            builder.setPositiveButton(R.string.action_use,
                    (d, w) -> onUse.accept(output.getText().toString()));
            builder.setNegativeButton(R.string.action_cancel, null);
        } else {
            builder.setPositiveButton(R.string.action_close, null);
        }
        builder.show();
    }

    private String generatePassword(int length, boolean upper, boolean lower,
                                    boolean digits, boolean symbols) {
        StringBuilder alphabet = new StringBuilder();
        if (upper) {
            alphabet.append("ABCDEFGHJKLMNPQRSTUVWXYZ");
        }
        if (lower) {
            alphabet.append("abcdefghijkmnopqrstuvwxyz");
        }
        if (digits) {
            alphabet.append("23456789");
        }
        if (symbols) {
            alphabet.append("!@#$%^&*-_=+?");
        }
        if (alphabet.length() == 0) {
            alphabet.append("abcdefghijkmnopqrstuvwxyz");
        }
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return password.toString();
    }

    // endregion

    // region Settings / recovery / change PIN

    private void showSettings() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_title)
                .setView(view)
                .setNegativeButton(R.string.action_close, null)
                .create();
        View premiumRow = view.findViewById(R.id.rowPremiumDebug);
        premiumRow.setVisibility(premiumManager.isDebugOverrideAvailable()
                ? View.VISIBLE : View.GONE);
        if (premiumManager.isDebugOverrideAvailable()) {
            com.google.android.material.materialswitch.MaterialSwitch premiumSwitch =
                    view.findViewById(R.id.switchPremiumDebug);
            TextView premiumStatus = view.findViewById(R.id.premiumDebugStatus);
            premiumSwitch.setChecked(premiumManager.isPro());
            premiumStatus.setText(getString(R.string.premium_debug_status,
                    premiumManager.isPro() ? getString(R.string.premium_pro)
                            : getString(R.string.premium_free)));
            premiumSwitch.setOnCheckedChangeListener((button, checked) -> {
                premiumManager.setDebugPro(checked);
                premiumStatus.setText(getString(R.string.premium_debug_status,
                        checked ? getString(R.string.premium_pro)
                                : getString(R.string.premium_free)));
            });
        }
        view.findViewById(R.id.rowRecovery).setOnClickListener(v -> {
            dialog.dismiss();
            showRecoveryQrDialog();
        });
        view.findViewById(R.id.rowPassphraseQr).setOnClickListener(v -> {
            dialog.dismiss();
            requestPassphraseForQr();
        });
        view.findViewById(R.id.rowPrivacy).setOnClickListener(v -> showPrivacyDialog());
        view.findViewById(R.id.rowTutorial).setOnClickListener(v -> {
            dialog.dismiss();
            showTutorial();
        });
        View changePin = view.findViewById(R.id.rowChangePin);
        changePin.setOnClickListener(v -> {
            dialog.dismiss();
            showChangePin();
        });
        TextView autoLockValue = view.findViewById(R.id.autoLockValue);
        autoLockValue.setText(autoLockStatus(autoLockMinutes()));
        view.findViewById(R.id.rowAutoLock).setOnClickListener(v ->
                showAutoLockChooser(autoLockValue));
        com.google.android.material.materialswitch.MaterialSwitch autoLockSwitch =
                view.findViewById(R.id.switchAutoLock);
        autoLockSwitch.setChecked(isAutoLockEnabled());
        autoLockSwitch.setOnCheckedChangeListener((button, checked) -> {
            setAutoLockEnabled(checked);
            autoLockValue.setText(autoLockStatus(autoLockMinutes()));
        });
        com.google.android.material.materialswitch.MaterialSwitch backgroundSwitch =
                view.findViewById(R.id.switchLockBackground);
        backgroundSwitch.setChecked(lockOnBackground());
        backgroundSwitch.setOnCheckedChangeListener((button, checked) ->
                setLockOnBackground(checked));
        view.findViewById(R.id.rowLock).setOnClickListener(v -> {
            dialog.dismiss();
            lock();
        });

        boolean linked = repository.hasRemote();
        TextView remoteStatus = view.findViewById(R.id.remoteStatus);
        remoteStatus.setText(linked
                ? getString(R.string.remote_status_linked, repository.remoteLabel())
                : getString(R.string.remote_status_none));
        view.findViewById(R.id.rowLinkRemote).setOnClickListener(v -> {
            dialog.dismiss();
            awaitingResult = true;
            linkRemoteLauncher.launch(getString(R.string.vault_default_filename));
        });
        view.findViewById(R.id.rowBackup).setOnClickListener(v -> {
            dialog.dismiss();
            awaitingResult = true;
            backupLauncher.launch(getString(R.string.vault_default_filename));
        });
        view.findViewById(R.id.rowImportBackup).setOnClickListener(v -> {
            dialog.dismiss();
            showRecoveryImport();
        });
        View syncNow = view.findViewById(R.id.rowSyncNow);
        View unlink = view.findViewById(R.id.rowUnlinkRemote);
        syncNow.setVisibility(linked ? View.VISIBLE : View.GONE);
        unlink.setVisibility(linked ? View.VISIBLE : View.GONE);
        syncNow.setOnClickListener(v -> {
            dialog.dismiss();
            syncNow();
        });
        unlink.setOnClickListener(v -> {
            dialog.dismiss();
            repository.unlinkRemote();
        });
        view.findViewById(R.id.rowDeleteVault).setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteLocalVault();
        });
        dialog.show();
    }

    private void showPrivacyDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_privacy)
                .setMessage(getString(R.string.privacy_policy_text)
                        + "\n\n" + getString(R.string.privacy_policy_url))
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private void showTutorial() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_help, null);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_tutorial)
                .setView(view)
                .setPositiveButton(R.string.action_close, null)
                .create();
        view.findViewById(R.id.helpAdd).setOnClickListener(v -> showAddChooser());
        view.findViewById(R.id.helpImages).setOnClickListener(v -> showImageSourceChooser());
        view.findViewById(R.id.helpGenerator).setOnClickListener(v -> showGenerator(null));
        view.findViewById(R.id.helpSettings).setOnClickListener(v -> {
            dialog.dismiss();
            showSettings();
        });
        view.findViewById(R.id.helpRecoveryQr).setOnClickListener(v -> showRecoveryQrDialog());
        view.findViewById(R.id.helpRecovery).setOnClickListener(v -> {
            dialog.dismiss();
            showRecoveryImport();
        });
        dialog.show();
    }

    private void confirmDeleteLocalVault() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_vault_title)
                .setMessage(R.string.delete_vault_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    try {
                        repository.deleteLocalVault();
                        clearSession();
                        deleteCacheContents();
                        showFirstRun();
                    } catch (IOException | GeneralSecurityException exception) {
                        toast(R.string.err_save);
                    }
                })
                .show();
    }

    private void deleteCacheContents() {
        File[] files = getCacheDir().listFiles();
        if (files != null) {
            for (File file : files) {
                deleteRecursively(file);
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private void showAutoLockChooser(TextView valueLabel) {
        int[] minutes = {1, 5, 15, 30};
        String[] labels = new String[minutes.length];
        int current = 0;
        int currentMinutes = autoLockMinutes();
        for (int i = 0; i < minutes.length; i++) {
            labels[i] = autoLockLabel(minutes[i]);
            if (minutes[i] == currentMinutes) {
                current = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_autolock)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    setAutoLockMinutes(minutes[which]);
                    valueLabel.setText(autoLockStatus(minutes[which]));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String autoLockStatus(int minutes) {
        return autoLockLabel(minutes);
    }

    private String autoLockLabel(int minutes) {
        switch (minutes) {
            case 1: return getString(R.string.autolock_1);
            case 15: return getString(R.string.autolock_15);
            case 30: return getString(R.string.autolock_30);
            case 0: return getString(R.string.autolock_never);
            default: return getString(R.string.autolock_5);
        }
    }

    private void showChangePin() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_pin, null);
        TextInputEditText oldPin = view.findViewById(R.id.editOldPin);
        TextInputEditText newPin = view.findViewById(R.id.editNewPin);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.change_pin_title)
                .setView(view)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_change, (d, w) -> {
                    char[] oldValue = text(oldPin);
                    char[] newValue = text(newPin);
                    if (!PinPolicy.isValid(oldValue) || !PinPolicy.isValid(newValue)) {
                        clear(oldValue);
                        clear(newValue);
                        toast(R.string.err_unlock);
                        return;
                    }
                    if (repository.copyRecoverySecret() != null) {
                        performTruePinChange(oldValue, newValue);
                    } else {
                        authenticateForTruePinChange(oldValue, newValue);
                    }
                })
                .show();
    }

    private void authenticateForTruePinChange(char[] oldValue, char[] newValue) {
        try {
            Cipher cipher = repository.prepareRecoveryQrCipher();
            Executor executor = this::runOnUiThread;
            awaitingResult = true;
            new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.change_pin_title))
                    .setSubtitle(getString(R.string.app_tagline))
                    .setNegativeButton(getString(R.string.action_cancel), executor,
                            (dialog, which) -> {
                                awaitingResult = false;
                                clear(oldValue);
                                clear(newValue);
                            })
                    .build()
                    .authenticate(new BiometricPrompt.CryptoObject(cipher),
                            new CancellationSignal(), executor,
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(
                                        BiometricPrompt.AuthenticationResult result) {
                                    awaitingResult = false;
                                    try {
                                        RecoveryQrPayload payload = repository.openRecoveryQr(
                                                result.getCryptoObject().getCipher());
                                        Crypto.wipe(payload.qrSecret);
                                        performTruePinChange(oldValue, newValue);
                                    } catch (GeneralSecurityException | IOException exception) {
                                        clear(oldValue);
                                        clear(newValue);
                                        toast(R.string.err_change_pin);
                                    }
                                }

                                @Override
                                public void onAuthenticationError(int errorCode,
                                                                  CharSequence errString) {
                                    awaitingResult = false;
                                    clear(oldValue);
                                    clear(newValue);
                                }
                            });
        } catch (GeneralSecurityException | IOException exception) {
            clear(oldValue);
            clear(newValue);
            toast(R.string.err_biometric);
        }
    }

    private void performTruePinChange(char[] oldValue, char[] newValue) {
        char[] sessionPin = newValue.clone();
        byte[] qrSecret = repository.copyRecoverySecret();
        awaitingResult = true;
        ioExecutor.execute(() -> {
            try {
                repository.changeTruePin(oldValue, newValue);
                char[] confirmedPin = sessionPin.clone();
                byte[] confirmedQr = qrSecret == null ? null : qrSecret.clone();
                runOnUiThread(() -> {
                    clear(sessionTruePin);
                    sessionTruePin = confirmedPin.clone();
                    Session.set(sessionTruePin, currentData, biometricSession, sessionDek);
                    Session.setRecoverySecret(confirmedQr);
                    Session.setRecoveredPrevious(sessionRecoveredPrevious);
                    awaitingResult = false;
                    autoBackupAfterSave();
                    toast(R.string.msg_pin_changed);
                    clear(confirmedPin);
                    Crypto.wipe(confirmedQr);
                });
            } catch (GeneralSecurityException | IOException exception) {
                runOnUiThread(() -> {
                    awaitingResult = false;
                    toast(R.string.err_change_pin);
                });
            } finally {
                clear(oldValue);
                clear(newValue);
                clear(sessionPin);
                Crypto.wipe(qrSecret);
            }
        });
    }

    private void showRecoveryQrDialog() {
        try {
            RecoveryQrPayload payload = repository.createRecoveryPayload();
            displayRecoveryQr(payload);
        } catch (GeneralSecurityException | java.io.IOException
                 | com.google.zxing.WriterException exception) {
            authenticateForRecoveryQr();
        }
    }

    private void displayRecoveryQr(RecoveryQrPayload payload)
            throws IOException, com.google.zxing.WriterException {
        Session.setRecoverySecret(payload.qrSecret);
        String encoded = payload.toQrString();
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_recovery_qr, null);
        ImageView qr = view.findViewById(R.id.qrImage);
        Bitmap bitmap = encodeQrBitmap(encoded, 640);
        qr.setImageBitmap(bitmap);
        view.findViewById(R.id.btnCopyPayload).setOnClickListener(v ->
                copyToClipboard(getString(R.string.recovery_qr_title), encoded));
        view.findViewById(R.id.btnShareQr).setOnClickListener(v ->
                shareQrAsJpg(encoded, "recovery-qr"));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recovery_qr_title)
                .setView(view)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private void authenticateForRecoveryQr() {
        try {
            Cipher cipher = repository.prepareRecoveryQrCipher();
            Executor executor = this::runOnUiThread;
            awaitingResult = true;
            new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.recovery_qr_title))
                    .setSubtitle(getString(R.string.app_tagline))
                    .setNegativeButton(getString(R.string.action_cancel), executor,
                            (dialog, which) -> awaitingResult = false)
                    .build()
                    .authenticate(new BiometricPrompt.CryptoObject(cipher),
                            new CancellationSignal(), executor,
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(
                                        BiometricPrompt.AuthenticationResult result) {
                                    awaitingResult = false;
                                    try {
                                        displayRecoveryQr(repository.openRecoveryQr(
                                                result.getCryptoObject().getCipher()));
                                    } catch (GeneralSecurityException | IOException
                                             | com.google.zxing.WriterException exception) {
                                        toast(R.string.err_recovery_create);
                                    }
                                }

                                @Override
                                public void onAuthenticationError(int errorCode,
                                                                  CharSequence errString) {
                                    awaitingResult = false;
                                }
                            });
        } catch (GeneralSecurityException | IOException exception) {
            toast(R.string.err_recovery_create);
        }
    }

    private void requestPassphraseForQr() {
        if (sessionTruePin != null) {
            showPassphraseQrDialog(sessionTruePin);
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_passphrase, null);
        TextInputEditText field = view.findViewById(R.id.editPassphrase);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.passphrase_qr_title)
                .setView(view)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_show, (dialog, which) -> {
                    char[] passphrase = text(field);
                    try {
                        if (!PinPolicy.isValid(passphrase)) {
                            toast(R.string.err_unlock);
                        } else {
                            showPassphraseQrDialog(passphrase);
                        }
                    } finally {
                        clear(passphrase);
                    }
                })
                .show();
    }

    private void showPassphraseQrDialog(char[] passphrase) {
        try {
            PassphraseQrPayload payload = repository.createPassphraseQrPayload(passphrase);
            String encoded = payload.toQrString();
            View view = LayoutInflater.from(this).inflate(R.layout.dialog_passphrase_qr, null);
            ImageView qr = view.findViewById(R.id.passphraseQrImage);
            qr.setImageBitmap(encodeQrBitmap(encoded, 640));
            view.findViewById(R.id.btnSharePassphraseQr).setOnClickListener(v ->
                    shareQrAsJpg(encoded, "passphrase-qr"));
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.passphrase_qr_title)
                    .setView(view)
                    .setPositiveButton(R.string.action_close, null)
                    .show();
        } catch (GeneralSecurityException | IOException | com.google.zxing.WriterException exception) {
            toast(R.string.err_recovery_create);
        }
    }

    private void shareQrAsJpg(String encoded, String name)
            throws RuntimeException {
        try {
            File directory = new File(getCacheDir(), "shared");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Cannot create QR share directory");
            }
            File file = new File(directory, name + "-" + System.currentTimeMillis() + ".jpg");
            Bitmap bitmap = encodeQrBitmap(encoded, 1200);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) {
                    throw new IOException("Cannot encode QR JPG");
                }
            } finally {
                bitmap.recycle();
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/jpeg");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.action_share_jpg)));
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> { if (file.exists()) file.delete(); }, 10 * 60 * 1000L);
        } catch (IOException | com.google.zxing.WriterException exception) {
            toast(R.string.err_recovery_create);
        }
    }

    /**
     * Encodes QR with a four-module quiet zone and medium error correction. The quiet zone
     * is critical when scanning a QR displayed on another screen: without it, camera
     * autofocus and the QR detector can merge the code into the surrounding UI.
     */
    private static Bitmap encodeQrBitmap(String value, int size)
            throws com.google.zxing.WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        return new BarcodeEncoder().encodeBitmap(value, BarcodeFormat.QR_CODE,
                size, size, hints);
    }

    private void showRecoveryImport() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_recovery, null);
        recoveryPayloadField = view.findViewById(R.id.editPayload);
        recoveryPinField = view.findViewById(R.id.editPin);
        recoveryFileStatus = view.findViewById(R.id.fileStatus);
        view.findViewById(R.id.btnScan).setOnClickListener(v -> {
            scanningPassphraseQr = false;
            awaitingResult = true;
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setCaptureActivity(PortraitCaptureActivity.class)
                    .setOrientationLocked(true)
                    .setPrompt(getString(R.string.action_scan))
                    .setBeepEnabled(false)
                    .initiateScan();
        });
        view.findViewById(R.id.btnScanPassphrase).setOnClickListener(v -> {
            scanningPassphraseQr = true;
            awaitingResult = true;
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setCaptureActivity(PortraitCaptureActivity.class)
                    .setOrientationLocked(true)
                    .setPrompt(getString(R.string.action_scan_passphrase))
                    .setBeepEnabled(false)
                    .initiateScan();
        });
        view.findViewById(R.id.btnPickFile).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            awaitingResult = true;
            startActivityForResult(intent, REQUEST_VAULT_FILE);
        });
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recovery_title)
                .setView(view)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_recover, (d, w) -> {
                    if (selectedRecoveryVaultUri == null) {
                        toast(R.string.err_pick_file);
                        return;
                    }
                    char[] pin = text(recoveryPinField);
                    final RecoveryQrPayload recovery;
                    try {
                        recovery = RecoveryQrPayload.fromQrString(str(recoveryPayloadField));
                    } catch (IOException | IllegalArgumentException exception) {
                        clear(pin);
                        toast(R.string.err_recovery);
                        return;
                    }
                    Uri backup = selectedRecoveryVaultUri;
                    awaitingResult = true;
                    clearSession();
                    showUnlock();
                    ioExecutor.execute(() -> {
                        try {
                            repository.importCopy(backup, recovery, pin);
                            runOnUiThread(() -> {
                                selectedRecoveryVaultUri = null;
                                clearSession();
                                byte[] qrSecret = repository.copyRecoverySecret();
                                Session.setRecoverySecret(qrSecret);
                                Crypto.wipe(qrSecret);
                                awaitingResult = false;
                                showUnlock();
                                toast(R.string.msg_imported_backup);
                            });
                        } catch (GeneralSecurityException | IOException exception) {
                            String reason = exception.getMessage();
                            runOnUiThread(() -> {
                                awaitingResult = false;
                                showBackupError(getString(R.string.err_recovery), reason);
                            });
                        } finally {
                            Crypto.wipe(recovery.qrSecret);
                            clear(pin);
                        }
                    });
                })
                .show();
    }

    // endregion

    // region Persistence helpers

    private void saveData(VaultData data) {
        saveData(data, Collections.emptyList());
    }

    private void saveData(VaultData data, List<PendingVaultAsset> pending) {
        if (sessionRecoveredPrevious) {
            clearPending(pending);
            toast(R.string.err_recovered_read_only);
            return;
        }
        if (saveInProgress) {
            clearPending(pending);
            toast(R.string.err_save);
            return;
        }
        boolean imageAdded = countImages(data) > countImages(currentData);
        boolean fileAdded = countFiles(data) > countFiles(currentData);
        Log.d(IMAGE_LOG, "saveData imageEntries=" + countImages(data)
                + ", biometric=" + biometricSession
                + ", sessionActive=" + Session.isActive());
        saveInProgress = true;
        awaitingResult = true;
        if (biometricSession) {
            authenticateBiometricForSave(data, pending, imageAdded, fileAdded);
        } else if (sessionTruePin == null || !PinPolicy.isValid(sessionTruePin)) {
            saveInProgress = false;
            awaitingResult = false;
            clearPending(pending);
            toast(R.string.err_unlock);
            lock();
        } else {
            char[] pin = sessionTruePin.clone();
            try {
                ioExecutor.execute(() -> {
                    try {
                        repository.updateWithTruePin(pin, data, pending);
                        runOnUiThread(() -> finishSave(data, imageAdded, fileAdded));
                    } catch (VaultSizeLimitException exception) {
                        Log.e(IMAGE_LOG, "vault size limit reached");
                        runOnUiThread(() -> failSave(R.string.err_vault_full));
                    } catch (GeneralSecurityException | IOException
                             | IllegalArgumentException exception) {
                        Log.e(IMAGE_LOG, "vault update failed: "
                                + exception.getClass().getSimpleName());
                        runOnUiThread(() -> failSave(R.string.err_save));
                    } finally {
                        clear(pin);
                        clearPending(pending);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException exception) {
                clear(pin);
                clearPending(pending);
                saveInProgress = false;
                awaitingResult = false;
                toast(R.string.err_save);
            }
        }
    }

    private void finishSave(VaultData data, boolean imageAdded, boolean fileAdded) {
        currentData = data;
        Session.updateData(currentData);
        renderList();
        saveInProgress = false;
        awaitingResult = false;
        scheduleOrphanCollection();
        autoBackupAfterSave();
        if (imageAdded) {
            toast(R.string.msg_image_saved);
        } else if (fileAdded) {
            toast(R.string.msg_file_saved);
        }
    }

    private void failSave(@StringRes int message) {
        saveInProgress = false;
        awaitingResult = false;
        toast(message);
    }

    private void authenticateBiometricForSave(VaultData data, List<PendingVaultAsset> pending,
                                              boolean imageAdded, boolean fileAdded) {
        try {
            Log.d(IMAGE_LOG, "preparing biometric authorization for vault save");
            Cipher cipher = repository.prepareBiometricCipher();
            Executor executor = this::runOnUiThread;
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.action_save))
                    .setSubtitle(getString(R.string.app_tagline))
                    .setNegativeButton(getString(R.string.action_cancel), executor,
                            (d, w) -> {
                                clearPending(pending);
                                saveInProgress = false;
                                awaitingResult = false;
                            })
                    .build();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),
                    new CancellationSignal(), executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            ioExecutor.execute(() -> {
                                try {
                                    repository.updateWithBiometric(
                                            result.getCryptoObject().getCipher(), data, pending);
                                    runOnUiThread(() ->
                                            finishSave(data, imageAdded, fileAdded));
                                } catch (VaultSizeLimitException exception) {
                                    Log.e(IMAGE_LOG, "vault size limit reached");
                                    runOnUiThread(() -> failSave(R.string.err_vault_full));
                                } catch (GeneralSecurityException | IOException
                                         | IllegalArgumentException exception) {
                                    Log.e(IMAGE_LOG, "biometric vault update failed: "
                                            + exception.getClass().getSimpleName());
                                    runOnUiThread(() -> failSave(R.string.err_save));
                                } finally {
                                    clearPending(pending);
                                }
                            });
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            Log.w(IMAGE_LOG, "biometric save authorization error code=" + errorCode);
                            clearPending(pending);
                            saveInProgress = false;
                            awaitingResult = false;
                        }
                    });
        } catch (GeneralSecurityException | IOException exception) {
            clearPending(pending);
            saveInProgress = false;
            awaitingResult = false;
            Log.e(IMAGE_LOG, "biometric save setup failed: "
                    + exception.getClass().getSimpleName());
            toast(R.string.err_biometric);
        }
    }

    private static int countFiles(VaultData data) {
        int count = 0;
        for (VaultItem item : data.items) {
            if (item.isFile()) {
                count++;
            }
        }
        return count;
    }

    /**
     * If an automatic backup target is configured, writes a fresh encrypted copy after
     * every database update. The operational vault stays local; only a ciphertext copy is
     * pushed to the chosen location.
     */
    private void autoBackupAfterSave() {
        if (!repository.hasRemote()) {
            return;
        }
        if (sessionDek == null) {
            showBackupError(getString(R.string.msg_saved_local_sync_failed),
                    getString(R.string.err_unlock));
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            try {
                repository.pushRemote(key);
                runOnUiThread(() -> toast(R.string.msg_synced));
            } catch (Exception exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(
                        getString(R.string.msg_saved_local_sync_failed), reason));
            } finally {
                Crypto.wipe(key);
            }
        });
    }

    private void scheduleOrphanCollection() {
        if (sessionDek == null) {
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            try {
                int removed = repository.collectOrphans(key);
                if (removed > 0) {
                    Log.i(IMAGE_LOG, "removed unreachable VLT3 objects=" + removed);
                }
            } catch (IOException | GeneralSecurityException exception) {
                Log.w(IMAGE_LOG, "VLT3 orphan collection failed: "
                        + exception.getClass().getSimpleName());
            } finally {
                Crypto.wipe(key);
            }
        });
    }

    // endregion

    // region Image container

    private void showAddChooser() {
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.add_manual));
        options.add(getString(R.string.add_image_container));
        options.add(getString(R.string.add_file));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_choose_title)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        showEntryDialog(null, null);
                    } else if (which == 1) {
                        showImageSourceChooser();
                    } else {
                        awaitingResult = true;
                        pickFileLauncher.launch(new String[]{"*/*"});
                    }
                })
                .show();
    }

    private void showImageSourceChooser() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_image_container)
                .setItems(new String[]{getString(R.string.ocr_camera),
                        getString(R.string.ocr_gallery)}, (dialog, which) -> {
                    if (which == 0) {
                        requestContainerCamera();
                    } else {
                        awaitingResult = true;
                        sImageFlowActive = true;
                        containerPickImageLauncher.launch("image/*");
                    }
                })
                .show();
    }

    private void requestContainerCamera() {
        awaitingResult = true;
        sImageFlowActive = true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchContainerCamera();
        } else {
            containerCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchContainerCamera() {
        try {
            File directory = new File(getCacheDir(), "images");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new java.io.IOException("Cannot create capture directory");
            }
            File photo = new File(directory, "capture-" + System.currentTimeMillis() + ".jpg");
            pendingContainerPhotoUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photo);
            awaitingResult = true;
            containerTakePhotoLauncher.launch(pendingContainerPhotoUri);
        } catch (java.io.IOException | IllegalArgumentException exception) {
            sImageFlowActive = false;
            awaitingResult = false;
            toast(R.string.err_image);
        }
    }

    private void launchImageCrop(Uri source) {
        Log.d(IMAGE_LOG, "launchImageCrop scheme=" + (source == null ? "null" : source.getScheme()));
        Intent intent = new Intent(this, ImageCropActivity.class);
        intent.putExtra(ImageCropActivity.EXTRA_IMAGE_URI, source);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        awaitingResult = true;
        sImageFlowActive = true;
        imageCropLauncher.launch(intent);
    }

    private void addCroppedImage(Uri uri) {
        Log.d(IMAGE_LOG, "addCroppedImage started, uriPresent=" + (uri != null));
        awaitingResult = true;
        ioExecutor.execute(() -> {
            byte[] bytes = null;
            byte[] thumbnail = null;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("Image is unavailable");
                }
                bytes = readLimited(input, VaultImageProcessor.MAX_IMAGE_BYTES);
                Log.d(IMAGE_LOG, "cropped JPG read bytes=" + bytes.length);
                thumbnail = VaultImageProcessor.createThumbnail(bytes);
                byte[] acceptedJpeg = bytes;
                byte[] acceptedThumbnail = thumbnail;
                runOnUiThread(() -> requestImageName(acceptedJpeg, acceptedThumbnail));
                bytes = null;
                thumbnail = null;
            } catch (IOException | IllegalArgumentException exception) {
                Log.e(IMAGE_LOG, "cropped image read failed: "
                        + exception.getClass().getSimpleName());
                runOnUiThread(() -> toast(R.string.err_image));
                runOnUiThread(() -> awaitingResult = false);
            } finally {
                Crypto.wipe(bytes);
                Crypto.wipe(thumbnail);
                try {
                    if (uri != null && getPackageName().concat(".fileprovider")
                            .equals(uri.getAuthority())) {
                        getContentResolver().delete(uri, null, null);
                    }
                } catch (RuntimeException ignored) {
                    // The FileProvider owns the temporary file; cleanup is best effort.
                }
            }
        });
    }

    private void requestImageName(byte[] jpeg, byte[] thumbnail) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_image_name, null);
        TextInputEditText name = view.findViewById(R.id.editImageName);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_image_container)
                .setView(view)
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                    Crypto.wipe(jpeg);
                    Crypto.wipe(thumbnail);
                    awaitingResult = false;
                })
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String title = str(name).trim();
                    if (title.isEmpty()) {
                        title = "Obraz";
                    }
                    String imageId = newObjectId();
                    String thumbnailId = newObjectId();
                    VaultItem item = VaultItem.imageReference(title, imageId, thumbnailId,
                            jpeg.length);
                    List<PendingVaultAsset> pending = Arrays.asList(
                            new PendingVaultAsset(imageId, item.entryId,
                                    VaultObjectRef.Type.IMAGE, "image/jpeg", jpeg),
                            new PendingVaultAsset(thumbnailId, item.entryId,
                                    VaultObjectRef.Type.THUMBNAIL, "image/jpeg", thumbnail));
                    List<VaultItem> items = new ArrayList<>(currentData.items);
                    items.add(item);
                    Crypto.wipe(jpeg);
                    Crypto.wipe(thumbnail);
                    saveData(new VaultData(items), pending);
                })
                .show();
    }

    private void addSelectedFile(Uri uri) {
        if (!premiumManager.isPro()) {
            toast(R.string.msg_file_pro_required);
            return;
        }
        awaitingResult = true;
        ioExecutor.execute(() -> {
            byte[] contents = null;
            try {
                long declaredSize = declaredFileSize(uri);
                if (declaredSize > MAX_FILE_ATTACHMENT_BYTES) {
                    throw new VaultSizeLimitException("File exceeds attachment limit");
                }

                try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new IOException("Selected file is unavailable");
                    }
                    contents = readLimited(input, MAX_FILE_ATTACHMENT_BYTES);
                }
                String title = displayName(uri);
                String contentType = validMimeType(getContentResolver().getType(uri));
                byte[] objectId = VaultObjectIds.randomId();
                String id = VaultObjectIds.toHex(objectId);
                Crypto.wipe(objectId);
                VaultItem item = VaultItem.fileReference(title, id, contentType,
                        contents.length);
                PendingVaultAsset pending = new PendingVaultAsset(id, item.entryId,
                        VaultObjectRef.Type.FILE, contentType, contents);
                List<VaultItem> items = new ArrayList<>(currentData.items);
                items.add(item);
                Crypto.wipe(contents);
                contents = null;
                runOnUiThread(() -> saveData(new VaultData(items),
                        Collections.singletonList(pending)));
            } catch (VaultSizeLimitException exception) {
                Log.e(IMAGE_LOG, "file exceeds VLT3 attachment limit");
                runOnUiThread(() -> {
                    awaitingResult = false;
                    toast(R.string.err_file_size);
                });
            } catch (IOException | IllegalArgumentException exception) {
                Log.e(IMAGE_LOG, "file attachment failed: "
                        + exception.getClass().getSimpleName());
                runOnUiThread(() -> toast(R.string.err_file));
                runOnUiThread(() -> awaitingResult = false);
            } finally {
                Crypto.wipe(contents);
            }
        });
    }

    private long declaredFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    return cursor.getLong(sizeColumn);
                }
            }
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            Log.d(IMAGE_LOG, "provider does not expose file size metadata");
        }
        return -1;
    }

    private static int countImages(VaultData data) {
        int count = 0;
        for (VaultItem item : data.items) {
            if (item.isImage()) {
                count++;
            }
        }
        return count;
    }

    /** Opens the interactive OCR review screen where the user marks the fields to import. */
    private void launchOcrReview(Uri image) {
        Intent intent = new Intent(this, OcrReviewActivity.class);
        intent.putExtra(OcrReviewActivity.EXTRA_IMAGE_URI, image);
        intent.setData(image);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        awaitingResult = true;
        ocrReviewLauncher.launch(intent);
    }

    private void clearOcrCache() {
        pendingPhotoUri = null;
        File directory = new File(getCacheDir(), "ocr");
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    // endregion

    // region External backup copy

    /** Sets (or replaces) the automatic backup target and writes the first copy. */
    private void linkRemote(Uri uri) {
        if (sessionDek == null) {
            toast(R.string.err_unlock);
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            try {
                repository.linkRemote(uri, key);
                runOnUiThread(() -> toast(R.string.msg_remote_linked));
            } catch (java.io.IOException | GeneralSecurityException exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(getString(R.string.err_remote_link), reason));
            } finally {
                Crypto.wipe(key);
            }
        });
    }

    /** Writes a one-off encrypted backup copy to any chosen location. */
    private void exportBackup(Uri uri) {
        if (sessionDek == null) {
            toast(R.string.err_unlock);
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            try {
                repository.exportCopy(uri, key);
                runOnUiThread(() -> toast(R.string.msg_backup_done));
            } catch (java.io.IOException | GeneralSecurityException exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(getString(R.string.err_backup), reason));
            } finally {
                Crypto.wipe(key);
            }
        });
    }

    /** Immediately refreshes the copy at the configured automatic backup target. */
    private void syncNow() {
        if (sessionDek == null) {
            toast(R.string.err_unlock);
            return;
        }
        byte[] key = sessionDek.clone();
        ioExecutor.execute(() -> {
            try {
                repository.pushRemote(key);
                runOnUiThread(() -> toast(R.string.msg_synced));
            } catch (java.io.IOException | GeneralSecurityException exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(getString(R.string.err_sync), reason));
            } finally {
                Crypto.wipe(key);
            }
        });
    }

    private void showBackupError(String title, String reason) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(reason == null ? getString(R.string.err_backup) : reason)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    // endregion

    // region Clipboard

    private void copyToClipboard(CharSequence label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        ClipData clip = ClipData.newPlainText(label, value);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        toast(R.string.msg_copied);
        clipboardHandler.removeCallbacksAndMessages(null);
        clipboardHandler.postDelayed(() -> clearClipboard(clipboard), CLIPBOARD_TIMEOUT_MS);
    }

    private void clearClipboard(ClipboardManager clipboard) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip();
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (SecurityException ignored) {
            // Clearing may fail if we are no longer the foreground app; not security-critical.
        }
    }

    // endregion

    // region Misc helpers

    /**
     * Sets a screen layout and applies system-bar/cutout insets as padding on the content
     * root, so toolbars never slide under the status bar and buttons never hide behind the
     * navigation bar (edge-to-edge is enabled by default on modern Android).
     */
    private void setScreen(int layoutRes) {
        setContentView(layoutRes);
        final View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private void lock() {
        clearSession();
        repository.clearInMemorySecrets();
        showUnlock();
    }

    private void showFatalError(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.action_close, (d, w) -> finish())
                .show();
    }

    private void toast(@StringRes int message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void clearSession() {
        if (sessionTruePin != null) {
            clear(sessionTruePin);
        }
        sessionTruePin = null;
        Crypto.wipe(sessionDek);
        sessionDek = null;
        Session.clear();
        biometricSession = false;
        unlocked = false;
        currentData = VaultData.empty();
        clipboardHandler.removeCallbacksAndMessages(null);
        inactivityHandler.removeCallbacks(lockRunnable);
    }

    private static String mask(String value) {
        int length = Math.max(8, Math.min(value.length(), 16));
        char[] dots = new char[length];
        Arrays.fill(dots, '\u2022');
        return new String(dots);
    }

    private static char[] text(TextView field) {
        CharSequence value = field.getText();
        return value == null ? new char[0] : value.toString().toCharArray();
    }

    private static String str(TextView field) {
        CharSequence value = field.getText();
        return value == null ? "" : value.toString();
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static byte[] readLimited(java.io.InputStream input, int maximum) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maximum - total) {
                throw new VaultSizeLimitException("Input exceeds configured size limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    // endregion

    /** Minimal TextWatcher that only reports the final text. */
    private static final class SimpleWatcher implements TextWatcher {
        interface Callback {
            void onText(String value);
        }

        private final Callback callback;

        SimpleWatcher(Callback callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            callback.onText(TextUtils.isEmpty(s) ? "" : s.toString());
        }
    }
}
