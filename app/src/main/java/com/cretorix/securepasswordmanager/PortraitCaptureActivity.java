package com.cretorix.securepasswordmanager;

import android.os.Bundle;

import androidx.core.view.WindowCompat;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * ZXing activity locked to portrait. Using an explicit capture activity avoids the
 * library's default landscape transition on devices whose camera provider reports a
 * landscape sensor orientation.
 */
public final class PortraitCaptureActivity extends CaptureActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected DecoratedBarcodeView initializeContent() {
        DecoratedBarcodeView scanner = super.initializeContent();
        EdgeToEdgeInsets.apply(scanner);
        return scanner;
    }
}
