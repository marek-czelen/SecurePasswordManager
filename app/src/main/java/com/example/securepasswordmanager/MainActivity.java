package com.example.securepasswordmanager;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
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
import com.example.securepasswordmanager.crypto.PasswordHealth;
import com.example.securepasswordmanager.crypto.Totp;
import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultItem;
import com.example.securepasswordmanager.repository.VaultRepository;
import com.example.securepasswordmanager.security.PinPolicy;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.io.File;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_VAULT_FILE = 4101;
    private static final long CLIPBOARD_TIMEOUT_MS = 30_000L;
    private static final String KEY_PENDING_PHOTO = "pending_photo";
    private static final String KEY_AWAITING = "awaiting_result";
    private static final String APP_PREFS = "app_settings";
    private static final String PREF_AUTOLOCK_MIN = "autolock_min";
    private static final int DEFAULT_AUTOLOCK_MIN = 5;
    // Survives Activity recreation so background time is measured across the OCR/camera flow.
    private static long sBackgroundedElapsed;

    private VaultRepository repository;
    private char[] sessionTruePin;
    private boolean biometricSession;
    private boolean unlocked;
    private VaultData currentData = VaultData.empty();

    private PasswordAdapter adapter;
    private String searchQuery = "";
    private boolean favoritesOnly;

    private TextInputEditText recoveryPayloadField;
    private TextInputEditText recoveryPinField;
    private TextView recoveryFileStatus;
    private byte[] selectedRecoveryVault;

    private final Handler clipboardHandler = new Handler(Looper.getMainLooper());
    private final Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockRunnable = () -> {
        if (unlocked) {
            lock();
        }
    };
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private Uri pendingPhotoUri;
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
                    launchCamera();
                } else {
                    awaitingResult = false;
                    toast(R.string.err_camera_permission);
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
            repository = new VaultRepository(this);
            if (Session.isActive()) {
                restoreSession();
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
    private void restoreSession() {
        sessionTruePin = Session.pin();
        biometricSession = Session.isBiometricSession();
        currentData = Session.data();
        unlocked = true;
        showVault();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // No immediate lock: staying unlocked while actively using the app is intentional.
        // We only remember when we went to background so the inactivity timeout can apply.
        sBackgroundedElapsed = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onStart() {
        super.onStart();
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
        return getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getInt(PREF_AUTOLOCK_MIN, DEFAULT_AUTOLOCK_MIN);
    }

    private void setAutoLockMinutes(int minutes) {
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_AUTOLOCK_MIN, minutes).apply();
        scheduleAutoLock();
    }

    @Override
    protected void onDestroy() {
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
                recoveryPayloadField.setText(scan.getContents());
            }
            return;
        }
        if (requestCode == REQUEST_VAULT_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try (java.io.InputStream input = getContentResolver().openInputStream(data.getData())) {
                if (input == null) {
                    throw new java.io.IOException("Vault file is unavailable");
                }
                selectedRecoveryVault = readLimited(input, 256 * 1024 * 1024);
                if (recoveryFileStatus != null) {
                    recoveryFileStatus.setVisibility(View.VISIBLE);
                    recoveryFileStatus.setText(R.string.action_pick_file);
                }
            } catch (java.io.IOException exception) {
                selectedRecoveryVault = null;
                toast(R.string.err_read_file);
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
        TextInputEditText pin = findViewById(R.id.editPin);
        findViewById(R.id.btnUnlock).setOnClickListener(view -> openWithPin(text(pin)));
        findViewById(R.id.btnBiometric).setOnClickListener(view -> authenticateBiometric());
        findViewById(R.id.btnRecovery).setOnClickListener(view -> showRecoveryImport());
    }

    private void openWithPin(char[] entered) {
        if (!PinPolicy.isValid(entered)) {
            toast(R.string.err_unlock);
            clear(entered);
            return;
        }
        try {
            VaultRepository.OpenResult result = repository.openWithPin(entered);
            clearSession();
            sessionTruePin = entered.clone();
            biometricSession = false;
            currentData = result.data;
            showVault();
            result.clear();
        } catch (GeneralSecurityException | java.io.IOException
                 | IllegalArgumentException exception) {
            toast(R.string.err_unlock);
        } finally {
            clear(entered);
        }
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
                                VaultRepository.OpenResult opened =
                                        repository.openWithBiometric(result.getCryptoObject().getCipher());
                                clearSession();
                                biometricSession = true;
                                currentData = opened.data;
                                showVault();
                                opened.clear();
                            } catch (GeneralSecurityException | java.io.IOException exception) {
                                toast(R.string.err_open);
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            // The PIN path stays available; no fake-mode hint is shown.
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
        });
        recycler.setAdapter(adapter);

        TextInputEditText search = findViewById(R.id.editSearch);
        search.addTextChangedListener(new SimpleWatcher(value -> {
            searchQuery = value;
            renderList();
        }));

        findViewById(R.id.fabAdd).setOnClickListener(view -> showAddChooser());
        renderList();
        Session.set(sessionTruePin, currentData, biometricSession);
        scheduleAutoLock();
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
        VaultItem changed = new VaultItem(item.title, item.username, item.password, item.url,
                item.notes, item.tags, item.folder, item.totpSecret, !item.favorite);
        items.set(index, changed);
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
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_details, null);
        TextView username = view.findViewById(R.id.valueUsername);
        TextView password = view.findViewById(R.id.valuePassword);
        TextView url = view.findViewById(R.id.valueUrl);
        TextView notes = view.findViewById(R.id.valueNotes);
        TextView totp = view.findViewById(R.id.valueTotp);
        username.setText(item.username.isEmpty() ? "—" : item.username);
        url.setText(item.url.isEmpty() ? "—" : item.url);
        notes.setText(item.notes.isEmpty() ? "—" : item.notes);
        String totpCode = "";
        if (!item.totpSecret.isEmpty()) {
            try {
                totpCode = Totp.code(item.totpSecret, System.currentTimeMillis());
            } catch (GeneralSecurityException | IllegalArgumentException exception) {
                totpCode = "—";
            }
        }
        totp.setText(totpCode);
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
        String finalTotpCode = totpCode;
        view.findViewById(R.id.copyTotp).setOnClickListener(v ->
                copyToClipboard(getString(R.string.totp_label), finalTotpCode));

        new MaterialAlertDialogBuilder(this)
                .setTitle(item.title.isEmpty() ? "—" : item.title)
                .setView(view)
                .setNeutralButton(R.string.action_edit, (d, w) -> showEntryDialog(item, item))
                .setNegativeButton(R.string.action_delete, (d, w) -> confirmDelete(item))
                .setPositiveButton(R.string.action_close, null)
                .show();
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
                    VaultItem edited = new VaultItem(str(title), str(username), str(password),
                            str(url), str(notes), str(tags), str(folder), str(totp),
                            favorite.isChecked());
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
        view.findViewById(R.id.rowRecovery).setOnClickListener(v -> {
            dialog.dismiss();
            showRecoveryQrDialog();
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
        dialog.show();
    }

    private void showAutoLockChooser(TextView valueLabel) {
        int[] minutes = {1, 5, 15, 30, 0};
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
        if (sessionTruePin == null) {
            toast(R.string.err_change_pin_true);
            return;
        }
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
                    try {
                        repository.changeTruePin(oldValue, newValue);
                        clearSession();
                        sessionTruePin = newValue.clone();
                        unlocked = true;
                        showVault();
                    } catch (GeneralSecurityException | java.io.IOException exception) {
                        toast(R.string.err_change_pin);
                    } finally {
                        clear(oldValue);
                        clear(newValue);
                    }
                })
                .show();
    }

    private void showRecoveryQrDialog() {
        try {
            RecoveryQrPayload payload = repository.createRecoveryPayload();
            String encoded = payload.toQrString();
            View view = LayoutInflater.from(this).inflate(R.layout.dialog_recovery_qr, null);
            ImageView qr = view.findViewById(R.id.qrImage);
            Bitmap bitmap = new BarcodeEncoder().encodeBitmap(encoded, BarcodeFormat.QR_CODE, 640, 640);
            qr.setImageBitmap(bitmap);
            view.findViewById(R.id.btnCopyPayload).setOnClickListener(v ->
                    copyToClipboard(getString(R.string.recovery_qr_title), encoded));
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.recovery_qr_title)
                    .setView(view)
                    .setPositiveButton(R.string.action_close, null)
                    .show();
        } catch (GeneralSecurityException | java.io.IOException
                 | com.google.zxing.WriterException exception) {
            toast(R.string.err_recovery_create);
        }
    }

    private void showRecoveryImport() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_recovery, null);
        recoveryPayloadField = view.findViewById(R.id.editPayload);
        recoveryPinField = view.findViewById(R.id.editPin);
        recoveryFileStatus = view.findViewById(R.id.fileStatus);
        view.findViewById(R.id.btnScan).setOnClickListener(v -> {
            awaitingResult = true;
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt(getString(R.string.action_scan))
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
                    if (selectedRecoveryVault == null) {
                        toast(R.string.err_pick_file);
                        return;
                    }
                    char[] pin = text(recoveryPinField);
                    try {
                        repository.restore(selectedRecoveryVault,
                                RecoveryQrPayload.fromQrString(str(recoveryPayloadField)), pin);
                        selectedRecoveryVault = null;
                        showUnlock();
                    } catch (GeneralSecurityException | java.io.IOException exception) {
                        toast(R.string.err_recovery);
                    } finally {
                        clear(pin);
                    }
                })
                .show();
    }

    // endregion

    // region Persistence helpers

    private void saveData(VaultData data) {
        try {
            if (biometricSession) {
                authenticateBiometricForSave(data);
            } else if (sessionTruePin == null || !PinPolicy.isValid(sessionTruePin)) {
                // Session lost its PIN (e.g. after a lock). Ask the user to unlock again
                // instead of crashing.
                toast(R.string.err_unlock);
                lock();
            } else {
                repository.updateWithTruePin(sessionTruePin, data);
                currentData = data;
                Session.updateData(currentData);
                renderList();
                autoBackupAfterSave();
            }
        } catch (GeneralSecurityException | java.io.IOException
                 | IllegalArgumentException exception) {
            toast(R.string.err_save);
        }
    }

    private void authenticateBiometricForSave(VaultData data) {
        try {
            Cipher cipher = repository.prepareBiometricCipher();
            Executor executor = this::runOnUiThread;
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.action_save))
                    .setSubtitle(getString(R.string.app_tagline))
                    .setNegativeButton(getString(R.string.action_cancel), executor,
                            (d, w) -> awaitingResult = false)
                    .build();
            awaitingResult = true;
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),
                    new CancellationSignal(), executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            awaitingResult = false;
                            try {
                                repository.updateWithBiometric(
                                        result.getCryptoObject().getCipher(), data);
                                currentData = data;
                                Session.updateData(currentData);
                                renderList();
                                autoBackupAfterSave();
                            } catch (GeneralSecurityException | java.io.IOException exception) {
                                toast(R.string.err_save);
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            awaitingResult = false;
                        }
                    });
        } catch (GeneralSecurityException | java.io.IOException exception) {
            toast(R.string.err_biometric);
        }
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
        ioExecutor.execute(() -> {
            try {
                repository.pushRemote();
                runOnUiThread(() -> toast(R.string.msg_synced));
            } catch (Exception exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(
                        getString(R.string.msg_saved_local_sync_failed), reason));
            }
        });
    }

    // endregion

    // region OCR import

    private void showAddChooser() {
        String[] options = {
                getString(R.string.add_manual),
                getString(R.string.add_from_camera),
                getString(R.string.add_from_gallery)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_choose_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showEntryDialog(null, null);
                    } else if (which == 1) {
                        requestCameraAndCapture();
                    } else {
                        awaitingResult = true;
                        pickImageLauncher.launch("image/*");
                    }
                })
                .show();
    }

    private void requestCameraAndCapture() {
        // Cover the permission dialog too: on some devices it stops the activity, which
        // would otherwise trigger auto-lock and drop the user back to the unlock screen.
        awaitingResult = true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        try {
            File directory = new File(getCacheDir(), "ocr");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new java.io.IOException("Cannot create capture directory");
            }
            File photo = new File(directory, "capture_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photo);
            awaitingResult = true;
            takePhotoLauncher.launch(pendingPhotoUri);
        } catch (java.io.IOException | IllegalArgumentException exception) {
            toast(R.string.err_ocr);
        }
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
        ioExecutor.execute(() -> {
            try {
                repository.linkRemote(uri);
                runOnUiThread(() -> toast(R.string.msg_remote_linked));
            } catch (java.io.IOException exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(getString(R.string.err_remote_link), reason));
            }
        });
    }

    /** Writes a one-off encrypted backup copy to any chosen location. */
    private void exportBackup(Uri uri) {
        ioExecutor.execute(() -> {
            try {
                repository.exportCopy(uri);
                runOnUiThread(() -> toast(R.string.msg_backup_done));
            } catch (java.io.IOException exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(getString(R.string.err_backup), reason));
            }
        });
    }

    /** Immediately refreshes the copy at the configured automatic backup target. */
    private void syncNow() {
        ioExecutor.execute(() -> {
            try {
                repository.pushRemote();
                runOnUiThread(() -> toast(R.string.msg_synced));
            } catch (java.io.IOException exception) {
                String reason = exception.getMessage();
                runOnUiThread(() -> showBackupError(getString(R.string.err_sync), reason));
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
                throw new java.io.IOException("Vault file is too large");
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
