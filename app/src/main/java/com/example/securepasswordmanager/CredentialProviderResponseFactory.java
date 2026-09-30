package com.example.securepasswordmanager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.credentials.provider.AuthenticationAction;
import androidx.credentials.provider.BeginGetCredentialRequest;
import androidx.credentials.provider.BeginGetPasswordOption;
import androidx.credentials.provider.BeginGetCredentialResponse;
import androidx.credentials.provider.PasswordCredentialEntry;

import com.example.securepasswordmanager.crypto.VaultItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class CredentialProviderResponseFactory {
    static final int MAX_PROVIDER_ENTRIES = 50;
    static final String EXTRA_MODE = "credential_provider_mode";
    static final String EXTRA_SESSION_TOKEN = "credential_provider_session";
    static final String EXTRA_ENTRY_ID = "credential_provider_entry_id";
    static final String MODE_UNLOCK = "unlock";
    static final String MODE_GET = "get";

    private static final AtomicInteger REQUEST_CODES = new AtomicInteger();

    private CredentialProviderResponseFactory() {
    }

    static List<BeginGetPasswordOption> passwordOptions(BeginGetCredentialRequest request) {
        List<BeginGetPasswordOption> options = new ArrayList<>();
        for (androidx.credentials.provider.BeginGetCredentialOption option
                : request.getBeginGetCredentialOptions()) {
            if (option instanceof BeginGetPasswordOption) {
                options.add((BeginGetPasswordOption) option);
            }
        }
        return options;
    }

    static List<VaultItem> matchingItems(List<VaultItem> items,
                                         List<BeginGetPasswordOption> options) {
        List<VaultItem> matches = new ArrayList<>();
        for (VaultItem item : items) {
            if (!isUsable(item)) {
                continue;
            }
            for (BeginGetPasswordOption option : options) {
                Set<String> allowedIds = option.getAllowedUserIds();
                if (allowedIds.isEmpty() || allowedIds.contains(item.username)) {
                    matches.add(item);
                    break;
                }
            }
        }
        return matches;
    }

    static BeginGetCredentialResponse authenticationResponse(Context context) {
        String token = java.util.UUID.randomUUID().toString();
        Intent intent = new Intent(context, CredentialProviderActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_UNLOCK);
        intent.setData(Uri.parse("securepasswordmanager://credential/unlock/" + token));
        PendingIntent pendingIntent = PendingIntent.getActivity(context,
                REQUEST_CODES.getAndIncrement(), intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AuthenticationAction authenticationAction = new AuthenticationAction.Builder(
                context.getString(R.string.autofill_unlock), pendingIntent).build();
        return new BeginGetCredentialResponse.Builder()
                .addAuthenticationAction(authenticationAction)
                .build();
    }

    static BeginGetCredentialResponse credentialsResponse(Context context,
                                                           List<VaultItem> items,
                                                           List<BeginGetPasswordOption> options) {
        if (items.isEmpty() || items.size() > MAX_PROVIDER_ENTRIES) {
            return null;
        }
        List<VaultItem> selected = matchingItems(items, options);
        if (selected.isEmpty()) {
            return null;
        }
        String token = CredentialProviderSession.remember(selected);
        if (token == null) {
            return null;
        }

        BeginGetCredentialResponse.Builder response =
                new BeginGetCredentialResponse.Builder();
        for (VaultItem item : selected) {
            BeginGetPasswordOption option = matchingOption(item, options);
            if (option == null) {
                continue;
            }
            Intent intent = new Intent(context, CredentialProviderActivity.class);
            intent.putExtra(EXTRA_MODE, MODE_GET);
            intent.putExtra(EXTRA_SESSION_TOKEN, token);
            intent.putExtra(EXTRA_ENTRY_ID, item.entryId);
            intent.setData(Uri.parse("securepasswordmanager://credential/get/"
                    + token + "/" + item.entryId));
            PendingIntent pendingIntent = PendingIntent.getActivity(context,
                    REQUEST_CODES.getAndIncrement(), intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String displayName = item.title.trim().isEmpty()
                    ? item.username : item.title.trim();
            response.addCredentialEntry(new PasswordCredentialEntry.Builder(
                    context, item.username, pendingIntent, option)
                    .setDisplayName(displayName)
                    .setAutoSelectAllowed(false)
                    .build());
        }
        BeginGetCredentialResponse result = response.build();
        if (result.getCredentialEntries().isEmpty()) {
            CredentialProviderSession.forget(token);
            return null;
        }
        return result;
    }

    private static BeginGetPasswordOption matchingOption(
            VaultItem item, List<BeginGetPasswordOption> options) {
        for (BeginGetPasswordOption option : options) {
            Set<String> allowedIds = option.getAllowedUserIds();
            if (allowedIds.isEmpty() || allowedIds.contains(item.username)) {
                return option;
            }
        }
        return null;
    }

    private static boolean isUsable(VaultItem item) {
        return VaultItem.TYPE_LOGIN.equals(item.type)
                && !item.username.isEmpty()
                && !item.password.isEmpty();
    }
}
