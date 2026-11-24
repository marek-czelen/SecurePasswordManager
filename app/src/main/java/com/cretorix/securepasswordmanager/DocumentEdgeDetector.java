package com.cretorix.securepasswordmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;

import com.cretorix.securepasswordmanager.crypto.VaultImageProcessor;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Detects a document quadrilateral and rectifies approved captures into encrypted-ready JPGs. */
final class DocumentEdgeDetector {
    private static final int MAX_DETECTION_DIMENSION = 1600;
    private static final int MODEL_INPUT_DIMENSION = 256;
    private static final int MODEL_CHANNELS = 3;
    private static final double HEATMAP_THRESHOLD = 0.3d;
    private static final String MODEL_ASSET =
            "docaligner/lcnet100_h_e_bifpn_256_fp32.onnx";
    private static final String MODEL_INPUT_NAME = "img";
    private static final String MODEL_OUTPUT_NAME = "heatmap";
    private static final int MAX_RECTIFIED_EDGE = DocumentGeometry.MAX_RECTIFIED_EDGE;
    private static final double DOCUMENT_EDGE_PADDING_RATIO = 0.02;
    private static volatile OrtSession detectorSession;

    static final class Quad {
        final float[] points;
        final float aspectRatio;

        Quad(float[] points, float aspectRatio) {
            this.points = points;
            this.aspectRatio = aspectRatio;
        }
    }

    static final class CaptureCorners {
        final Point[] points;
        final int width;
        final int height;

        CaptureCorners(Point[] points, int width, int height) {
            this.points = points;
            this.width = width;
            this.height = height;
        }
    }

    static final class EdgesNotFoundException extends IOException {
        EdgesNotFoundException() {
            super("Document edges were not detected");
        }
    }

    private DocumentEdgeDetector() {
    }

