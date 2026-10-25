package com.cretorix.securepasswordmanager;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.credentials.provider.CallingAppInfo;

import com.cretorix.securepasswordmanager.crypto.VaultData;
import com.cretorix.securepasswordmanager.crypto.VaultItem;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class AutofillEntryFlow {
    private static final String TAG = "SPM_Autofill";
    private static String pendingToken;
    private static NewEntry pendingEntry;

    private AutofillEntryFlow() {
    }

    static void showPrompt(Activity activity, String label, Runnable onAdd, Runnable onCancel) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.autofill_add_entry_title)
                .setMessage(activity.getString(R.string.autofill_add_entry_message, label))
                .setNegativeButton(R.string.autofill_add_entry_no,
                        (dialog, which) -> onCancel.run())
                .setPositiveButton(R.string.autofill_add_entry_yes,
                        (dialog, which) -> onAdd.run())
                .setOnCancelListener(dialog -> onCancel.run())
                .show();
    }

    static NewEntry newEntry(Context context, String webDomain, String appPackageName) {
        String host = AutofillMatcher.hostFromUrl(webDomain);
        if (host != null) {
            return new NewEntry(host, "https://" + host, "");
        }
        if (webDomain != null && !webDomain.trim().isEmpty()) {
            return null;
        }
        String nativePackage = nativeAppPackage(context, appPackageName);
        if (nativePackage == null) {
            return null;
        }
        return new NewEntry(applicationLabel(context, nativePackage), "", nativePackage);
    }

    static String nativeAppPackage(Context context, String appPackageName) {
        if (!VaultItem.isValidPackageName(appPackageName)
                || AutofillOrigin.isDefaultBrowser(context, appPackageName)) {
            return null;
        }
        return appPackageName;
    }

    static String credentialAppPackage(Context context, CallingAppInfo callingAppInfo) {
        return callingAppInfo == null ? null
                : nativeAppPackage(context, callingAppInfo.getPackageName());
    }

    static void launchNewEntry(Activity activity, NewEntry target, VaultData data,
                               byte[] dek, byte[] recoverySecret) {
        if (target == null) {
            throw new IllegalArgumentException("Autofill target is not available");
        }
        if (!Session.isActive() && data != null && dek != null) {
            Session.set(null, data, true, dek);
            Session.setRecoveredPrevious(false);
            Session.setRecoverySecret(recoverySecret);
        }
        Intent intent = new Intent(activity, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_AUTOFILL_CREATE_TOKEN,
                storePendingEntry(target));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
    }

    static String targetLabel(Context context, String webDomain, String appPackageName) {
        NewEntry target = newEntry(context, webDomain, appPackageName);
        if (target == null) {
            throw new IllegalArgumentException("Autofill target is not available");
        }
        return target.title;
    }

    static synchronized NewEntry consumePendingEntry(String token) {
        if (token == null || !token.equals(pendingToken)) {
            return null;
        }
        NewEntry entry = pendingEntry;
        pendingToken = null;
        pendingEntry = null;
        return entry;
    }

    private static synchronized String storePendingEntry(NewEntry entry) {
        pendingToken = java.util.UUID.randomUUID().toString();
        pendingEntry = entry;
        return pendingToken;
    }

    private static String applicationLabel(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = context.getPackageManager().getApplicationLabel(info);
            return label == null || label.toString().trim().isEmpty()
                    ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException exception) {
            Log.w(TAG, "Could not resolve autofill target app label", exception);
            return packageName;
        }
    }

    static final class NewEntry {
        final String title;
        final String url;
        final String appPackageName;

        NewEntry(String title, String url, String appPackageName) {
            this.title = title;
            this.url = url;
            this.appPackageName = appPackageName;
        }
    }
}
