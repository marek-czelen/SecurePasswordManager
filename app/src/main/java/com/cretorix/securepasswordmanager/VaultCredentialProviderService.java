package com.cretorix.securepasswordmanager;

import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.CreateCredentialException;
import androidx.credentials.exceptions.CreateCredentialUnknownException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialUnknownException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.credentials.provider.BeginCreateCredentialRequest;
import androidx.credentials.provider.BeginCreateCredentialResponse;
import androidx.credentials.provider.BeginGetCredentialRequest;
import androidx.credentials.provider.BeginGetCredentialResponse;
import androidx.credentials.provider.CredentialProviderService;
import androidx.credentials.provider.ProviderClearCredentialStateRequest;
import androidx.annotation.RequiresApi;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

import java.util.List;

@RequiresApi(34)
public final class VaultCredentialProviderService extends CredentialProviderService {
    @Override
    public void onBeginGetCredentialRequest(BeginGetCredentialRequest request,
                                            CancellationSignal cancellationSignal,
                                            OutcomeReceiver<BeginGetCredentialResponse,
                                                    GetCredentialException> callback) {
        if (cancellationSignal.isCanceled()) {
            return;
        }
        try {
            List<androidx.credentials.provider.BeginGetPasswordOption> options =
                    CredentialProviderResponseFactory.passwordOptions(request);
            if (options.isEmpty()) {
                callback.onResult(new BeginGetCredentialResponse.Builder().build());
                return;
            }
            if (Session.isActive() && !Session.isRecoveredPrevious()) {
                String origin = AutofillOrigin.verifiedCredentialOrigin(this,
                        request.getCallingAppInfo());
                List<VaultItem> matches = AutofillMatcher.matchingLogins(
                        Session.data().items, origin);
                matches = CredentialProviderResponseFactory.matchingItems(matches, options);
                if (matches.isEmpty()) {
                    callback.onError(new NoCredentialException());
                    return;
                }
                if (origin != null
                        && matches.size()
                        <= CredentialProviderResponseFactory.MAX_PROVIDER_ENTRIES) {
                    BeginGetCredentialResponse response =
                            CredentialProviderResponseFactory.credentialsResponse(
                                    this, matches, options);
                    if (response != null) {
                        callback.onResult(response);
                        return;
                    }
                }
            }
            callback.onResult(CredentialProviderResponseFactory.authenticationResponse(this));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            android.util.Log.e("SPM_Credential",
                    "Could not process Credential Manager request", exception);
            callback.onError(new GetCredentialUnknownException(
                    getString(R.string.autofill_request_failed)));
        }
    }

    @Override
    public void onBeginCreateCredentialRequest(BeginCreateCredentialRequest request,
                                               CancellationSignal cancellationSignal,
                                               OutcomeReceiver<BeginCreateCredentialResponse,
                                                       CreateCredentialException> callback) {
        if (cancellationSignal.isCanceled()) {
            return;
        }
        callback.onError(new CreateCredentialUnknownException(
                getString(R.string.credential_save_not_supported)));
    }

    @Override
    public void onClearCredentialStateRequest(ProviderClearCredentialStateRequest request,
                                              CancellationSignal cancellationSignal,
                                              OutcomeReceiver<Void, ClearCredentialException> callback) {
        if (cancellationSignal.isCanceled()) {
            return;
        }
        CredentialProviderSession.clearAll();
        callback.onResult(null);
    }
}
