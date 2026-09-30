package com.example.securepasswordmanager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.credentials.provider.CallingAppInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class AutofillOrigin {
    private static final String TAG = "SPM_Credential";

    private AutofillOrigin() {
    }

    static boolean isDefaultBrowser(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://example.com/"));
        browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo resolved = context.getPackageManager().resolveActivity(
                browserIntent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && packageName.equals(resolved.activityInfo.packageName);
    }

    static String verifiedCredentialOrigin(Context context, CallingAppInfo callingAppInfo) {
        if (callingAppInfo == null
                || !isDefaultBrowser(context, callingAppInfo.getPackageName())) {
            return null;
        }
        try {
            String allowlist = browserAllowlist(callingAppInfo);
            return allowlist == null ? null : callingAppInfo.getOrigin(allowlist);
        } catch (JSONException | NoSuchAlgorithmException exception) {
            Log.w(TAG, "Could not verify browser origin", exception);
            return null;
        }
    }

    private static String browserAllowlist(CallingAppInfo callingAppInfo)
            throws JSONException, NoSuchAlgorithmException {
        if (callingAppInfo.getSigningInfo() == null) {
            return null;
        }
        Signature[] signers = callingAppInfo.getSigningInfo().getApkContentsSigners();
        if (signers == null || signers.length == 0) {
            return null;
        }
        String build = "userdebug".equals(Build.TYPE) ? "userdebug" : "release";
        JSONArray signatures = new JSONArray();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Signature signer : signers) {
            byte[] fingerprint = digest.digest(signer.toByteArray());
            StringBuilder encodedFingerprint = new StringBuilder(fingerprint.length * 3 - 1);
            for (int index = 0; index < fingerprint.length; index++) {
                if (index > 0) {
                    encodedFingerprint.append(':');
                }
                encodedFingerprint.append(String.format(Locale.ROOT, "%02X",
                        fingerprint[index] & 0xff));
            }
            JSONObject signature = new JSONObject()
                    .put("build", build)
                    .put("cert_fingerprint_sha256", encodedFingerprint.toString());
            signatures.put(signature);
        }
        JSONObject appInfo = new JSONObject()
                .put("package_name", callingAppInfo.getPackageName())
                .put("signatures", signatures);
        JSONObject app = new JSONObject()
                .put("type", "android")
                .put("info", appInfo);
        return new JSONObject().put("apps", new JSONArray().put(app)).toString();
    }
}
