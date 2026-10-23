package com.cretorix.securepasswordmanager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.cretorix.securepasswordmanager.crypto.VaultImageProcessor;
import com.cretorix.securepasswordmanager.crypto.VaultItem;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.opencv.android.OpenCVLoader;

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
    private static final double ID1_CARD_ASPECT = 85.60d / 53.98d;
    private static final int MAX_REVIEW_IMAGE_EDGE = DocumentGeometry.MAX_RECTIFIED_EDGE;
    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();
    private final MediaActionSound shutterSound = new MediaActionSound();
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
    private ZoomableImageView reviewImage;
    private DocumentReviewOverlay reviewOverlay;
    private TextView status;
    private TextView pageCount;
    private AppCompatImageButton flashlightToggle;
    private AppCompatImageButton captureButton;
    private AppCompatImageButton finishButton;
    private AppCompatImageButton acceptButton;
    private AppCompatImageButton retakeButton;
    private AppCompatImageButton adjustEdgesButton;
    private View cameraControls;
    private View reviewControls;
    private ImageCapture imageCapture;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private Double requestedAspect;
    private File reviewFile;
    private File rectifiedReviewFile;
    private File capturingFile;
    private Bitmap reviewBitmap;
    private Bitmap rectifiedReviewBitmap;
    private boolean reviewHasDetectedEdges;
    private boolean showingRectifiedReview;
    private boolean captureInProgress;
    private boolean resultDelivered;
    private boolean torchEnabled;
    private boolean torchRequestPending;
    private boolean aspectChooserShown;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        reviewImage = findViewById(R.id.documentReviewImage);
        reviewOverlay = findViewById(R.id.documentReviewOverlay);
        reviewImage.setDoubleTapZoomEnabled(false);
        reviewImage.setOnImageMatrixChangedListener(reviewOverlay::setImageTransform);
        reviewOverlay.setImageView(reviewImage);
        status = findViewById(R.id.documentScanStatus);
        pageCount = findViewById(R.id.documentPageCount);
        flashlightToggle = findViewById(R.id.documentFlashlight);
        captureButton = findViewById(R.id.documentCapture);
        finishButton = findViewById(R.id.documentFinish);
        acceptButton = findViewById(R.id.documentAccept);
        retakeButton = findViewById(R.id.documentRetake);
        adjustEdgesButton = findViewById(R.id.documentAdjustEdges);
        cameraControls = findViewById(R.id.documentCameraControls);
        reviewControls = findViewById(R.id.documentReviewControls);

        findViewById(R.id.documentCancel).setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        captureButton.setOnClickListener(view -> capturePage());
        finishButton.setOnClickListener(view -> requestDocumentName());
        flashlightToggle.setOnClickListener(view -> setTorchEnabled(!torchEnabled));
        retakeButton.setOnClickListener(view -> discardReview());
        acceptButton.setOnClickListener(view -> acceptPage());
        adjustEdgesButton.setOnClickListener(view -> adjustEdges());
        reviewOverlay.setOnCornersChangedListener(valid -> {
            acceptButton.setEnabled(!captureInProgress
                    && (showingRectifiedReview || valid));
            if (reviewFile != null && !reviewHasDetectedEdges) {
                status.setText(valid ? R.string.document_review_edges_hint
                        : R.string.document_review_mark_hint);
            }
        });
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
        updatePageCount();

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        showAspectChooser();
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        processingExecutor.shutdownNow();
        shutterSound.release();
        if (reviewImage != null) {
            reviewImage.clearImage();
        }
        if (reviewBitmap != null) {
            reviewBitmap.recycle();
            reviewBitmap = null;
        }
        if (rectifiedReviewBitmap != null) {
            rectifiedReviewBitmap.recycle();
            rectifiedReviewBitmap = null;
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
                R.drawable.ic_settings
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
        } else {
            requestedAspect = choice == 0
                    ? ID1_CARD_ASPECT
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
                Preview preview = previewBuilder.build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                        ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build())
                        .setTargetRotation(rotation);
                imageCapture = captureBuilder.build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);
                flashlightToggle.setVisibility(camera.getCameraInfo().hasFlashUnit()
                        ? View.VISIBLE : View.GONE);
                syncFlashlightToggle();
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

    private void capturePage() {
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

        capturingFile = captured;
        captureInProgress = true;
        status.setText(R.string.document_scan_processing);
        shutterSound.play(MediaActionSound.SHUTTER_CLICK);
        updateCameraActions();
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(captured).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults output) {
                        capturingFile = null;
                        prepareReview(captured);
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        capturingFile = null;
                        deleteFile(captured);
                        captureInProgress = false;
                        updateCameraActions();
                        status.setText(R.string.document_scan_failed);
                        Toast.makeText(DocumentScanActivity.this,
                                R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void prepareReview(File captured) {
        try {
            processingExecutor.execute(() -> {
                try {
                    DocumentEdgeDetector.CaptureCorners detected = null;
                    try {
                        detected = DocumentEdgeDetector.detectCaptureCorners(this, captured);
                    } catch (DocumentEdgeDetector.EdgesNotFoundException ignored) {
                        // The review screen will let the user mark the document corners.
                    }
                    Bitmap preview = decodeReview(captured);
                    DocumentEdgeDetector.CaptureCorners detectedCorners = detected;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            preview.recycle();
                            deleteFile(captured);
                            return;
                        }
                        reviewFile = captured;
                        reviewBitmap = preview;
                        reviewHasDetectedEdges = detectedCorners != null;
                        reviewOverlay.setImageSize(preview.getWidth(), preview.getHeight());
                        if (detectedCorners == null) {
                            reviewOverlay.clearCorners();
                        } else {
                            reviewOverlay.setCorners(detectedCorners.points,
                                    detectedCorners.width, detectedCorners.height);
                        }
                        captureInProgress = false;
                        showReview();
                    });
                } catch (IOException | RuntimeException exception) {
                    android.util.Log.w("SPM_DOCUMENT_SCAN", "page processing failed: "
                            + exception.getClass().getSimpleName());
                    runOnUiThread(() -> {
                        deleteFile(captured);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        captureInProgress = false;
                        updateCameraActions();
                        status.setText(R.string.document_scan_failed);
                        Toast.makeText(this, R.string.document_scan_failed,
                                Toast.LENGTH_LONG).show();
                    });
                } finally {
                    if (isFinishing() || isDestroyed()) {
                        deleteFile(captured);
                    }
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

    private void updateReviewActions() {
        boolean ready = !captureInProgress
                && (showingRectifiedReview || reviewOverlay.isSelectionValid());
        acceptButton.setEnabled(ready);
        retakeButton.setEnabled(!captureInProgress);
        adjustEdgesButton.setEnabled(ready && showingRectifiedReview);
    }

    private Bitmap decodeReview(File file) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(),
                Uri.fromFile(file));
        try {
            return ImageDecoder.decodeBitmap(source, (decoder, info, unused) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (largest / sample > MAX_REVIEW_IMAGE_EDGE) {
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
        showingRectifiedReview = false;
        pageCount.setText(getString(R.string.document_review_page, acceptedPages.size() + 1));
        previewView.setVisibility(View.GONE);
        reviewImage.setImageBitmap(reviewBitmap);
        reviewImage.setVisibility(View.VISIBLE);
        reviewOverlay.setVisibility(View.VISIBLE);
        reviewOverlay.setEnabled(true);
        adjustEdgesButton.setVisibility(View.GONE);
        cameraControls.setVisibility(View.GONE);
        reviewControls.setVisibility(View.VISIBLE);
        acceptButton.setContentDescription(getString(R.string.document_review_straighten));
        acceptButton.setTooltipText(getString(R.string.document_review_straighten));
        status.setText(reviewHasDetectedEdges
                ? R.string.document_review_edges_hint : R.string.document_review_mark_hint);
        updateReviewActions();
    }

    private void showRectifiedReview() {
        showingRectifiedReview = true;
        pageCount.setText(getString(R.string.document_review_page, acceptedPages.size() + 1));
        reviewImage.setImageBitmap(rectifiedReviewBitmap);
        reviewOverlay.setVisibility(View.GONE);
        reviewOverlay.setEnabled(false);
        adjustEdgesButton.setVisibility(View.VISIBLE);
        cameraControls.setVisibility(View.GONE);
        reviewControls.setVisibility(View.VISIBLE);
        acceptButton.setContentDescription(getString(R.string.document_scan_accept));
        acceptButton.setTooltipText(getString(R.string.document_scan_accept));
        status.setText(R.string.document_review_rectified_hint);
        updateReviewActions();
    }

    private void adjustEdges() {
        if (!showingRectifiedReview || captureInProgress || reviewBitmap == null) {
            return;
        }
        deleteFile(rectifiedReviewFile);
        rectifiedReviewFile = null;
        if (rectifiedReviewBitmap != null) {
            rectifiedReviewBitmap.recycle();
            rectifiedReviewBitmap = null;
        }
        showingRectifiedReview = false;
        reviewImage.setImageBitmap(reviewBitmap);
        reviewOverlay.setVisibility(View.VISIBLE);
        reviewOverlay.setEnabled(true);
        adjustEdgesButton.setVisibility(View.GONE);
        acceptButton.setContentDescription(getString(R.string.document_review_straighten));
        acceptButton.setTooltipText(getString(R.string.document_review_straighten));
        status.setText(R.string.document_review_edges_hint);
        updateReviewActions();
    }

    private void discardReview() {
        if (captureInProgress) {
            return;
        }
        deleteFile(reviewFile);
        deleteFile(rectifiedReviewFile);
        reviewFile = null;
        rectifiedReviewFile = null;
        showingRectifiedReview = false;
        clearReviewBitmap();
        showCamera();
    }

    private void acceptPage() {
        if (reviewFile == null || captureInProgress) {
            return;
        }
        if (showingRectifiedReview) {
            commitReviewPage();
            return;
        }
        float[] corners = reviewOverlay.getNormalizedCorners();
        if (corners == null) {
            return;
        }

        rectifyPage(corners);
    }

    private void rectifyPage(float[] corners) {
        File source = reviewFile;
        captureInProgress = true;
        reviewOverlay.setEnabled(false);
        status.setText(R.string.document_review_rectifying);
        updateReviewActions();
        try {
            processingExecutor.execute(() -> {
                File rectified = null;
                try {
                    byte[] jpeg = DocumentEdgeDetector.rectify(this, source,
                            requestedAspect, corners);
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
                        if (isFinishing() || isDestroyed()) {
                            preview.recycle();
                            deleteFile(ready);
                            return;
                        }
                        rectifiedReviewFile = ready;
                        rectifiedReviewBitmap = preview;
                        captureInProgress = false;
                        showRectifiedReview();
                    });
                } catch (IOException | RuntimeException exception) {
                    android.util.Log.w("SPM_DOCUMENT_SCAN", "page rectification failed: "
                            + exception.getClass().getSimpleName());
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            deleteFile(source);
                            return;
                        }
                        captureInProgress = false;
                        reviewOverlay.setEnabled(true);
                        status.setText(R.string.document_scan_failed);
                        updateReviewActions();
                        Toast.makeText(this, R.string.document_scan_failed,
                                Toast.LENGTH_LONG).show();
                    });
                } finally {
                    deleteFile(rectified);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            captureInProgress = false;
            reviewOverlay.setEnabled(true);
            status.setText(R.string.document_scan_failed);
            updateReviewActions();
            Toast.makeText(this, R.string.document_scan_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void commitReviewPage() {
        if (rectifiedReviewFile == null) {
            return;
        }
        acceptedPages.add(rectifiedReviewFile);
        rectifiedReviewFile = null;
        deleteFile(reviewFile);
        reviewFile = null;
        showingRectifiedReview = false;
        clearReviewBitmap();
        updatePageCount();
        showCamera();
    }

    private void showCamera() {
        reviewImage.setVisibility(View.GONE);
        reviewImage.clearImage();
        reviewOverlay.setVisibility(View.GONE);
        reviewOverlay.clearCorners();
        reviewHasDetectedEdges = false;
        showingRectifiedReview = false;
        adjustEdgesButton.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        reviewControls.setVisibility(View.GONE);
        cameraControls.setVisibility(View.VISIBLE);
        updatePageCount();
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
        boolean addingPage = !acceptedPages.isEmpty();
        captureButton.setImageResource(addingPage
                ? R.drawable.ic_document_add_page : R.drawable.ic_camera);
        int captureDescription = addingPage
                ? R.string.document_scan_add_page : R.string.document_scan_capture;
        finishButton.setImageResource(R.drawable.ic_document_done);
        finishButton.setContentDescription(getString(R.string.document_scan_finish));
        finishButton.setTooltipText(getString(R.string.document_scan_finish));
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
        reviewImage.clearImage();
        if (reviewBitmap != null) {
            reviewBitmap.recycle();
            reviewBitmap = null;
        }
        if (rectifiedReviewBitmap != null) {
            rectifiedReviewBitmap.recycle();
            rectifiedReviewBitmap = null;
        }
        reviewOverlay.setImageSize(0, 0);
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
                && camera.getCameraInfo().hasFlashUnit()
                && !torchRequestPending && !captureInProgress);
        updateCameraActions();
    }

    private void updateCameraActions() {
        if (cameraControls == null || cameraControls.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean ready = !captureInProgress && !torchRequestPending;
        flashlightToggle.setEnabled(ready && camera != null
                && camera.getCameraInfo().hasFlashUnit());
        captureButton.setEnabled(ready && acceptedPages.size()
                < VaultItem.MAX_DOCUMENT_PAGES);
        finishButton.setEnabled(ready && !acceptedPages.isEmpty());
    }

    private void showFlashlightError(Exception exception) {
        android.util.Log.w("SPM_DOCUMENT_SCAN", "could not change torch state: "
                + exception.getClass().getSimpleName());
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, R.string.document_flash_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
