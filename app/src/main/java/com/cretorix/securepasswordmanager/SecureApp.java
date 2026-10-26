package com.cretorix.securepasswordmanager;

import android.app.Application;

/**
 * Application object. The app intentionally uses its own fixed brand palette (light and dark)
 * instead of wallpaper-based dynamic colors, so the UI looks consistent on every device.
 */
public final class SecureApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
    }
}