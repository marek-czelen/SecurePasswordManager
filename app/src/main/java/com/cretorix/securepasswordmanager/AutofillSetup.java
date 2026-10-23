package com.cretorix.securepasswordmanager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.autofill.AutofillManager;

final class AutofillSetup {
    private static final String PREFS = "autofill_setup";
    private static final String PROMPT_SHOWN = "setup_prompt_shown";
    private static final String CREDENTIAL_PROVIDER_PROMPT_SHOWN =
            "credential_provider_prompt_shown";

    private AutofillSetup() {
    }

    static boolean isEnabled(Context context) {
        AutofillManager autofillManager = context.getSystemService(AutofillManager.class);
        return autofillManager != null && autofillManager.hasEnabledAutofillServices();
    }

    static boolean claimFirstPrompt(Context context) {
        boolean credentialProviderAvailable =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        if (!credentialProviderAvailable && isEnabled(context)) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String promptKey = credentialProviderAvailable
                ? CREDENTIAL_PROVIDER_PROMPT_SHOWN : PROMPT_SHOWN;
        if (preferences.getBoolean(promptKey, false)) {
            return false;
        }
        preferences.edit().putBoolean(promptKey, true).apply();
        return true;
    }

    static boolean supportsCredentialProvider(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    static Intent settingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Intent credentialProvider = new Intent(Settings.ACTION_CREDENTIAL_PROVIDER);
            if (credentialProvider.resolveActivity(context.getPackageManager()) != null) {
                return credentialProvider;
            }
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            return intent;
        }
        return new Intent(Settings.ACTION_SETTINGS);
    }
}
