package com.example.securepasswordmanager;

import android.content.Context;

/**
 * Provides the current product entitlement.
 *
 * <p>The debug override exists only to test Free/Pro UI before Google Play Billing is
 * connected. It is guarded by {@link BuildConfig#DEBUG}, so a release build cannot expose
 * a local activation switch. Premium state never participates in vault cryptography.</p>
 */
public final class PremiumManager {
    private static final String PREFS = "premium_debug";
    private static final String PREF_DEBUG_PRO = "debug_pro";

    private final Context context;

    public PremiumManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns the current entitlement; release builds are Free until Billing is added. */
    public boolean isPro() {
        return BuildConfig.DEBUG && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG_PRO, false);
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
