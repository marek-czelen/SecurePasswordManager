package com.cretorix.securepasswordmanager;

import android.content.Context;

/**
 * Provides the current ad-removal entitlement.
 *
 * <p>The debug override exists only to test ad display/removal. It is guarded by
 * {@link BuildConfig#DEBUG}, so a release build cannot expose a local activation switch.
 * Ad-removal state never participates in vault cryptography.</p>
 */
public final class PremiumManager {
    private static final String PREFS = "premium_debug";
    private static final String PREF_DEBUG_AD_FREE = "debug_ad_free";
    private static final String ENTITLEMENT_PREFS = "premium_entitlements";
    private static final String PREF_PLAY_AD_FREE = "play_pro";

    private final Context context;

    public PremiumManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns the Play ad-removal entitlement or the debug override. */
    public boolean isAdFree() {
        return isPlayAdFree() || (BuildConfig.DEBUG
                && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG_AD_FREE, false));
    }

    /** Returns the locally cached Google Play ad-removal entitlement. */
    public boolean isPlayAdFree() {
        return context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_PLAY_AD_FREE, false);
    }

    /** Returns only the debug override, not the combined entitlement. */
    public boolean isDebugAdFree() {
        return BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG_AD_FREE, false);
    }

    /** Updates the cache only after a successful Google Play purchase query or acknowledgement. */
    void setPlayAdFree(boolean enabled) {
        context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_PLAY_AD_FREE, enabled).apply();
    }

    /** Changes the debug entitlement; production code cannot call this successfully. */
    public void setDebugAdFree(boolean enabled) {
        if (!BuildConfig.DEBUG) {
            throw new IllegalStateException("Debug ad-removal override is disabled in release");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_DEBUG_AD_FREE, enabled).apply();
    }

    /** The switch is intentionally hidden outside debug builds. */
    public boolean isDebugOverrideAvailable() {
        return BuildConfig.DEBUG;
    }
}
