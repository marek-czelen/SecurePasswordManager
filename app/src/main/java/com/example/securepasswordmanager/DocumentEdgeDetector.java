package com.example.securepasswordmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;

import com.example.securepasswordmanager.crypto.VaultImageProcessor;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Detects a document quadrilateral and rectifies approved captures into encrypted-ready JPGs. */
final class DocumentEdgeDetector {
    private static final int MAX_DETECTION_DIMENSION = 1600;
    private static final int MAX_RECTIFIED_EDGE = DocumentGeometry.MAX_RECTIFIED_EDGE;
    private static final double DOCUMENT_EDGE_PADDING_RATIO = 0.02;

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
        Mat gray = new Mat();
        Mat detectionGray = new Mat();
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            resizeForDetection(gray, detectionGray);
            Point[] detected = findCorners(detectionGray);
            if (detected == null) {
                throw new EdgesNotFoundException();
            }
            return new CaptureCorners(scaleCorners(detected,
                    gray.cols() / (double) detectionGray.cols(),
                    gray.rows() / (double) detectionGray.rows()),
                    gray.cols(), gray.rows());
        } catch (org.opencv.core.CvException | OutOfMemoryError exception) {
            throw new IOException("Could not detect document corners", exception);
        } finally {
            source.recycle();
            rgba.release();
            gray.release();
            detectionGray.release();
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

    private static Point[] findCorners(Mat gray) {
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat dilated = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat hierarchy = new Mat();
        Mat input = null;
        List<MatOfPoint> contours = new ArrayList<>();
        MatOfInt hullIndices = new MatOfInt();
        try {
            Imgproc.GaussianBlur(gray, blurred, new Size(7, 7), 0);
            Imgproc.Canny(blurred, edges, 40, 150);
            Imgproc.dilate(edges, dilated, kernel, new Point(-1, -1), 2);
            input = dilated.clone();
            Imgproc.findContours(input, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            if (contours.isEmpty()) {
                return null;
            }
            contours.sort((first, second) -> Double.compare(
                    Imgproc.contourArea(second), Imgproc.contourArea(first)));

            MatOfPoint largestContour = contours.get(0);
            Imgproc.convexHull(largestContour, hullIndices);
            Point[] contourPoints = largestContour.toArray();
            int[] hullPointIndices = hullIndices.toArray();
            if (hullPointIndices.length == 0) {
                return null;
            }

            Point topLeft = null;
            Point topRight = null;
            Point bottomRight = null;
            Point bottomLeft = null;
            double minSum = Double.POSITIVE_INFINITY;
            double maxSum = Double.NEGATIVE_INFINITY;
            double minDifference = Double.POSITIVE_INFINITY;
            double maxDifference = Double.NEGATIVE_INFINITY;
            for (int pointIndex : hullPointIndices) {
                Point point = contourPoints[pointIndex];
                double sum = point.x + point.y;
                double difference = point.y - point.x;
                if (sum < minSum) {
                    minSum = sum;
                    topLeft = point;
                }
                if (sum > maxSum) {
                    maxSum = sum;
                    bottomRight = point;
                }
                if (difference < minDifference) {
                    minDifference = difference;
                    topRight = point;
                }
                if (difference > maxDifference) {
                    maxDifference = difference;
                    bottomLeft = point;
                }
            }
            if (topLeft == null || topRight == null
                    || bottomRight == null || bottomLeft == null) {
                return null;
            }
            return new Point[]{topLeft, topRight, bottomRight, bottomLeft};
        } finally {
            blurred.release();
            edges.release();
            dilated.release();
            kernel.release();
            hierarchy.release();
            if (input != null) {
                input.release();
            }
            for (MatOfPoint contour : contours) {
                contour.release();
            }
            hullIndices.release();
        }
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
