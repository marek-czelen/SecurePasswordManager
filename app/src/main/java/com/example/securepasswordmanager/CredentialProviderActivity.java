package com.example.securepasswordmanager;

import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPasswordOption;
import androidx.credentials.PasswordCredential;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.credentials.provider.BeginGetCredentialRequest;
import androidx.credentials.provider.BeginGetCredentialResponse;
import androidx.credentials.provider.BeginGetPasswordOption;
import androidx.credentials.provider.PendingIntentHandler;
import androidx.credentials.provider.ProviderGetCredentialRequest;

import android.hardware.biometrics.BiometricPrompt;

import com.example.securepasswordmanager.crypto.VaultData;
import com.example.securepasswordmanager.crypto.VaultItem;
import com.example.securepasswordmanager.repository.VaultV3Repository;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;

public final class CredentialProviderActivity extends AppCompatActivity {
    private BeginGetCredentialRequest beginGetRequest;
    private ProviderGetCredentialRequest getRequest;
    private VaultV3Repository repository;
    private String sessionToken;
    private String entryId;
    private boolean pickerVisible;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_autofill_auth);

        String mode = getIntent().getStringExtra(CredentialProviderResponseFactory.EXTRA_MODE);
        if (CredentialProviderResponseFactory.MODE_UNLOCK.equals(mode)) {
            beginGetRequest = PendingIntentHandler.retrieveBeginGetCredentialRequest(getIntent());
            if (beginGetRequest == null) {
                finishCanceled();
            } else {
                authenticate();
            }
            return;
        }
        if (CredentialProviderResponseFactory.MODE_GET.equals(mode)) {
            beginGetSelection();
            return;
        }
        finishCanceled();
    }

    private void beginGetSelection() {
        getRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(getIntent());
        sessionToken = getIntent().getStringExtra(
                CredentialProviderResponseFactory.EXTRA_SESSION_TOKEN);
        entryId = getIntent().getStringExtra(CredentialProviderResponseFactory.EXTRA_ENTRY_ID);
        if (getRequest == null || sessionToken == null || entryId == null) {
            returnGetException(new NoCredentialException());
            return;
        }
        VaultItem cached = CredentialProviderSession.get(sessionToken, entryId);
        if (cached != null && isAllowedByRequest(cached, getRequest)) {
            returnCredential(cached);
            return;
        }
        if (Session.isActive() && !Session.isRecoveredPrevious()) {
            returnCredential(findRequestedItem(Session.data()));
            return;
        }
        authenticate();
    }

    private void authenticate() {
        try {
            repository = new VaultV3Repository(this);
            if (!repository.exists()) {
                showMessage(R.string.autofill_vault_unavailable);
                finishCanceled();
                return;
            }
            Cipher cipher = repository.prepareBiometricCipher();
            Executor executor = this::runOnUiThread;
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle(getString(R.string.autofill_unlock_title))
                    .setSubtitle(getString(R.string.autofill_unlock_subtitle))
                    .setNegativeButton(getString(R.string.action_cancel), executor,
                            (dialog, which) -> finishCanceled())
                    .build();
            prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),
                    new CancellationSignal(), executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            if (result.getCryptoObject() == null
                                    || result.getCryptoObject().getCipher() == null) {
                                finishCanceled();
                                return;
                            }
                            openVault(result.getCryptoObject().getCipher());
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

    private void openVault(Cipher authenticatedCipher) {
        try {
            VaultV3Repository.OpenResult opened =
                    repository.openWithBiometric(authenticatedCipher);
            try {
                if (opened.recoveredPrevious) {
                    showMessage(R.string.autofill_recovery_required);
                    finishCanceled();
                } else if (beginGetRequest != null) {
                    handleUnlockedBeginRequest(opened.data);
                } else {
                    returnCredential(findRequestedItem(opened.data));
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

    private void handleUnlockedBeginRequest(VaultData data) {
        List<BeginGetPasswordOption> options =
                CredentialProviderResponseFactory.passwordOptions(beginGetRequest);
        String origin = AutofillOrigin.verifiedCredentialOrigin(this,
                beginGetRequest.getCallingAppInfo());
        List<VaultItem> matches = CredentialProviderResponseFactory.matchingItems(
                AutofillMatcher.matchingLogins(data.items, origin), options);
        if (matches.isEmpty()) {
            finishCanceled();
            return;
        }
        if (origin != null
                && matches.size() <= CredentialProviderResponseFactory.MAX_PROVIDER_ENTRIES) {
            returnBeginResponse(CredentialProviderResponseFactory.credentialsResponse(
                    this, matches, options));
            return;
        }
        showCredentialPicker(matches, origin);
    }

    private void showCredentialPicker(List<VaultItem> matches, String origin) {
        pickerVisible = true;
        setContentView(R.layout.activity_autofill_picker);
        TextView subtitle = findViewById(R.id.autofillPickerSubtitle);
        String packageName = beginGetRequest == null
                || beginGetRequest.getCallingAppInfo() == null ? null
                : beginGetRequest.getCallingAppInfo().getPackageName();
        if (origin != null && packageName != null) {
            subtitle.setText(getString(R.string.autofill_context_host_app,
                    origin, packageName));
        } else if (origin != null) {
            subtitle.setText(origin);
        } else if (packageName != null) {
            subtitle.setText(getString(R.string.autofill_context_app_only, packageName));
        } else {
            subtitle.setText(R.string.autofill_choose_login);
        }

        List<CredentialChoice> choices = new ArrayList<>(matches.size());
        String unnamedLabel = getString(R.string.autofill_unnamed_credential);
        for (VaultItem item : matches) {
            choices.add(new CredentialChoice(item, unnamedLabel));
        }
        ArrayAdapter<CredentialChoice> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, choices);
        ListView list = findViewById(R.id.autofillCredentialList);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            CredentialChoice choice = adapter.getItem(position);
            if (choice != null) {
                returnBeginResponse(CredentialProviderResponseFactory.credentialsResponse(
                        this, Collections.singletonList(choice.item),
                        CredentialProviderResponseFactory.passwordOptions(beginGetRequest)));
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

    private VaultItem findRequestedItem(VaultData data) {
        if (data == null || getRequest == null) {
            return null;
        }
        for (VaultItem item : AutofillMatcher.matchingLogins(data.items,
                verifiedOrigin(getRequest))) {
            if (entryId.equals(item.entryId) && isAllowedByRequest(item, getRequest)) {
                return item;
            }
        }
        return null;
    }

    private String verifiedOrigin(ProviderGetCredentialRequest request) {
        return AutofillOrigin.verifiedCredentialOrigin(this, request.getCallingAppInfo());
    }

    private boolean isAllowedByRequest(VaultItem item, ProviderGetCredentialRequest request) {
        String origin = verifiedOrigin(request);
        if (origin != null
                && AutofillMatcher.matchingLogins(Collections.singletonList(item), origin)
                .isEmpty()) {
            return false;
        }
        for (androidx.credentials.CredentialOption option : request.getCredentialOptions()) {
            if (option instanceof GetPasswordOption) {
                Set<String> allowedIds = ((GetPasswordOption) option).getAllowedUserIds();
                if (allowedIds.isEmpty() || allowedIds.contains(item.username)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void returnCredential(VaultItem item) {
        CredentialProviderSession.forget(sessionToken);
        if (item == null || !isAllowedByRequest(item, getRequest)) {
            returnGetException(new NoCredentialException());
            return;
        }
        Intent result = new Intent();
        PendingIntentHandler.setGetCredentialResponse(result,
                new GetCredentialResponse(new PasswordCredential(item.username, item.password)));
        setResult(RESULT_OK, result);
        finishCompleted();
    }

    private void returnGetException(NoCredentialException exception) {
        CredentialProviderSession.forget(sessionToken);
        Intent result = new Intent();
        PendingIntentHandler.setGetCredentialException(result, exception);
        setResult(RESULT_OK, result);
        finishCompleted();
    }

    private void returnBeginResponse(BeginGetCredentialResponse response) {
        if (response == null) {
            finishCanceled();
            return;
        }
        Intent result = new Intent();
        PendingIntentHandler.setBeginGetCredentialResponse(result, response);
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
