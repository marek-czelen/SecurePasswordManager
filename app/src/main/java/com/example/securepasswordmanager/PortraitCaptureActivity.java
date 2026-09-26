package com.example.securepasswordmanager;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * ZXing activity locked to portrait. Using an explicit capture activity avoids the
 * library's default landscape transition on devices whose camera provider reports a
 * landscape sensor orientation.
 */
public final class PortraitCaptureActivity extends CaptureActivity {
}
