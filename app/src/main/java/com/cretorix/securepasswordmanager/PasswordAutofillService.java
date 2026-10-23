package com.cretorix.securepasswordmanager;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.util.Log;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class PasswordAutofillService extends AutofillService {
    private static final String TAG = "SPM_Autofill";
    private static final AtomicInteger REQUEST_CODES = new AtomicInteger();

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal,
                              FillCallback callback) {
        if (cancellationSignal.isCanceled()) {
            callback.onSuccess(null);
            return;
        }
        try {
            AutofillForm form = AutofillForm.from(request);
            if (form == null) {
                callback.onSuccess(null);
                return;
            }
            if (getPackageName().equals(form.targetPackage)) {
                callback.onSuccess(null);
                return;
            }
            if (!AutofillOrigin.isDefaultBrowser(this, form.targetPackage)) {
                form = form.requiringPicker();
            }
            if (form.webDomain != null && !form.webDomain.trim().isEmpty()
                    && AutofillMatcher.hostFromUrl(form.webDomain) == null) {
                callback.onSuccess(null);
                return;
            }
            if (Session.isActive()) {
                if (Session.isRecoveredPrevious()) {
                    callback.onFailure(getString(R.string.autofill_recovery_required));
                    return;
                }
                List<VaultItem> matches = AutofillMatcher.matchingLogins(
                        Session.data().items, form.webDomain);
                if (matches.isEmpty()) {
                    callback.onSuccess(null);
                    return;
                }
                if (!form.requirePicker
                        && AutofillMatcher.hostFromUrl(form.webDomain) != null
                        && matches.size() <= AutofillDatasetFactory.MAX_SYSTEM_DATASETS) {
                    callback.onSuccess(AutofillDatasetFactory.response(this, form, matches));
                    return;
                }
            }
            callback.onSuccess(authenticationResponse(form));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            Log.e(TAG, "Could not process autofill request", exception);
            callback.onFailure(getString(R.string.autofill_request_failed));
        }
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onFailure(getString(R.string.autofill_save_unavailable));
    }

    private FillResponse authenticationResponse(AutofillForm form) {
        Intent intent = new Intent(this, AutofillAuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        form.addToIntent(intent);
        PendingIntent authentication = PendingIntent.getActivity(this,
                REQUEST_CODES.getAndIncrement(), intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
        return AutofillDatasetFactory.authenticationResponse(this, form, authentication);
    }
}
