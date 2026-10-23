package com.cretorix.securepasswordmanager;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Crops one user-selected image region and returns a temporary JPEG URI. */
public final class ImageCropActivity extends AppCompatActivity {
    private static final String IMAGE_LOG = "SPM_IMAGE_FLOW";
    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_CROPPED_URI = "cropped_uri";

    private Bitmap bitmap;
    private SelectionOverlayView overlay;
    private Uri sourceUri;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(IMAGE_LOG, "ImageCropActivity.onCreate");
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_image_crop);
        View root = findViewById(R.id.imageCropRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        sourceUri = getIntent().getParcelableExtra(EXTRA_IMAGE_URI);
        if (sourceUri == null) {
            finishCancelled();
            return;
        }
        bitmap = load(sourceUri);
        if (bitmap == null) {
            Log.e(IMAGE_LOG, "crop source decode returned null");
            Toast.makeText(this, R.string.err_image, Toast.LENGTH_SHORT).show();
            finishCancelled();
            return;
        }
        Log.d(IMAGE_LOG, "crop source decoded " + bitmap.getWidth() + "x" + bitmap.getHeight());
        overlay = findViewById(R.id.imageOverlay);
        overlay.setRoleLabels(getString(R.string.image_crop_label), "", "");
        overlay.setBitmap(bitmap);
        overlay.setPendingRole(SelectionOverlayView.ROLE_NAME);
        findViewById(R.id.btnCancelImage).setOnClickListener(v -> finishCancelled());
        findViewById(R.id.btnConfirmImage).setOnClickListener(v -> confirm());
    }

    @Override
    protected void onDestroy() {
        Log.d(IMAGE_LOG, "ImageCropActivity.onDestroy finishing=" + isFinishing());
        super.onDestroy();
    }

    private Bitmap load(Uri uri) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
                int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (largest / sample > 3072) {
                    sample *= 2;
                }
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample);
                }
            });
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private void confirm() {
        Rect crop = overlay.getBitmapRect(SelectionOverlayView.ROLE_NAME);
        if (crop == null) {
            Log.w(IMAGE_LOG, "crop confirm without selection");
            Toast.makeText(this, R.string.image_crop_select, Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap result = Bitmap.createBitmap(bitmap, crop.left, crop.top,
                crop.width(), crop.height());
        Log.d(IMAGE_LOG, "crop selected " + crop.width() + "x" + crop.height());
        try {
            File directory = new File(getCacheDir(), "images");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Cannot create image cache");
            }
            File file = new File(directory, "crop-" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!result.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw new IOException("Cannot encode JPEG");
                }
            } finally {
                result.recycle();
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Log.d(IMAGE_LOG, "crop JPG written bytes=" + file.length());
            deletePrivateSource();
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_CROPPED_URI, uri));
            finish();
        } catch (IOException exception) {
            Log.e(IMAGE_LOG, "crop JPG write failed: "
                    + exception.getClass().getSimpleName());
            Toast.makeText(this, R.string.err_image, Toast.LENGTH_SHORT).show();
        }
    }

    private void finishCancelled() {
        deletePrivateSource();
        setResult(RESULT_CANCELED);
        finish();
    }

    private void deletePrivateSource() {
        if (sourceUri != null && getPackageName().concat(".fileprovider")
                .equals(sourceUri.getAuthority())) {
            getContentResolver().delete(sourceUri, null, null);
        }
    }
}
