package com.example.securepasswordmanager;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/** Enables Material You dynamic colors app-wide when the device supports them. */
public final class SecureApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
