package com.example.securepasswordmanager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.securepasswordmanager.crypto.VaultImageProcessor;
import com.example.securepasswordmanager.crypto.VaultItem;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Point;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-app camera flow for scanning, rectifying, and reviewing multi-page documents. */
public final class DocumentScanActivity extends AppCompatActivity {
    public static final String EXTRA_PAGE_URIS = "document_page_uris";
    public static final String EXTRA_TITLE = "document_title";
    public static final String EXTRA_CALIBRATION_ONLY = "calibration_only";
    private static final int CALIBRATION_TARGET_VIEWS = 8;
    private static final int MAX_CALIBRATION_VIEWS = 12;
    private static final int MAX_MISSED_EDGE_FRAMES = 7;
    private static final float EDGE_SMOOTHING = 0.3f;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();
    private final List<File> acceptedPages = new ArrayList<>();
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    bindCamera();
                } else {
                    Toast.makeText(this, R.string.err_camera_permission, Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

    private PreviewView previewView;
    private DocumentEdgeOverlay edgeOverlay;
    private ImageView reviewImage;
    private TextView status;
    private TextView pageCount;
    private AppCompatImageButton flashlightToggle;
    private AppCompatImageButton captureButton;
    private AppCompatImageButton finishButton;
    private AppCompatImageButton calibrateButton;
    private View cameraControls;
    private View reviewControls;
    private ImageCapture imageCapture;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private String cameraId;
    private int sensorPixelWidth;
    private int sensorPixelHeight;
    private Rect activeArray;
    private int captureRotationDegrees;
    private DocumentCameraCalibration calibration;
    private final List<Point[]> calibrationObjectViews = new ArrayList<>();
    private final List<Point[]> calibrationImageViews = new ArrayList<>();
    private Double requestedAspect;
    private File reviewFile;
    private File capturingFile;
    private Bitmap reviewBitmap;
    private DocumentEdgeDetector.Quad displayedQuad;
    private volatile boolean cameraMode = true;
    private boolean captureInProgress;
    private boolean resultDelivered;
    private boolean torchEnabled;
    private boolean torchRequestPending;
    private boolean aspectChooserShown;
    private boolean calibrating;
    private boolean calibrationOnly;
    private int missedEdgeFrames;
    private long lastAnalysisAt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        calibrationOnly = getIntent().getBooleanExtra(EXTRA_CALIBRATION_ONLY, false);
        if (savedInstanceState == null) {
            clearScanCache();
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_document_scan);
        View root = findViewById(R.id.documentScanRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);

        previewView = findViewById(R.id.documentCameraPreview);
        edgeOverlay = findViewById(R.id.documentEdgeOverlay);
        reviewImage = findViewById(R.id.documentReviewImage);
        status = findViewById(R.id.documentScanStatus);
        pageCount = findViewById(R.id.documentPageCount);
        flashlightToggle = findViewById(R.id.documentFlashlight);
        captureButton = findViewById(R.id.documentCapture);
        finishButton = findViewById(R.id.documentFinish);
        calibrateButton = findViewById(R.id.documentCalibrate);
        cameraControls = findViewById(R.id.documentCameraControls);
        reviewControls = findViewById(R.id.documentReviewControls);

        findViewById(R.id.documentCancel).setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        captureButton.setOnClickListener(view -> capturePage());
        finishButton.setOnClickListener(view -> {
            if (calibrating) {
                calibrateCamera();
            } else {
                requestDocumentName();
            }
        });
        calibrateButton.setOnClickListener(view -> startCalibrationWizard(false));
        flashlightToggle.setOnClickListener(view -> setTorchEnabled(!torchEnabled));
        findViewById(R.id.documentRetake).setOnClickListener(view -> discardReview());
        findViewById(R.id.documentAccept).setOnClickListener(view -> acceptPage());
        updatePageCount();

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (calibrationOnly) {
            startCameraAfterAspectSelection();
        } else {
            showAspectChooser();
        }
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        cameraExecutor.shutdownNow();
        processingExecutor.shutdownNow();
        if (reviewBitmap != null) {
            if (reviewImage != null) {
                reviewImage.setImageDrawable(null);
            }
            reviewBitmap.recycle();
            reviewBitmap = null;
        }
        if (!resultDelivered) {
            clearScanCache();
        }
        super.onDestroy();
    }

    private void showAspectChooser() {
        if (aspectChooserShown || isFinishing()) {
            return;
        }
        aspectChooserShown = true;
        View content = getLayoutInflater()
                .inflate(R.layout.dialog_document_aspect_chooser, null);
        LinearLayout actions = content.findViewById(R.id.documentAspectActions);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(content);

        String[] options = getResources().getStringArray(R.array.document_aspect_options);
        int[] icons = {
                R.drawable.ic_person,
                R.drawable.ic_note,
                R.drawable.ic_note,
                R.drawable.ic_settings,
                R.drawable.ic_camera
        };
        for (int index = 0; index < options.length; index++) {
            View row = getLayoutInflater()
                    .inflate(R.layout.item_list_menu_action, actions, false);
            ((ImageView) row.findViewById(R.id.menuIcon)).setImageResource(icons[index]);
            ((TextView) row.findViewById(R.id.menuTitle)).setText(options[index]);
            int selection = index;
            row.setOnClickListener(view -> {
                dialog.dismiss();
                selectDocumentAspect(selection);
            });
            actions.addView(row);
        }
        content.findViewById(R.id.documentAspectCancel).setOnClickListener(view -> {
            dialog.dismiss();
            finish();
        });
        dialog.setOnCancelListener(ignored -> finish());
        dialog.show();
    }

    private void selectDocumentAspect(int choice) {
        if (choice == 3) {
            showCustomAspectDialog();
        } else if (choice == 4) {
            requestedAspect = null;
            startCameraAfterAspectSelection();
        } else {
            requestedAspect = choice == 0
                    ? DocumentCameraCalibration.ID1_WIDTH_MM
                    / DocumentCameraCalibration.ID1_HEIGHT_MM
                    : choice == 1 ? 125d / 88d : 210d / 297d;
            startCameraAfterAspectSelection();
        }
    }

    private void showCustomAspectDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Math.round(20
                * getResources().getDisplayMetrics().density);
        content.setPadding(horizontal, 0, horizontal, 0);
        TextInputLayout widthLayout = new TextInputLayout(this);
        widthLayout.setHint(getString(R.string.document_aspect_width));
        TextInputEditText widthInput = new TextInputEditText(this);
        widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        widthLayout.addView(widthInput);
        content.addView(widthLayout);
        TextInputLayout heightLayout = new TextInputLayout(this);
        heightLayout.setHint(getString(R.string.document_aspect_height));
        TextInputEditText heightInput = new TextInputEditText(this);
        heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        heightLayout.addView(heightInput);
        content.addView(heightLayout);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.document_aspect_custom_title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, (ignored, which) -> finish())
                .setPositiveButton(R.string.action_continue, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            double width = parsePositive(widthInput);
                            double height = parsePositive(heightInput);
                            if (!Double.isFinite(width) || !Double.isFinite(height)
                                    || width <= 0 || height <= 0
                                    || !Double.isFinite(width / height)
                                    || width / height <= 0
                                    || !Double.isFinite(height / width)) {
                                widthLayout.setError(getString(R.string.document_aspect_invalid));
                                return;
                            }
                            requestedAspect = width / height;
                            dialog.dismiss();
                            startCameraAfterAspectSelection();
                        }));
        dialog.show();
    }

    private static double parsePositive(TextInputEditText input) {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private void startCameraAfterAspectSelection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            bindCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void bindCamera() {
        androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this).get();
                int rotation = previewView.getDisplay() == null
                        ? Surface.ROTATION_0 : previewView.getDisplay().getRotation();
                Preview.Builder previewBuilder = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(rotation);
                new Camera2Interop.Extender<>(previewBuilder).setCaptureRequestOption(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);
                Preview preview = previewBuilder.build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(rotation);
                new Camera2Interop.Extender<>(captureBuilder).setCaptureRequestOption(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);
                imageCapture = captureBuilder.build();
                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
                new Camera2Interop.Extender<>(analysisBuilder).setCaptureRequestOption(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);
                ImageAnalysis analysis = analysisBuilder.build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, analysis);
                captureRotationDegrees =
                        camera.getCameraInfo().getSensorRotationDegrees(rotation);
                loadCameraCalibration();
                flashlightToggle.setVisibility(camera.getCameraInfo().hasFlashUnit()
                        ? View.VISIBLE : View.GONE);
                syncFlashlightToggle();
                if (calibrationOnly) {
                    previewView.post(() -> startCalibrationWizard(true));
                } else if (requestedAspect == null && calibration == null) {
                    previewView.post(() -> promptForRequiredCalibration());
                }
            } catch (ExecutionException exception) {
                showCameraError(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                showCameraError(exception);
            } catch (RuntimeException exception) {
                showCameraError(exception);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void loadCameraCalibration() {
        calibration = null;
        cameraId = null;
        sensorPixelWidth = 0;
        sensorPixelHeight = 0;
        activeArray = null;
        try {
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            cameraId = camera2Info.getCameraId();
            Size pixelArray = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            Rect active = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
            if (active == null) {
                active = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            }
            float[] intrinsic = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
            float[] lensDistortion = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_DISTORTION);
            if (pixelArray != null) {
                sensorPixelWidth = pixelArray.getWidth();
                sensorPixelHeight = pixelArray.getHeight();
                activeArray = active == null ? null : new Rect(active);
                calibration = DocumentCameraCalibration.load(this, cameraId,
                        sensorPixelWidth, sensorPixelHeight);
                if (calibration == null) {
                    calibration = DocumentCameraCalibration.fromCamera2(cameraId,
                            sensorPixelWidth, sensorPixelHeight, intrinsic, lensDistortion);
                }
            }
        } catch (RuntimeException exception) {
            android.util.Log.w("SPM_DOCUMENT_SCAN", "camera calibration metadata unavailable: "
                    + exception.getClass().getSimpleName());
            calibration = null;
        }
    }

    private void promptForRequiredCalibration() {
        if (isFinishing() || requestedAspect != null || calibration != null) {
            return;
        }
        if (sensorPixelWidth <= 0 || sensorPixelHeight <= 0 || cameraId == null) {
            showCalibrationUnavailable();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.document_calibration_title)
                .setMessage(R.string.document_calibration_required)
                .setPositiveButton(R.string.document_calibration_start,
                        (dialog, which) -> startCalibrationWizard(true))
                .setNeutralButton(R.string.document_aspect_choose_known, (dialog, which) -> {
                    aspectChooserShown = false;
                    previewView.post(this::showAspectChooser);
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void showCalibrationUnavailable() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.document_calibration_title)
                .setMessage(R.string.document_calibration_unavailable);
        if (calibrationOnly) {
            builder.setPositiveButton(R.string.action_close, (dialog, which) -> finish());
        } else {
            builder.setPositiveButton(R.string.document_aspect_choose_known, (dialog, which) -> {
                    aspectChooserShown = false;
                    previewView.post(this::showAspectChooser);
            });
        }
        builder.setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void startCalibrationWizard(boolean required) {
        if (cameraId == null || sensorPixelWidth <= 0 || sensorPixelHeight <= 0) {
            if (required) {
                showCalibrationUnavailable();
            } else {
                Toast.makeText(this, R.string.document_calibration_unavailable,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.document_calibration_title)
                .setMessage(R.string.document_calibration_instructions)
                .setPositiveButton(R.string.document_calibration_start,
                        (dialog, which) -> beginCalibration())
                .setNegativeButton(calibrationOnly || !required
                        ? R.string.action_cancel : R.string.document_aspect_choose_known,
                        (dialog, which) -> {
                    if (required && !calibrationOnly) {
                        aspectChooserShown = false;
                        previewView.post(this::showAspectChooser);
                    } else if (calibrationOnly) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void beginCalibration() {
        calibrationObjectViews.clear();
        calibrationImageViews.clear();
        calibrating = true;
        captureInProgress = false;
        setTorchEnabled(false);
        calibrateButton.setVisibility(View.GONE);
        captureButton.setImageResource(R.drawable.ic_camera);
        captureButton.setContentDescription(getString(R.string.document_calibration_capture));
        captureButton.setTooltipText(getString(R.string.document_calibration_capture));
        finishButton.setImageResource(R.drawable.ic_settings);
        finishButton.setContentDescription(getString(R.string.document_calibration_finish));
        finishButton.setTooltipText(getString(R.string.document_calibration_finish));
        status.setText(R.string.document_calibration_instructions);
        updateCalibrationCount();
        updateCameraActions();
    }

    private void updateCalibrationCount() {
        pageCount.setText(getString(R.string.document_calibration_count,
                calibrationImageViews.size(), CALIBRATION_TARGET_VIEWS));
    }

    private void calibrateCamera() {
        if (captureInProgress || calibrationImageViews.size()
                < DocumentCameraCalibration.REQUIRED_VIEWS) {
            return;
        }
        if (!hasVariedCalibrationViews()) {
            status.setText(R.string.document_calibration_more_angles);
            return;
        }
        finishButton.setEnabled(false);
        captureButton.setEnabled(false);
        status.setText(R.string.document_calibration_working);
        List<Point[]> objectViews = new ArrayList<>(calibrationObjectViews);
        List<Point[]> imageViews = new ArrayList<>(calibrationImageViews);
        try {
            processingExecutor.execute(() -> {
                try {
                    DocumentCameraCalibration candidate = DocumentCameraCalibration.calibrate(
                            cameraId, sensorPixelWidth, sensorPixelHeight, objectViews, imageViews);
                    candidate.save(this);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        calibration = candidate;
                        calibrating = false;
                        calibrateButton.setVisibility(View.VISIBLE);
                        updateScannerButtonIcons();
                        updatePageCount();
                        status.setText(R.string.document_calibration_success);
                        updateCameraActions();
                        if (calibrationOnly) {
                            setResult(RESULT_OK);
                            finish();
                        }
                    });
                } catch (RuntimeException exception) {
                    android.util.Log.w("SPM_DOCUMENT_SCAN", "camera calibration failed: "
                            + exception.getClass().getSimpleName());
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        status.setText(R.string.document_calibration_failed);
                        updateCameraActions();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            status.setText(R.string.document_calibration_failed);
            updateCameraActions();
        }
    }

    private boolean hasVariedCalibrationViews() {
        int variedViews = 0;
        List<double[]> signatures = new ArrayList<>();
        for (Point[] points : calibrationImageViews) {
            double first = distance(points[0], points[1]);
            double second = distance(points[1], points[2]);
            double oppositeFirst = distance(points[3], points[2]);
            double oppositeSecond = distance(points[0], points[3]);
            double cosine = ((points[1].x - points[0].x) * (points[3].x - points[0].x)
                    + (points[1].y - points[0].y) * (points[3].y - points[0].y))
                    / Math.max(1d, first * oppositeSecond);
            double skew = Math.max(Math.abs(first - oppositeFirst)
                            / Math.max(first, oppositeFirst),
                    Math.abs(second - oppositeSecond) / Math.max(second, oppositeSecond));
            double[] signature = {cosine, skew};
            boolean distinct = true;
            for (double[] previous : signatures) {
                if (Math.hypot(signature[0] - previous[0],
                        signature[1] - previous[1]) < 0.025) {
                    distinct = false;
                    break;
                }
            }
            if (distinct && (Math.abs(cosine) > 0.05 || skew > 0.05)) {
                signatures.add(signature);
                variedViews++;
            }
        }
        return variedViews >= 3;
    }

    private void analyzeFrame(ImageProxy frame) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (!cameraMode || now - lastAnalysisAt < 120) {
                return;
            }
            lastAnalysisAt = now;
            ImageProxy.PlaneProxy luminance = frame.getPlanes()[0];
            DocumentEdgeDetector.Quad quad = DocumentEdgeDetector.detectLuminance(
                    luminance.getBuffer(), frame.getWidth(), frame.getHeight(),
                    luminance.getRowStride(), luminance.getPixelStride(),
                    frame.getImageInfo().getRotationDegrees());
            runOnUiThread(() -> {
                if (cameraMode && !isFinishing()) {
                    updateStableEdge(quad);
                }
            });
        } catch (RuntimeException exception) {
            android.util.Log.w("SPM_DOCUMENT_SCAN", "edge analysis failed: "
                    + exception.getClass().getSimpleName());
        } finally {
            frame.close();
        }
    }

    private void updateStableEdge(DocumentEdgeDetector.Quad candidate) {
        if (candidate == null) {
            if (displayedQuad == null || ++missedEdgeFrames > MAX_MISSED_EDGE_FRAMES) {
                displayedQuad = null;
                missedEdgeFrames = 0;
                edgeOverlay.setQuad(null);
            }
            return;
        }

        missedEdgeFrames = 0;
        if (displayedQuad == null) {
            displayedQuad = candidate;
        } else {
            float[] points = new float[candidate.points.length];
            for (int index = 0; index < points.length; index++) {
                points[index] = displayedQuad.points[index] * (1f - EDGE_SMOOTHING)
                        + candidate.points[index] * EDGE_SMOOTHING;
            }
            displayedQuad = new DocumentEdgeDetector.Quad(points, candidate.aspectRatio);
        }
        edgeOverlay.setQuad(displayedQuad);
    }

    private void capturePage() {
        if (calibrating) {
            captureCalibrationView();
            return;
        }
        if (imageCapture == null || captureInProgress || torchRequestPending
                || acceptedPages.size() >= VaultItem.MAX_DOCUMENT_PAGES) {
            return;
        }
        File directory = new File(getCacheDir(), "documents");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        File captured;
        try {
            captured = File.createTempFile("capture-", ".jpg", directory);
        } catch (IOException exception) {
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        DocumentEdgeDetector.Quad captureQuad = displayedQuad;
        capturingFile = captured;
        captureInProgress = true;
        captureButton.setEnabled(false);
        finishButton.setEnabled(false);
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(captured).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults output) {
                        capturingFile = null;
                        prepareReview(captured, captureQuad);
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        capturingFile = null;
                        deleteFile(captured);
                        captureInProgress = false;
                        updateCameraActions();
                        Toast.makeText(DocumentScanActivity.this,
                                R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void prepareReview(File captured, DocumentEdgeDetector.Quad previewQuad) {
        try {
            processingExecutor.execute(() -> {
                File rectified = null;
                try {
                    byte[] jpeg = DocumentEdgeDetector.rectify(this, captured, requestedAspect,
                            calibration, captureRotationDegrees, activeArray, previewQuad);
                    try {
                        rectified = File.createTempFile("page-", ".jpg",
                                new File(getCacheDir(), "documents"));
                        try (FileOutputStream output = new FileOutputStream(rectified)) {
                            output.write(jpeg);
                            output.flush();
                        }
                    } finally {
                        java.util.Arrays.fill(jpeg, (byte) 0);
                    }
                    Bitmap preview = decodeReview(rectified);
                    File ready = rectified;
                    rectified = null;
                    runOnUiThread(() -> {
                        if (isFinishing()) {
                            preview.recycle();
                            deleteFile(ready);
                            return;
                        }
                        reviewFile = ready;
                        reviewBitmap = preview;
                        captureInProgress = false;
                        showReview();
                    });
                } catch (DocumentEdgeDetector.CalibrationRequiredException exception) {
                    runOnUiThread(() -> {
                        captureInProgress = false;
                        status.setText(R.string.document_calibration_required);
                        updateCameraActions();
                        promptForRequiredCalibration();
                    });
                } catch (DocumentEdgeDetector.EdgesNotFoundException exception) {
                    android.util.Log.w("SPM_DOCUMENT_SCAN", "page rectification failed: "
                            + exception.getClass().getSimpleName());
                    File failed = rectified;
                    runOnUiThread(() -> {
                        deleteFile(failed);
                        captureInProgress = false;
                        updateCameraActions();
                        status.setText(R.string.document_edge_not_found);
                        Toast.makeText(this, R.string.document_edge_not_found,
                                Toast.LENGTH_LONG).show();
                    });
                } catch (IOException | RuntimeException exception) {
                    android.util.Log.w("SPM_DOCUMENT_SCAN", "page processing failed: "
                            + exception.getClass().getSimpleName());
                    File failed = rectified;
                    runOnUiThread(() -> {
                        deleteFile(failed);
                        captureInProgress = false;
                        updateCameraActions();
                        status.setText(R.string.document_scan_failed);
                        Toast.makeText(this, R.string.document_scan_failed,
                                Toast.LENGTH_LONG).show();
                    });
                } finally {
                    deleteFile(captured);
                    deleteFile(rectified);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            deleteFile(captured);
            captureInProgress = false;
            captureButton.setEnabled(true);
            finishButton.setEnabled(!acceptedPages.isEmpty());
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void captureCalibrationView() {
        if (imageCapture == null || captureInProgress || torchRequestPending
                || calibrationImageViews.size() >= MAX_CALIBRATION_VIEWS) {
            return;
        }
        File directory = new File(getCacheDir(), "documents");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        File captured;
        try {
            captured = File.createTempFile("capture-", ".jpg", directory);
        } catch (IOException exception) {
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        capturingFile = captured;
        captureInProgress = true;
        updateCameraActions();
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(captured).build(),
                ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults output) {
                        capturingFile = null;
                        prepareCalibrationView(captured);
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        capturingFile = null;
                        deleteFile(captured);
                        captureInProgress = false;
                        updateCameraActions();
                        Toast.makeText(DocumentScanActivity.this,
                                R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void prepareCalibrationView(File captured) {
        try {
            processingExecutor.execute(() -> {
                try {
                    DocumentEdgeDetector.CaptureCorners detected =
                            DocumentEdgeDetector.detectCaptureCorners(this, captured);
                    DocumentCameraCalibration.ImageTransform imageTransform =
                            new DocumentCameraCalibration.ImageTransform(detected.width,
                                    detected.height, captureRotationDegrees, sensorPixelWidth,
                                    sensorPixelHeight, activeArray);
                    Point[] sensorPoints = new Point[detected.points.length];
                    for (int index = 0; index < detected.points.length; index++) {
                        sensorPoints[index] = imageTransform.toSensor(detected.points[index]);
                    }
                    double firstPair = (distance(sensorPoints[0], sensorPoints[1])
                            + distance(sensorPoints[3], sensorPoints[2])) / 2d;
                    double secondPair = (distance(sensorPoints[0], sensorPoints[3])
                            + distance(sensorPoints[1], sensorPoints[2])) / 2d;
                    Point[] objectPoints = DocumentCameraCalibration.calibrationObjectCorners(
                            firstPair >= secondPair);
                    runOnUiThread(() -> {
                        deleteFile(captured);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        calibrationImageViews.add(sensorPoints);
                        calibrationObjectViews.add(objectPoints);
                        captureInProgress = false;
                        updateCalibrationCount();
                        status.setText(calibrationImageViews.size() >=
                                DocumentCameraCalibration.REQUIRED_VIEWS
                                ? R.string.document_calibration_ready
                                : R.string.document_calibration_instructions);
                        updateCameraActions();
                    });
                } catch (IOException | RuntimeException exception) {
                    android.util.Log.w("SPM_DOCUMENT_SCAN", "calibration view failed: "
                            + exception.getClass().getSimpleName());
                    runOnUiThread(() -> {
                        deleteFile(captured);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        captureInProgress = false;
                        status.setText(R.string.document_calibration_view_failed);
                        updateCameraActions();
                    });
                } finally {
                    deleteFile(captured);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            deleteFile(captured);
            captureInProgress = false;
            updateCameraActions();
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x - second.x, first.y - second.y);
    }

    private Bitmap decodeReview(File file) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(),
                Uri.fromFile(file));
        try {
            return ImageDecoder.decodeBitmap(source, (decoder, info, unused) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (largest / sample > 1600) {
                    sample *= 2;
                }
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample);
                }
            });
        } catch (RuntimeException exception) {
            throw new IOException("Could not create document preview", exception);
        }
    }

    private void showReview() {
        setTorchEnabled(false);
        cameraMode = false;
        pageCount.setText(getString(R.string.document_review_page, acceptedPages.size() + 1));
        previewView.setVisibility(View.GONE);
        edgeOverlay.setVisibility(View.GONE);
        reviewImage.setImageBitmap(reviewBitmap);
        reviewImage.setVisibility(View.VISIBLE);
        cameraControls.setVisibility(View.GONE);
        reviewControls.setVisibility(View.VISIBLE);
        status.setText(R.string.document_review_hint);
    }

    private void discardReview() {
        deleteFile(reviewFile);
        reviewFile = null;
        clearReviewBitmap();
        showCamera();
    }

    private void acceptPage() {
        if (reviewFile == null) {
            return;
        }
        acceptedPages.add(reviewFile);
        reviewFile = null;
        clearReviewBitmap();
        updatePageCount();
        showCamera();
    }

    private void showCamera() {
        cameraMode = true;
        reviewImage.setVisibility(View.GONE);
        reviewImage.setImageDrawable(null);
        previewView.setVisibility(View.VISIBLE);
        edgeOverlay.setVisibility(View.VISIBLE);
        displayedQuad = null;
        missedEdgeFrames = 0;
        edgeOverlay.setQuad(null);
        reviewControls.setVisibility(View.GONE);
        cameraControls.setVisibility(View.VISIBLE);
        calibrateButton.setVisibility(View.VISIBLE);
        if (!calibrating) {
            updatePageCount();
        }
        updateScannerButtonIcons();
        status.setText(acceptedPages.size() >= VaultItem.MAX_DOCUMENT_PAGES
                ? R.string.document_scan_max_reached : R.string.document_scan_hint);
        updateCameraActions();
    }

    private void updatePageCount() {
        pageCount.setText(getString(R.string.document_page_count_compact,
                acceptedPages.size()));
    }

    private void updateScannerButtonIcons() {
        int captureDescription;
        if (calibrating) {
            captureButton.setImageResource(R.drawable.ic_camera);
            captureDescription = R.string.document_calibration_capture;
            finishButton.setImageResource(R.drawable.ic_settings);
            finishButton.setContentDescription(getString(R.string.document_calibration_finish));
            finishButton.setTooltipText(getString(R.string.document_calibration_finish));
        } else {
            boolean addingPage = !acceptedPages.isEmpty();
            captureButton.setImageResource(addingPage
                    ? R.drawable.ic_document_add_page : R.drawable.ic_camera);
            captureDescription = addingPage
                    ? R.string.document_scan_add_page : R.string.document_scan_capture;
            finishButton.setImageResource(R.drawable.ic_document_done);
            finishButton.setContentDescription(getString(R.string.document_scan_finish));
            finishButton.setTooltipText(getString(R.string.document_scan_finish));
        }
        captureButton.setContentDescription(getString(captureDescription));
        captureButton.setTooltipText(getString(captureDescription));
    }

    private void requestDocumentName() {
        if (acceptedPages.isEmpty()) {
            return;
        }
        setTorchEnabled(false);
        View content = getLayoutInflater().inflate(R.layout.dialog_document_name, null);
        TextInputLayout nameLayout = content.findViewById(R.id.documentNameLayout);
        TextInputEditText name = content.findViewById(R.id.documentName);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.document_name_title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            String title = name.getText() == null ? ""
                                    : name.getText().toString().trim();
                            if (title.isEmpty()) {
                                nameLayout.setError(getString(R.string.document_name_required));
                                return;
                            }
                            if (returnDocument(title)) {
                                dialog.dismiss();
                            }
                        }));
        dialog.show();
    }

    private boolean returnDocument(String title) {
        ArrayList<Uri> uris = new ArrayList<>(acceptedPages.size());
        try {
            for (File page : acceptedPages) {
                uris.add(FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", page));
            }
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
        deleteUnacceptedScanFiles();
        Intent result = new Intent()
                .putParcelableArrayListExtra(EXTRA_PAGE_URIS, uris)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        resultDelivered = true;
        setResult(RESULT_OK, result);
        finish();
        return true;
    }

    private void clearReviewBitmap() {
        reviewImage.setImageDrawable(null);
        if (reviewBitmap != null) {
            reviewBitmap.recycle();
            reviewBitmap = null;
        }
    }

    private void showCameraError(Exception exception) {
        android.util.Log.e("SPM_DOCUMENT_SCAN", "camera could not start: "
                + exception.getClass().getSimpleName());
        runOnUiThread(() -> {
            if (!isFinishing()) {
                Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            android.util.Log.w("SPM_DOCUMENT_SCAN", "could not remove temporary scan");
        }
    }

    private void clearScanCache() {
        File directory = new File(getCacheDir(), "documents");
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (isScanFile(file)) {
                deleteFile(file);
            }
        }
    }

    private void deleteUnacceptedScanFiles() {
        File directory = new File(getCacheDir(), "documents");
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (isScanFile(file) && !acceptedPages.contains(file)) {
                deleteFile(file);
            }
        }
    }

    private static boolean isScanFile(File file) {
        String name = file.getName();
        return file.isFile() && name.endsWith(".jpg")
                && (name.startsWith("capture-") || name.startsWith("page-"));
    }

    private void setTorchEnabled(boolean enabled) {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            torchEnabled = false;
            syncFlashlightToggle();
            return;
        }
        if (torchRequestPending) {
            return;
        }
        if (torchEnabled == enabled) {
            syncFlashlightToggle();
            return;
        }

        torchRequestPending = true;
        syncFlashlightToggle();
        ListenableFuture<Void> request;
        try {
            request = camera.getCameraControl().enableTorch(enabled);
            request.addListener(() -> {
                try {
                    request.get();
                    torchEnabled = enabled;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showFlashlightError(exception);
                } catch (java.util.concurrent.ExecutionException
                         | java.util.concurrent.CancellationException exception) {
                    showFlashlightError(exception);
                } finally {
                    torchRequestPending = false;
                    syncFlashlightToggle();
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (RuntimeException exception) {
            torchRequestPending = false;
            showFlashlightError(exception);
            syncFlashlightToggle();
        }
    }

    private void syncFlashlightToggle() {
        if (flashlightToggle == null) {
            return;
        }
        flashlightToggle.setImageResource(torchEnabled
                ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        int description = torchEnabled ? R.string.document_flashlight_turn_off
                : R.string.document_flashlight_turn_on;
        flashlightToggle.setContentDescription(getString(description));
        flashlightToggle.setTooltipText(getString(description));
        flashlightToggle.setEnabled(camera != null
                && camera.getCameraInfo().hasFlashUnit() && !torchRequestPending);
        updateCameraActions();
    }

    private void updateCameraActions() {
        if (cameraControls == null || cameraControls.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean ready = !captureInProgress && !torchRequestPending;
        if (calibrating) {
            captureButton.setEnabled(ready
                    && calibrationImageViews.size() < MAX_CALIBRATION_VIEWS);
            finishButton.setEnabled(ready && calibrationImageViews.size()
                    >= DocumentCameraCalibration.REQUIRED_VIEWS);
            calibrateButton.setVisibility(View.GONE);
        } else {
            captureButton.setEnabled(ready && acceptedPages.size()
                    < VaultItem.MAX_DOCUMENT_PAGES);
            finishButton.setEnabled(ready && !acceptedPages.isEmpty());
            calibrateButton.setVisibility(View.VISIBLE);
        }
    }

    private void showFlashlightError(Exception exception) {
        android.util.Log.w("SPM_DOCUMENT_SCAN", "could not change torch state: "
                + exception.getClass().getSimpleName());
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, R.string.document_flash_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
