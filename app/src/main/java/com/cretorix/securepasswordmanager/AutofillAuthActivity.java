package com.cretorix.securepasswordmanager;

import android.content.Intent;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import android.hardware.biometrics.BiometricPrompt;

import com.cretorix.securepasswordmanager.crypto.VaultData;
import com.cretorix.securepasswordmanager.crypto.VaultItem;
import com.cretorix.securepasswordmanager.repository.VaultV3Repository;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;

public final class AutofillAuthActivity extends AppCompatActivity {
    private AutofillForm form;
    private VaultV3Repository repository;
    private boolean finished;
    private boolean pickerVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_autofill_auth);
        form = AutofillForm.fromIntent(getIntent());
        if (form == null) {
            finishCanceled();
            return;
        }
        if (Session.isActive() && !form.requirePicker
                && AutofillMatcher.hostFromUrl(form.webDomain) != null) {
            if (Session.isRecoveredPrevious()) {
                showMessage(R.string.autofill_recovery_required);
                finishCanceled();
                return;
            }
            showCredentials(Session.data());
        } else {
            authenticate();
        }
    }

    private void authenticate() {
        try {
            repository = new VaultV3Repository(this);
            if (!repository.exists()) {
                showMessage(R.string.autofill_vault_unavailable);
                finishCanceled();
                return;
            }
            Cipher cipher = repository.prepareDeviceAuthCipher();
            Executor executor = this::runOnUiThread;
            BiometricPrompt prompt = DeviceAuthenticationPrompt.builder(this,
                    getString(R.string.autofill_unlock_title),
                    getString(R.string.autofill_unlock_subtitle)).build();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),
                    new android.os.CancellationSignal(), executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            if (result.getCryptoObject() == null
                                    || result.getCryptoObject().getCipher() == null) {
                                showMessage(R.string.autofill_auth_failed);
                                finishCanceled();
                                return;
                            }
                            try {
                                VaultV3Repository.OpenResult opened =
                                        repository.openWithDeviceAuth(
                                                result.getCryptoObject().getCipher());
                                try {
                                    if (opened.recoveredPrevious) {
                                        showMessage(R.string.autofill_recovery_required);
                                        finishCanceled();
                                    } else {
                                        showCredentials(opened.data);
                                    }
                                } finally {
                                    opened.clear();
                                    repository.clearInMemorySecrets();
                                }
                            } catch (GeneralSecurityException | IOException exception) {
                                showMessage(R.string.autofill_auth_failed);
                                finishCanceled();
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode,
                                                          CharSequence errorString) {
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                    && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                                showMessage(R.string.autofill_auth_failed);
                            }
                            finishCanceled();
                        }
                    });
        } catch (GeneralSecurityException | IOException exception) {
            showMessage(R.string.autofill_auth_failed);
            finishCanceled();
        }
    }

    private void showCredentials(VaultData data) {
        List<VaultItem> matches = AutofillMatcher.matchingLogins(data.items, form.webDomain);
        if (matches.isEmpty()) {
            showMessage(R.string.autofill_no_matching_credentials);
            finishCanceled();
            return;
        }
        showCredentialPicker(matches);
    }

    private void showCredentialPicker(List<VaultItem> matches) {
        pickerVisible = true;
        setContentView(R.layout.activity_autofill_picker);
        TextView subtitle = findViewById(R.id.autofillPickerSubtitle);
        String matchedHost = AutofillMatcher.hostFromUrl(form.webDomain);
        if (form.targetPackage != null && form.requirePicker) {
            subtitle.setText(matchedHost == null
                    ? getString(R.string.autofill_context_app_only, form.targetPackage)
                    : getString(R.string.autofill_context_host_app,
                    matchedHost, form.targetPackage));
        } else if (matchedHost != null) {
            subtitle.setText(matchedHost);
        } else {
            subtitle.setText(R.string.autofill_choose_login);
        }

        List<CredentialChoice> choices = new ArrayList<>(matches.size());
        for (VaultItem item : matches) {
            choices.add(new CredentialChoice(item,
                    getString(R.string.autofill_unnamed_credential)));
        }
        ArrayAdapter<CredentialChoice> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, choices);
        ListView list = findViewById(R.id.autofillCredentialList);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            CredentialChoice choice = adapter.getItem(position);
            if (choice != null) {
                returnDataset(AutofillDatasetFactory.dataset(this, form, choice.item));
            }
        });
        EditText search = findViewById(R.id.autofillCredentialSearch);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                adapter.getFilter().filter(value);
            }

            @Override
            public void afterTextChanged(android.text.Editable value) {
            }
        });
    }

    private void returnDataset(Dataset dataset) {
        Intent result = new Intent();
        result.putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                dataset);
        setResult(RESULT_OK, result);
        finishCompleted();
    }

    private void showMessage(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show();
    }

    private void finishCanceled() {
        if (!finished) {
            finished = true;
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void finishCompleted() {
        if (!finished) {
            finished = true;
            finish();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pickerVisible && !finished) {
            finishCanceled();
        }
    }

    @Override
    protected void onDestroy() {
        if (repository != null) {
            repository.clearInMemorySecrets();
        }
        super.onDestroy();
    }

    private static final class CredentialChoice {
        final VaultItem item;
        final String label;

        CredentialChoice(VaultItem item, String unnamedLabel) {
            this.item = item;
            String title = item.title.trim().isEmpty()
                    ? item.username : item.title.trim();
            if (title.isEmpty()) {
                title = unnamedLabel;
            }
            this.label = item.username.isEmpty() || item.username.equals(title)
                    ? title : title + " — " + item.username;
        }

        @Override
        public String toString() {
            return label;
        }
    }

}
