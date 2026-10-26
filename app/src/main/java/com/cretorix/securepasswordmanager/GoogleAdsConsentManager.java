package com.cretorix.securepasswordmanager;

import android.app.Activity;
import android.content.Context;

import com.google.android.gms.ads.MobileAds;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;

import java.util.ArrayList;
import java.util.List;

final class GoogleAdsConsentManager {
    interface ConsentCallback {
        void onComplete(boolean canRequestAds, FormError error);
    }

    private static final Object INITIALIZATION_LOCK = new Object();
    private static final List<Runnable> INITIALIZATION_CALLBACKS = new ArrayList<>();
    private static boolean adsInitializing;
    private static volatile boolean adsInitialized;

    private final ConsentInformation consentInformation;

    GoogleAdsConsentManager(Context context) {
        consentInformation = UserMessagingPlatform.getConsentInformation(
                context.getApplicationContext());
    }

    void gatherConsent(Activity activity, ConsentCallback callback) {
        ConsentRequestParameters parameters =
                new ConsentRequestParameters.Builder().build();
        consentInformation.requestConsentInfoUpdate(activity, parameters,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
                        error -> callback.onComplete(
                                consentInformation.canRequestAds(), error)),
                error -> callback.onComplete(consentInformation.canRequestAds(), error));
    }

    boolean canRequestAds() {
        return consentInformation.canRequestAds();
    }

    boolean isPrivacyOptionsRequired() {
        return consentInformation.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    void showPrivacyOptions(Activity activity, ConsentCallback callback) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity,
                error -> callback.onComplete(consentInformation.canRequestAds(), error));
    }

    static void initializeAdsIfNeeded(Context context, Runnable callback) {
        boolean initialize = false;
        boolean runCallback = false;
        synchronized (INITIALIZATION_LOCK) {
            if (adsInitialized) {
                runCallback = true;
            } else {
                INITIALIZATION_CALLBACKS.add(callback);
                if (!adsInitializing) {
                    adsInitializing = true;
                    initialize = true;
                }
            }
        }
        if (runCallback) {
            callback.run();
            return;
        }
        if (!initialize) {
            return;
        }
        MobileAds.initialize(context.getApplicationContext(), status -> {
            List<Runnable> callbacks;
            synchronized (INITIALIZATION_LOCK) {
                adsInitialized = true;
                adsInitializing = false;
                callbacks = new ArrayList<>(INITIALIZATION_CALLBACKS);
                INITIALIZATION_CALLBACKS.clear();
            }
            for (Runnable pending : callbacks) {
                pending.run();
            }
        });
    }
}
