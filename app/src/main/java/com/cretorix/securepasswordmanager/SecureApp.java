package com.cretorix.securepasswordmanager;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/** Enables Material You dynamic colors app-wide when the device supports them. */
/** Application object responsible for global Material You setup only. */
public final class SecureApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