    static CaptureCorners detectCaptureCorners(Context context, File sourceFile)
            throws IOException {
        Bitmap source = decodeCapture(context, sourceFile);
        if (source == null) {
            throw new IOException("Could not decode captured document");
        }
        Mat rgba = new Mat();
        Mat bgr = new Mat();
        Mat detectionBgr = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            resizeForDetection(bgr, detectionBgr);
            Point[] detected = findCorners(context, bgr,
                    detectionBgr.cols(), detectionBgr.rows());
            if (detected == null) {
                throw new EdgesNotFoundException();
            }
            return new CaptureCorners(scaleCorners(detected,
                    bgr.cols() / (double) detectionBgr.cols(),
                    bgr.rows() / (double) detectionBgr.rows()),
                    bgr.cols(), bgr.rows());
        } catch (org.opencv.core.CvException | OutOfMemoryError exception) {
            throw new IOException("Could not detect document corners", exception);
        } finally {
            source.recycle();
            rgba.release();
            bgr.release();
            detectionBgr.release();
        }
    }

    static byte[] rectify(Context context, File sourceFile, double requestedAspect,
                          float[] normalizedCorners) throws IOException {
        Bitmap source = decodeCapture(context, sourceFile);
        if (source == null) {
            throw new IOException("Could not decode captured document");
        }
        Mat color = new Mat();
        MatOfPoint2f sourceCorners = null;
        MatOfPoint2f destinationCorners = null;
        Mat transform = null;
        Mat corrected = new Mat();
        Bitmap result = null;
        try {
            Utils.bitmapToMat(source, color);
            Point[] corners = pointsFromNormalizedCorners(normalizedCorners,
                    color.cols(), color.rows());
            if (corners == null) {
                throw new EdgesNotFoundException();
            }
            double horizontalEdge = (distance(corners[0], corners[1])
                    + distance(corners[3], corners[2])) / 2d;
            double verticalEdge = (distance(corners[0], corners[3])
                    + distance(corners[1], corners[2])) / 2d;
            double edgePadding = Math.max(3d,
                    Math.min(horizontalEdge, verticalEdge) * DOCUMENT_EDGE_PADDING_RATIO);
            Point[] paddedCorners = DocumentGeometry.expandQuadrilateral(corners, edgePadding);
            double paddedHorizontalEdge = (distance(paddedCorners[0], paddedCorners[1])
                    + distance(paddedCorners[3], paddedCorners[2])) / 2d;
            double paddedVerticalEdge = (distance(paddedCorners[0], paddedCorners[3])
                    + distance(paddedCorners[1], paddedCorners[2])) / 2d;
            double aspectRatio = DocumentGeometry.orientAspectRatio(requestedAspect, corners);

            int[] dimensions = DocumentGeometry.outputDimensions(
                    paddedHorizontalEdge, paddedVerticalEdge, aspectRatio);
            int outputWidth = dimensions[0];
            int outputHeight = dimensions[1];
            sourceCorners = new MatOfPoint2f(paddedCorners);
            destinationCorners = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(outputWidth - 1, 0),
                    new Point(outputWidth - 1, outputHeight - 1),
                    new Point(0, outputHeight - 1));
            transform = Imgproc.getPerspectiveTransform(sourceCorners, destinationCorners);
            Imgproc.warpPerspective(color, corrected, transform,
                    new Size(outputWidth, outputHeight), Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE, new Scalar(255, 255, 255, 255));
            result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(corrected, result);
            return encodeBoundedJpeg(result);
        } catch (org.opencv.core.CvException | OutOfMemoryError exception) {
            throw new IOException("Could not rectify document page", exception);
        } finally {
            source.recycle();
            if (result != null) {
                result.recycle();
            }
            color.release();
            if (sourceCorners != null) {
                sourceCorners.release();
            }
            if (destinationCorners != null) {
                destinationCorners.release();
            }
            if (transform != null) {
                transform.release();
            }
            corrected.release();
        }
    }

    private static Bitmap decodeCapture(Context context, File sourceFile) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(),
                Uri.fromFile(sourceFile));
        try {
            return ImageDecoder.decodeBitmap(source, (decoder, info, unused) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (largest / sample > MAX_RECTIFIED_EDGE) {
                    sample *= 2;
                }
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample);
                }
            });
        } catch (RuntimeException | OutOfMemoryError exception) {
            throw new IOException("Could not decode captured document", exception);
        }
    }

    private static byte[] encodeBoundedJpeg(Bitmap bitmap) throws IOException {
        Bitmap current = bitmap;
        boolean recycleCurrent = false;
        try {
            for (int scaleAttempt = 0; scaleAttempt < 5; scaleAttempt++) {
                for (int quality : new int[]{90, 82, 74}) {
                    WipingByteArrayOutputStream output = new WipingByteArrayOutputStream();
                    try {
                        if (!current.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            throw new IOException("Could not encode rectified document page");
                        }
                        byte[] encoded = output.toByteArray();
                        if (encoded.length <= VaultImageProcessor.MAX_IMAGE_BYTES) {
                            return encoded;
                        }
                        Arrays.fill(encoded, (byte) 0);
                    } finally {
                        output.wipe();
                    }
                }
                if (Math.max(current.getWidth(), current.getHeight()) <= 1200) {
                    break;
                }
                int[] scaledDimensions = DocumentGeometry.scaleDimensions(
                        current.getWidth(), current.getHeight(), 0.8d);
                Bitmap scaled = Bitmap.createScaledBitmap(current,
                        scaledDimensions[0], scaledDimensions[1], true);
                if (recycleCurrent) {
                    current.recycle();
                }
                current = scaled;
                recycleCurrent = current != bitmap;
            }
            throw new IOException("Rectified document page exceeds the attachment limit");
        } finally {
            if (recycleCurrent) {
                current.recycle();
            }
        }
    }

    private static Point[] findCorners(Context context, Mat bgr, int targetWidth,
                                       int targetHeight) throws IOException {
        Mat modelInput = new Mat();
        OnnxTensor inputTensor = null;
        float[] inputValues = null;
        try {
            Imgproc.resize(bgr, modelInput,
                    new Size(MODEL_INPUT_DIMENSION, MODEL_INPUT_DIMENSION));
            inputValues = toNchwBgr(modelInput);
            inputTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                    FloatBuffer.wrap(inputValues),
                    new long[]{1, MODEL_CHANNELS, MODEL_INPUT_DIMENSION, MODEL_INPUT_DIMENSION});
            OrtSession session = getDetectorSession(context);
            try (OrtSession.Result result = session.run(
                    Collections.singletonMap(MODEL_INPUT_NAME, inputTensor))) {
                OnnxValue value = result.get(MODEL_OUTPUT_NAME).orElse(null);
                if (!(value instanceof OnnxTensor)) {
                    throw new IOException("DocAligner did not return document heatmaps");
                }
                OnnxTensor heatmapTensor = (OnnxTensor) value;
                TensorInfo info = heatmapTensor.getInfo();
                long[] shape = info.getShape();
                if (shape.length != 4 || shape[0] != 1 || shape[1] != 4
                        || shape[2] <= 0 || shape[3] <= 0
                        || shape[2] > Integer.MAX_VALUE || shape[3] > Integer.MAX_VALUE) {
                    throw new IOException("DocAligner returned an unsupported heatmap shape");
                }
                int heatmapHeight = (int) shape[2];
                int heatmapWidth = (int) shape[3];
                long heatmapSize = (long) shape[1] * heatmapHeight * heatmapWidth;
                FloatBuffer output = heatmapTensor.getFloatBuffer();
                if (heatmapSize > Integer.MAX_VALUE || output.remaining() != heatmapSize) {
                    throw new IOException("DocAligner returned invalid heatmap data");
                }
                float[] heatmaps = new float[(int) heatmapSize];
                output.get(heatmaps);
                try {
                    return postprocessHeatmaps(heatmaps, heatmapWidth, heatmapHeight,
                            targetWidth, targetHeight);
                } finally {
                    Arrays.fill(heatmaps, 0f);
                }
            }
        } catch (OrtException | org.opencv.core.CvException | OutOfMemoryError exception) {
            throw new IOException("Could not run DocAligner document detection", exception);
        } finally {
            if (inputTensor != null) {
                inputTensor.close();
            }
            if (inputValues != null) {
                Arrays.fill(inputValues, 0f);
            }
            modelInput.release();
        }
    }

    private static OrtSession getDetectorSession(Context context) throws IOException {
        OrtSession current = detectorSession;
        if (current != null) {
            return current;
        }
        synchronized (DocumentEdgeDetector.class) {
            if (detectorSession != null) {
                return detectorSession;
            }
            byte[] model = null;
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                model = readModelAsset(context);
                detectorSession = OrtEnvironment.getEnvironment()
                        .createSession(model, options);
                return detectorSession;
            } catch (IOException | OrtException | OutOfMemoryError exception) {
                throw new IOException("Could not load DocAligner document model", exception);
            } finally {
                if (model != null) {
                    Arrays.fill(model, (byte) 0);
                }
            }
        }
    }

    private static byte[] readModelAsset(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(MODEL_ASSET);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private static float[] toNchwBgr(Mat bgr) throws IOException {
        int pixelCount = bgr.rows() * bgr.cols();
        byte[] pixels = new byte[pixelCount * MODEL_CHANNELS];
        float[] values = null;
        try {
            values = new float[pixelCount * MODEL_CHANNELS];
            int bytesRead = bgr.get(0, 0, pixels);
            if (bytesRead != pixels.length) {
                throw new IOException("Could not prepare DocAligner input image");
            }
            int greenOffset = pixelCount;
            int blueOffset = pixelCount * 2;
            for (int index = 0; index < pixelCount; index++) {
                int pixelOffset = index * MODEL_CHANNELS;
                values[index] = (pixels[pixelOffset] & 0xff) / 255f;
                values[greenOffset + index] = (pixels[pixelOffset + 1] & 0xff) / 255f;
                values[blueOffset + index] = (pixels[pixelOffset + 2] & 0xff) / 255f;
            }
            return values;
        } finally {
            Arrays.fill(pixels, (byte) 0);
        }
    }

    private static Point[] postprocessHeatmaps(float[] heatmaps, int heatmapWidth,
                                               int heatmapHeight, int targetWidth,
                                               int targetHeight) throws IOException {
        Point[] corners = new Point[4];
        int heatmapSize = heatmapWidth * heatmapHeight;
        for (int channel = 0; channel < corners.length; channel++) {
            float[] channelHeatmap = new float[heatmapSize];
            System.arraycopy(heatmaps, channel * heatmapSize,
                    channelHeatmap, 0, heatmapSize);
            int targetSize = targetWidth * targetHeight;
            float[] resizedValues = new float[targetSize];
            byte[] binaryInput = new byte[targetSize];
            Mat heatmap = null;
            Mat resized = null;
            Mat mask = null;
            Mat binary = null;
            Mat hierarchy = null;
            List<MatOfPoint> contours = new ArrayList<>();
            try {
                heatmap = new Mat(heatmapHeight, heatmapWidth, CvType.CV_32FC1);
                resized = new Mat();
                mask = new Mat();
                binary = new Mat();
                hierarchy = new Mat();
                heatmap.put(0, 0, channelHeatmap);
                Imgproc.resize(heatmap, resized, new Size(targetWidth, targetHeight));
                resized.get(0, 0, resizedValues);
                for (int index = 0; index < targetSize; index++) {
                    float value = resizedValues[index];
                    if (value < HEATMAP_THRESHOLD) {
                        value = 0f;
                    }
                    binaryInput[index] = (byte) (int) (value * 255f);
                }
                mask.create(targetHeight, targetWidth, CvType.CV_8UC1);
                mask.put(0, 0, binaryInput);
                Imgproc.threshold(mask, binary, 0d, 255d,
                        Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
                Imgproc.findContours(binary, contours, hierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

                MatOfPoint largestContour = null;
                double largestArea = 0d;
                int largestContourCount = 0;
                for (MatOfPoint contour : contours) {
                    double area = Imgproc.contourArea(contour);
                    if (area > largestArea) {
                        largestArea = area;
                        largestContour = contour;
                        largestContourCount = 1;
                    } else if (area > 0d && area == largestArea) {
                        largestContourCount++;
                    }
                }
                if (largestContour == null || largestContourCount != 1) {
                    return null;
                }
                Moments moments = Imgproc.moments(largestContour);
                if (moments.get_m00() <= 0d) {
                    return null;
                }
                corners[channel] = new Point(moments.get_m10() / (moments.get_m00() + 1e-5d),
                        moments.get_m01() / (moments.get_m00() + 1e-5d));
            } finally {
                if (heatmap != null) {
                    heatmap.release();
                }
                if (resized != null) {
                    resized.release();
                }
                if (mask != null) {
                    mask.release();
                }
                if (binary != null) {
                    binary.release();
                }
                if (hierarchy != null) {
                    hierarchy.release();
                }
                for (MatOfPoint contour : contours) {
                    contour.release();
                }
                Arrays.fill(resizedValues, 0f);
                Arrays.fill(binaryInput, (byte) 0);
                Arrays.fill(channelHeatmap, 0f);
            }
        }
        return corners;
    }

    private static Point[] pointsFromNormalizedCorners(float[] normalizedCorners,
                                                       int width, int height) {
        if (normalizedCorners == null || normalizedCorners.length != 8
                || width <= 0 || height <= 0) {
            return null;
        }
        Point[] points = new Point[4];
        for (int index = 0; index < points.length; index++) {
            float x = normalizedCorners[index * 2];
            float y = normalizedCorners[index * 2 + 1];
            if (!Float.isFinite(x) || !Float.isFinite(y)
                    || x < 0 || x > 1 || y < 0 || y > 1) {
                return null;
            }
            points[index] = new Point(x * width, y * height);
        }
        return points;
    }

    private static void resizeForDetection(Mat source, Mat destination) {
        int largestDimension = Math.max(source.cols(), source.rows());
        double scale = Math.min(1d, MAX_DETECTION_DIMENSION / (double) largestDimension);
        int width = Math.max(1, (int) Math.round(source.cols() * scale));
        int height = Math.max(1, (int) Math.round(source.rows() * scale));
        if (width == source.cols() && height == source.rows()) {
            source.copyTo(destination);
        } else {
            Imgproc.resize(source, destination, new Size(width, height));
        }
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x - second.x, first.y - second.y);
    }

    private static Point[] scaleCorners(Point[] corners, double scaleX, double scaleY) {
        Point[] scaled = new Point[corners.length];
        for (int index = 0; index < corners.length; index++) {
            scaled[index] = new Point(corners[index].x * scaleX,
                    corners[index].y * scaleY);
        }
        return scaled;
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        void wipe() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
