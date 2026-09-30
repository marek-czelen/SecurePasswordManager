package com.example.securepasswordmanager;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class SettingsActivity extends AppCompatActivity {
    public static final String EXTRA_ACTION = "settings_action";
    public static final String EXTRA_REMOTE_LINKED = "settings_remote_linked";
    public static final String EXTRA_REMOTE_STATUS = "settings_remote_status";
    public static final String EXTRA_DEBUG_OVERRIDE = "settings_debug_override";
    public static final String EXTRA_DEBUG_PRO = "settings_debug_pro";

    public static final String ACTION_SHOW_RECOVERY = "show_recovery";
    public static final String ACTION_SHOW_PASSPHRASE_QR = "show_passphrase_qr";
    public static final String ACTION_CHANGE_PIN = "change_pin";
    public static final String ACTION_LOCK = "lock";
    public static final String ACTION_PRIVACY = "privacy";
    public static final String ACTION_TUTORIAL = "tutorial";
    public static final String ACTION_LINK_REMOTE = "link_remote";
    public static final String ACTION_BACKUP = "backup";
    public static final String ACTION_IMPORT_BACKUP = "import_backup";
    public static final String ACTION_SYNC_NOW = "sync_now";
    public static final String ACTION_UNLINK_REMOTE = "unlink_remote";
    public static final String ACTION_DELETE_VAULT = "delete_vault";
    public static final String ACTION_CALIBRATE_DOCUMENT_CAMERA = "calibrate_document_camera";

    private TextView autoLockValue;
    private MaterialSwitch autoLockSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_settings);
        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);

        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(view ->
                getOnBackPressedDispatcher().onBackPressed());

        View premiumRow = findViewById(R.id.rowPremiumDebug);
        boolean debugOverrideAvailable =
                getIntent().getBooleanExtra(EXTRA_DEBUG_OVERRIDE, false);
        premiumRow.setVisibility(debugOverrideAvailable ? View.VISIBLE : View.GONE);
        if (debugOverrideAvailable) {
            PremiumManager premiumManager = new PremiumManager(this);
            MaterialSwitch premiumSwitch = findViewById(R.id.switchPremiumDebug);
            TextView premiumStatus = findViewById(R.id.premiumDebugStatus);
            boolean isPro = getIntent().getBooleanExtra(EXTRA_DEBUG_PRO, false);
            premiumSwitch.setChecked(isPro);
            updatePremiumStatus(premiumStatus, isPro);
            premiumSwitch.setOnCheckedChangeListener((button, checked) -> {
                premiumManager.setDebugPro(checked);
                updatePremiumStatus(premiumStatus, checked);
            });
        }

        findViewById(R.id.rowRecovery).setOnClickListener(view ->
                returnAction(ACTION_SHOW_RECOVERY));
        findViewById(R.id.rowAutofill).setOnClickListener(view -> requestAutofillService());
        findViewById(R.id.rowPassphraseQr).setOnClickListener(view ->
                returnAction(ACTION_SHOW_PASSPHRASE_QR));
        findViewById(R.id.rowChangePin).setOnClickListener(view ->
                returnAction(ACTION_CHANGE_PIN));
        findViewById(R.id.rowDocumentCalibration).setOnClickListener(view ->
                returnAction(ACTION_CALIBRATE_DOCUMENT_CAMERA));
        findViewById(R.id.rowLock).setOnClickListener(view -> returnAction(ACTION_LOCK));
        findViewById(R.id.rowPrivacy).setOnClickListener(view -> returnAction(ACTION_PRIVACY));
        findViewById(R.id.rowTutorial).setOnClickListener(view -> returnAction(ACTION_TUTORIAL));
        findViewById(R.id.rowLinkRemote).setOnClickListener(view ->
                returnAction(ACTION_LINK_REMOTE));
        findViewById(R.id.rowBackup).setOnClickListener(view -> returnAction(ACTION_BACKUP));
        findViewById(R.id.rowImportBackup).setOnClickListener(view ->
                returnAction(ACTION_IMPORT_BACKUP));
        findViewById(R.id.rowSyncNow).setOnClickListener(view ->
                returnAction(ACTION_SYNC_NOW));
        findViewById(R.id.rowUnlinkRemote).setOnClickListener(view ->
                returnAction(ACTION_UNLINK_REMOTE));
        findViewById(R.id.rowDeleteVault).setOnClickListener(view ->
                returnAction(ACTION_DELETE_VAULT));

        boolean linked = getIntent().getBooleanExtra(EXTRA_REMOTE_LINKED, false);
        TextView remoteStatus = findViewById(R.id.remoteStatus);
        remoteStatus.setText(getIntent().getStringExtra(EXTRA_REMOTE_STATUS));
        findViewById(R.id.rowSyncNow).setVisibility(linked ? View.VISIBLE : View.GONE);
        findViewById(R.id.rowUnlinkRemote).setVisibility(linked ? View.VISIBLE : View.GONE);

        autoLockValue = findViewById(R.id.autoLockValue);
        autoLockSwitch = findViewById(R.id.switchAutoLock);
        autoLockSwitch.setChecked(isAutoLockEnabled());
        updateAutoLockStatus();
        autoLockSwitch.setOnCheckedChangeListener((button, checked) -> {
            AppSettings.setAutoLockEnabled(this, checked);
            updateAutoLockStatus();
        });
        findViewById(R.id.rowAutoLock).setOnClickListener(view -> showAutoLockChooser());

        MaterialSwitch lockBackgroundSwitch = findViewById(R.id.switchLockBackground);
        lockBackgroundSwitch.setChecked(AppSettings.lockOnBackground(this));
        lockBackgroundSwitch.setOnCheckedChangeListener((button, checked) ->
                AppSettings.setLockOnBackground(this, checked));
        updateAutofillStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAutofillStatus();
    }

    private void requestAutofillService() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.autofill_setup_title)
                .setMessage(R.string.autofill_setup_message)
                .setPositiveButton(R.string.action_enable_autofill,
                        (dialog, which) -> startActivity(AutofillSetup.settingsIntent(this)))
                .setNegativeButton(R.string.action_later, null)
                .show();
    }

    private void updateAutofillStatus() {
        TextView status = findViewById(R.id.autofillStatus);
        if (AutofillSetup.supportsCredentialProvider(this)) {
            status.setText(R.string.settings_autofill_provider_managed);
        } else {
            status.setText(AutofillSetup.isEnabled(this)
                    ? R.string.settings_autofill_enabled
                    : R.string.settings_autofill_not_enabled);
        }
    }

    private void updatePremiumStatus(TextView status, boolean isPro) {
        status.setText(getString(R.string.premium_debug_status,
                isPro ? getString(R.string.premium_pro) : getString(R.string.premium_free)));
    }

    private void updateAutoLockStatus() {
        autoLockValue.setText(autoLockSwitch.isChecked()
                ? autoLockLabel(configuredAutoLockMinutes())
                : getString(R.string.autolock_never));
    }

    private int configuredAutoLockMinutes() {
        return AppSettings.configuredAutoLockMinutes(this);
    }

    private boolean isAutoLockEnabled() {
        return AppSettings.isAutoLockEnabled(this);
    }

    private String autoLockLabel(int minutes) {
        switch (minutes) {
            case 1:
                return getString(R.string.autolock_1);
            case 15:
                return getString(R.string.autolock_15);
            case 30:
                return getString(R.string.autolock_30);
            default:
                return getString(R.string.autolock_5);
        }
    }

    private void showAutoLockChooser() {
        int[] minutes = {1, 5, 15, 30};
        String[] labels = new String[minutes.length];
        int selected = 0;
        int currentMinutes = isAutoLockEnabled() ? configuredAutoLockMinutes() : 0;
        for (int index = 0; index < minutes.length; index++) {
            labels[index] = autoLockLabel(minutes[index]);
            if (minutes[index] == currentMinutes) {
                selected = index;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_autolock)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    AppSettings.setAutoLockMinutes(this, minutes[which]);
                    autoLockSwitch.setChecked(true);
                    updateAutoLockStatus();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void returnAction(String action) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, action));
        finish();
    }
}
