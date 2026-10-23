package com.cretorix.securepasswordmanager;

import android.content.Context;

/**
 * Provides the current product entitlement.
 *
 * <p>The debug override exists only to test Free/Pro UI. It is guarded by
 * {@link BuildConfig#DEBUG}, so a release build cannot expose a local activation switch.
 * Premium state never participates in vault cryptography.</p>
 */
public final class PremiumManager {
    private static final String PREFS = "premium_debug";
    private static final String PREF_DEBUG_PRO = "debug_pro";
    private static final String ENTITLEMENT_PREFS = "premium_entitlements";
    private static final String PREF_PLAY_PRO = "play_pro";

    private final Context context;

    public PremiumManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns the Play entitlement, retained locally for offline access, or the debug override. */
    public boolean isPro() {
        return isPlayPro() || (BuildConfig.DEBUG
                && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG_PRO, false));
    }

    /** Returns the locally cached Google Play entitlement. */
    public boolean isPlayPro() {
        return context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_PLAY_PRO, false);
    }

    /** Returns only the debug override, not the combined entitlement. */
    public boolean isDebugPro() {
        return BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG_PRO, false);
    }

    /** Updates the cache only after a successful Google Play purchase query or acknowledgement. */
    void setPlayPro(boolean enabled) {
        context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_PLAY_PRO, enabled).apply();
    }

    /** Changes the debug entitlement; production code cannot call this successfully. */
    public void setDebugPro(boolean enabled) {
        if (!BuildConfig.DEBUG) {
            throw new IllegalStateException("Debug premium override is disabled in release");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_DEBUG_PRO, enabled).apply();
    }

    /** The switch is intentionally hidden outside debug builds. */
    public boolean isDebugOverrideAvailable() {
        return BuildConfig.DEBUG;
    }
}
