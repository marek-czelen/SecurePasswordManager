package com.cretorix.securepasswordmanager;

import android.app.PendingIntent;
import android.content.Context;
import android.widget.RemoteViews;
import android.view.autofill.AutofillValue;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

import java.util.List;

final class AutofillDatasetFactory {
    static final int MAX_SYSTEM_DATASETS = 50;

    private AutofillDatasetFactory() {
    }

    static FillResponse response(Context context, AutofillForm form, List<VaultItem> items) {
        if (items.isEmpty() || items.size() > MAX_SYSTEM_DATASETS) {
            return null;
        }
        FillResponse.Builder response = new FillResponse.Builder();
        for (VaultItem item : items) {
            response.addDataset(dataset(context, form, item));
        }
        return response.build();
    }

    static Dataset dataset(Context context, AutofillForm form, VaultItem item) {
        String label = item.title.trim().isEmpty()
                ? item.username : item.title.trim();
        if (label.isEmpty()) {
            label = context.getString(R.string.autofill_unnamed_credential);
        }
        if (!item.username.isEmpty() && !item.username.equals(label)) {
            label = context.getString(R.string.autofill_credential_label, label, item.username);
        }
        RemoteViews presentation = presentation(context, label);
        Dataset.Builder dataset = new Dataset.Builder(presentation);
        if (form.usernameId != null && !item.username.isEmpty()) {
            dataset.setValue(form.usernameId, AutofillValue.forText(item.username), presentation);
        }
        dataset.setValue(form.passwordId, AutofillValue.forText(item.password), presentation);
        return dataset.build();
    }

    static FillResponse authenticationResponse(Context context, AutofillForm form,
                                               PendingIntent authentication) {
        RemoteViews presentation = presentation(context,
                context.getString(R.string.autofill_unlock));
        Dataset dataset = new Dataset.Builder(presentation)
                .setValue(form.passwordId, null, presentation)
                .setAuthentication(authentication.getIntentSender())
                .build();
        return new FillResponse.Builder().addDataset(dataset).build();
    }

    private static RemoteViews presentation(Context context, String label) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                android.R.layout.simple_list_item_1);
        views.setTextViewText(android.R.id.text1, label);
        return views;
    }
}
