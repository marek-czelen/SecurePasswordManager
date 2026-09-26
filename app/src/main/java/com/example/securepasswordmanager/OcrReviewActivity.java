package com.example.securepasswordmanager;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Full-screen OCR review. The user marks login / password (and optionally a name) with
 * adjustable rectangles over the captured image. Only on confirm does the app OCR the
 * selected regions and return them. The app pre-suggests boxes but never confirms them.
 */
public final class OcrReviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_LOGIN = "login";
    public static final String EXTRA_PASSWORD = "password";

    private static final int MAX_DIMENSION = 3072;
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SelectionOverlayView overlay;
    private Bitmap bitmap;
    private boolean processing;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_ocr_review);
        applyInsets();

        overlay = findViewById(R.id.overlay);
        overlay.setRoleLabels(getString(R.string.ocr_mark_name),
                getString(R.string.ocr_mark_login), getString(R.string.ocr_mark_password));

        Uri image = getIntent() == null ? null
                : getIntent().getParcelableExtra(EXTRA_IMAGE_URI);
        if (image == null) {
            finishCancel();
            return;
        }
        bitmap = loadBitmap(image);
        if (bitmap == null) {
            Toast.makeText(this, R.string.err_ocr, Toast.LENGTH_SHORT).show();
            finishCancel();
            return;
        }
        overlay.setBitmap(bitmap);
        suggestRegions();

        findViewById(R.id.btnLogin).setOnClickListener(v ->
                overlay.setPendingRole(SelectionOverlayView.ROLE_LOGIN));
        findViewById(R.id.btnPassword).setOnClickListener(v ->
                overlay.setPendingRole(SelectionOverlayView.ROLE_PASSWORD));
        findViewById(R.id.btnName).setOnClickListener(v ->
                overlay.setPendingRole(SelectionOverlayView.ROLE_NAME));
        findViewById(R.id.btnDelete).setOnClickListener(v -> overlay.deleteSelected());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finishCancel());
        findViewById(R.id.btnConfirm).setOnClickListener(v -> confirm());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void applyInsets() {
        View root = findViewById(R.id.ocrRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Nullable
    private Bitmap loadBitmap(Uri uri) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
                int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (max / sample > MAX_DIMENSION) {
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

    /** Best-effort pre-selection using ML Kit line boxes. The user can override everything. */
    private void suggestRegions() {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    overlay.post(() -> applySuggestions(text));
                    recognizer.close();
                })
                .addOnFailureListener(e -> recognizer.close());
    }

    private void applySuggestions(Text text) {
        Rect loginBox = null;
        Rect passwordBox = null;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText();
                String low = value.toLowerCase(Locale.getDefault());
                Rect box = line.getBoundingBox();
                if (box == null) {
                    continue;
                }
                if (loginBox == null && (EMAIL.matcher(value).find()
                        || low.contains("login") || low.contains("user")
                        || low.contains("użytkownik") || low.contains("uzytkownik"))) {
                    loginBox = box;
                } else if (passwordBox == null && (low.contains("pass")
                        || low.contains("hasło") || low.contains("haslo") || low.contains("pwd"))) {
                    passwordBox = box;
                }
            }
        }
        if (loginBox != null) {
            overlay.addSuggestion(SelectionOverlayView.ROLE_LOGIN, loginBox);
        }
        if (passwordBox != null) {
            overlay.addSuggestion(SelectionOverlayView.ROLE_PASSWORD, passwordBox);
        }
    }

    private void confirm() {
        if (processing) {
            return;
        }
        Rect loginRect = overlay.getBitmapRect(SelectionOverlayView.ROLE_LOGIN);
        Rect passwordRect = overlay.getBitmapRect(SelectionOverlayView.ROLE_PASSWORD);
        Rect nameRect = overlay.getBitmapRect(SelectionOverlayView.ROLE_NAME);
        if (loginRect == null && passwordRect == null) {
            Toast.makeText(this, R.string.ocr_need_selection, Toast.LENGTH_SHORT).show();
            return;
        }
        processing = true;
        Toast.makeText(this, R.string.ocr_processing, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String name = recognizeRegion(nameRect, false);
            String login = recognizeRegion(loginRect, true);
            String password = recognizeRegion(passwordRect, true);
            runOnUiThread(() -> {
                Intent data = new Intent();
                data.putExtra(EXTRA_NAME, name);
                data.putExtra(EXTRA_LOGIN, login);
                data.putExtra(EXTRA_PASSWORD, password);
                setResult(RESULT_OK, data);
                finish();
            });
        });
    }

    /** Crops the selected region (with a small margin) and OCRs it synchronously. */
    private String recognizeRegion(@Nullable Rect region, boolean singleToken) {
        if (region == null || bitmap == null) {
            return "";
        }
        // A small margin around the selection avoids clipping glyph edges, which greatly
        // improves recognition of tightly-drawn boxes.
        int marginX = Math.max(6, region.width() / 12);
        int marginY = Math.max(6, region.height() / 6);
        Rect safe = new Rect(
                Math.max(0, region.left - marginX), Math.max(0, region.top - marginY),
                Math.min(bitmap.getWidth(), region.right + marginX),
                Math.min(bitmap.getHeight(), region.bottom + marginY));
        if (safe.width() < 2 || safe.height() < 2) {
            return "";
        }
        Bitmap crop = Bitmap.createBitmap(bitmap, safe.left, safe.top, safe.width(), safe.height());
        // Upscale small crops so ML Kit has enough pixels for the text height.
        int target = 1200;
        int maxDim = Math.max(crop.getWidth(), crop.getHeight());
        if (maxDim < target) {
            float factor = target / (float) maxDim;
            Bitmap scaled = Bitmap.createScaledBitmap(crop,
                    Math.round(crop.getWidth() * factor),
                    Math.round(crop.getHeight() * factor), true);
            if (scaled != crop) {
                crop.recycle();
                crop = scaled;
            }
        }
        crop = enhanceForOcr(crop);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        AtomicReference<String> result = new AtomicReference<>("");
        CountDownLatch latch = new CountDownLatch(1);
        recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener(text -> {
                    result.set(text.getText());
                    latch.countDown();
                })
                .addOnFailureListener(e -> latch.countDown());
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        recognizer.close();
        crop.recycle();
        return sanitize(result.get(), singleToken);
    }

    /** Grayscale + contrast boost. Improves printed-text recognition; handwriting stays hard. */
    private static Bitmap enhanceForOcr(Bitmap source) {
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        float contrast = 1.5f;
        float translate = (-0.5f * contrast + 0.5f) * 255f;
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, translate,
                0, contrast, 0, 0, translate,
                0, 0, contrast, 0, translate,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(contrastMatrix);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(source, 0, 0, paint);
        source.recycle();
        return out;
    }

    private static String sanitize(String value, boolean singleToken) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", " ").replace("\n", " ").trim();
        if (singleToken) {
            cleaned = cleaned.replaceAll("\\s+", "");
        } else {
            cleaned = cleaned.replaceAll("\\s+", " ");
        }
        return cleaned;
    }

    private void finishCancel() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
