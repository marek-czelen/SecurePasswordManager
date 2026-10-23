package com.cretorix.securepasswordmanager;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFS = "app_settings";
    private static final String AUTOLOCK_MIN = "autolock_min";
    private static final String AUTOLOCK_ENABLED = "autolock_enabled";
    private static final String LOCK_BACKGROUND = "lock_background";
    private static final int DEFAULT_AUTOLOCK_MIN = 5;

    private AppSettings() { }

    static int autoLockMinutes(Context context) {
        return isAutoLockEnabled(context) ? configuredAutoLockMinutes(context) : 0;
    }

    static int configuredAutoLockMinutes(Context context) {
        return preferences(context).getInt(AUTOLOCK_MIN, DEFAULT_AUTOLOCK_MIN);
    }

    static boolean isAutoLockEnabled(Context context) {
        return preferences(context).getBoolean(AUTOLOCK_ENABLED, true);
    }

    static void setAutoLockEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(AUTOLOCK_ENABLED, enabled).apply();
    }

    static void setAutoLockMinutes(Context context, int minutes) {
        preferences(context).edit()
                .putInt(AUTOLOCK_MIN, minutes)
                .putBoolean(AUTOLOCK_ENABLED, true)
                .apply();
    }

    static boolean lockOnBackground(Context context) {
        return preferences(context).getBoolean(LOCK_BACKGROUND, true);
    }

    static void setLockOnBackground(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(LOCK_BACKGROUND, enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
