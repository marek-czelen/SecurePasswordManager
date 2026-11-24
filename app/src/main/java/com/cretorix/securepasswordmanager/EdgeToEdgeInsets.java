package com.cretorix.securepasswordmanager;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public final class EdgeToEdgeInsets {
    private EdgeToEdgeInsets() {
    }

    public static void apply(Activity activity, View target) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        apply(target);
    }

    public static void apply(View target) {
        int left = target.getPaddingLeft();
        int top = target.getPaddingTop();
        int right = target.getPaddingRight();
        int bottom = target.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(target, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(target);
    }
}
