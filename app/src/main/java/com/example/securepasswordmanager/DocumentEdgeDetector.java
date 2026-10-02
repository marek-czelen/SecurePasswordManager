package com.example.securepasswordmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.graphics.Rect;

import com.example.securepasswordmanager.crypto.VaultImageProcessor;

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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Detects a document quadrilateral and rectifies approved captures into encrypted-ready JPGs. */
final class DocumentEdgeDetector {
    private static final double MIN_DOCUMENT_AREA = 0.10;
    private static final double FALLBACK_MIN_DOCUMENT_AREA = 0.06;
    private static final double MAX_DOCUMENT_AREA = 0.95;
    private static final int MAX_DETECTION_DIMENSION = 640;
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

    static final class CalibrationRequiredException extends IOException {
        CalibrationRequiredException() {
            super("Camera calibration is required for an unknown document aspect");
        }
    }

    private DocumentEdgeDetector() {
    }

    static Quad detectLuminance(ByteBuffer source, int width, int height,
                               int rowStride, int pixelStride, int rotationDegrees,
                               boolean allowFallback) {
        byte[] pixels = new byte[width * height];
        ByteBuffer luminance = source.duplicate();
        int bufferStart = luminance.position();
        for (int y = 0; y < height; y++) {
            int rowStart = y * rowStride;
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = luminance.get(
                        bufferStart + rowStart + x * pixelStride);
            }
        }

        Mat image = new Mat(height, width, org.opencv.core.CvType.CV_8UC1);
        Mat rotated = new Mat();
        Mat gray = new Mat();
        try {
            image.put(0, 0, pixels);
            rotate(image, rotated, rotationDegrees);
            resizeForDetection(rotated, gray);
            Point[] corners = findCorners(gray, allowFallback);
            return corners == null ? null : normalized(corners, gray.cols(), gray.rows());
        } finally {
            Arrays.fill(pixels, (byte) 0);
            image.release();
            rotated.release();
            gray.release();
        }
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

    static byte[] rectify(Context context, File sourceFile, Double requestedAspect,
                          DocumentCameraCalibration calibration, int rotationDegrees,
                          Rect activeArray, Quad previewQuad) throws IOException {
        Bitmap source = decodeCapture(context, sourceFile);
        if (source == null) {
            throw new IOException("Could not decode captured document");
        }
        Mat color = new Mat();
        Mat gray = new Mat();
        Mat detectionGray = new Mat();
        MatOfPoint2f sourceCorners = null;
        MatOfPoint2f destinationCorners = null;
        Mat transform = null;
        Mat corrected = new Mat();
        Bitmap result = null;
        try {
            Utils.bitmapToMat(source, color);
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_RGBA2GRAY);
            resizeForDetection(gray, detectionGray);
            Point[] detectedCorners = findCorners(detectionGray);
            if (detectedCorners == null && previewQuad != null) {
                detectedCorners = pointsFromNormalizedQuad(previewQuad,
                        detectionGray.cols(), detectionGray.rows());
            }
            if (detectedCorners == null) {
                throw new EdgesNotFoundException();
            }
            Point[] corners = scaleCorners(detectedCorners,
                    gray.cols() / (double) detectionGray.cols(),
                    gray.rows() / (double) detectionGray.rows());
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
            Point[] rectificationCorners = paddedCorners;
            double aspectRatio;
            DocumentCameraCalibration.ImageTransform imageTransform = null;
            if (calibration != null) {
                imageTransform = new DocumentCameraCalibration.ImageTransform(gray.cols(),
                        gray.rows(), rotationDegrees, calibration.pixelWidth,
                        calibration.pixelHeight, activeArray);
                Point[] normalizedCorners = new Point[corners.length];
                double[] normalizedCoordinates = new double[corners.length * 2];
                for (int index = 0; index < corners.length; index++) {
                    Point sensorPoint = imageTransform.toSensor(corners[index]);
                    normalizedCorners[index] =
                            calibration.undistortNormalized(sensorPoint);
                    normalizedCoordinates[index * 2] = normalizedCorners[index].x;
                    normalizedCoordinates[index * 2 + 1] = normalizedCorners[index].y;
                }
                double calibratedAspect =
                        DocumentGeometry.metricAspectRatio(normalizedCoordinates);
                if (requestedAspect != null) {
                    double landscapeAspect = Math.max(requestedAspect, 1d / requestedAspect);
                    aspectRatio = calibratedAspect >= 1d
                            ? landscapeAspect : 1d / landscapeAspect;
                } else {
                    aspectRatio = calibratedAspect;
                }
                rectificationCorners = new Point[paddedCorners.length];
                for (int index = 0; index < paddedCorners.length; index++) {
                    Point sensorPoint = imageTransform.toSensor(paddedCorners[index]);
                    Point normalized = calibration.undistortNormalized(sensorPoint);
                    rectificationCorners[index] = imageTransform.toUpright(
                            calibration.sensorPixelFromNormalized(normalized));
                }
            } else {
                if (requestedAspect == null) {
                    throw new CalibrationRequiredException();
                }
                aspectRatio = DocumentGeometry.orientAspectRatio(requestedAspect, corners);
            }

            int[] dimensions = DocumentGeometry.outputDimensions(
                    paddedHorizontalEdge, paddedVerticalEdge, aspectRatio);
            int outputWidth = dimensions[0];
            int outputHeight = dimensions[1];
            sourceCorners = new MatOfPoint2f(rectificationCorners);
            destinationCorners = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(outputWidth - 1, 0),
                    new Point(outputWidth - 1, outputHeight - 1),
                    new Point(0, outputHeight - 1));
            if (calibration == null) {
                transform = Imgproc.getPerspectiveTransform(sourceCorners, destinationCorners);
                Imgproc.warpPerspective(color, corrected, transform,
                        new Size(outputWidth, outputHeight), Imgproc.INTER_CUBIC,
                        Core.BORDER_REPLICATE, new Scalar(255, 255, 255, 255));
            } else {
                transform = Imgproc.getPerspectiveTransform(destinationCorners, sourceCorners);
                remapUndistortedDocument(color, corrected, transform, outputWidth, outputHeight,
                        calibration, imageTransform);
            }
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
            gray.release();
            detectionGray.release();
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

    private static void remapUndistortedDocument(Mat source, Mat destination, Mat destinationToSource,
                                                  int outputWidth, int outputHeight,
                                                  DocumentCameraCalibration calibration,
                                                  DocumentCameraCalibration.ImageTransform imageTransform) {
        destination.create(outputHeight, outputWidth, source.type());
        double[][] perspective = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                perspective[row][column] = destinationToSource.get(row, column)[0];
            }
        }
        double inverseFx = 1d / calibration.fx;
        double inverseFy = 1d / calibration.fy;
        double[] d = calibration.distortion;
        final int tileHeight = 96;
        for (int top = 0; top < outputHeight; top += tileHeight) {
            int rows = Math.min(tileHeight, outputHeight - top);
            Mat mapX = new Mat(rows, outputWidth, CvType.CV_32FC1);
            Mat mapY = new Mat(rows, outputWidth, CvType.CV_32FC1);
            Mat tile = new Mat();
            Mat target = destination.rowRange(top, top + rows);
            try {
                float[] xValues = new float[rows * outputWidth];
                float[] yValues = new float[rows * outputWidth];
                for (int y = 0; y < rows; y++) {
                    double destinationY = top + y;
                    for (int x = 0; x < outputWidth; x++) {
                        double denominator = perspective[2][0] * x
                                + perspective[2][1] * destinationY + perspective[2][2];
                        double uprightX = (perspective[0][0] * x
                                + perspective[0][1] * destinationY + perspective[0][2])
                                / denominator;
                        double uprightY = (perspective[1][0] * x
                                + perspective[1][1] * destinationY + perspective[1][2])
                                / denominator;
                        double idealSensorX = imageTransform.sensorXFromUpright(
                                uprightX, uprightY);
                        double idealSensorY = imageTransform.sensorYFromUpright(
                                uprightX, uprightY);
                        double normalizedY = (idealSensorY - calibration.cy) * inverseFy;
                        double normalizedX = (idealSensorX - calibration.cx
                                - calibration.skew * normalizedY) * inverseFx;
                        double radius2 = normalizedX * normalizedX
                                + normalizedY * normalizedY;
                        double radius4 = radius2 * radius2;
                        double radial = 1d + d[0] * radius2 + d[1] * radius4
                                + d[4] * radius4 * radius2;
                        double distortedX = normalizedX * radial
                                + 2d * d[2] * normalizedX * normalizedY
                                + d[3] * (radius2 + 2d * normalizedX * normalizedX);
                        double distortedY = normalizedY * radial
                                + d[2] * (radius2 + 2d * normalizedY * normalizedY)
                                + 2d * d[3] * normalizedX * normalizedY;
                        double distortedSensorX = calibration.fx * distortedX
                                + calibration.skew * distortedY + calibration.cx;
                        double distortedSensorY = calibration.fy * distortedY + calibration.cy;
                        int offset = y * outputWidth + x;
                        xValues[offset] = (float) imageTransform.uprightXFromSensor(
                                distortedSensorX, distortedSensorY);
                        yValues[offset] = (float) imageTransform.uprightYFromSensor(
                                distortedSensorX, distortedSensorY);
                    }
                }
                mapX.put(0, 0, xValues);
                mapY.put(0, 0, yValues);
                Imgproc.remap(source, tile, mapX, mapY, Imgproc.INTER_CUBIC,
                        Core.BORDER_REPLICATE, new Scalar(255, 255, 255, 255));
                tile.copyTo(target);
            } finally {
                target.release();
                mapX.release();
                mapY.release();
                tile.release();
            }
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
        return findCorners(gray, true);
    }

    private static Point[] findCorners(Mat gray, boolean allowFallback) {
        Point[] detected = findCannyCorners(gray, 45, 130, MIN_DOCUMENT_AREA,
                new double[]{0.02}, false, false, Imgproc.RETR_EXTERNAL);
        if (detected != null) {
            return detected;
        }
        if (!allowFallback) {
            return null;
        }
        detected = findCannyCorners(gray, 18, 75, FALLBACK_MIN_DOCUMENT_AREA,
                new double[]{0.02, 0.04, 0.06}, true, true, Imgproc.RETR_EXTERNAL);
        if (detected != null) {
            return detected;
        }
        return findAdaptiveCorners(gray);
    }

    private static Point[] findCannyCorners(Mat gray, double lowThreshold,
                                            double highThreshold, double minAreaFraction,
                                            double[] approximationFactors, boolean closeEdges,
                                            boolean useBoundingRectangle, int retrievalMode) {
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat closedEdges = new Mat();
        Mat kernel = null;
        try {
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Imgproc.Canny(blurred, edges, lowThreshold, highThreshold);
            Mat contourImage = edges;
            if (closeEdges) {
                kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
                Imgproc.morphologyEx(edges, closedEdges, Imgproc.MORPH_CLOSE, kernel);
                contourImage = closedEdges;
            }
            return findCornersFromContours(contourImage, minAreaFraction,
                    approximationFactors, retrievalMode, useBoundingRectangle);
        } finally {
            blurred.release();
            edges.release();
            closedEdges.release();
            if (kernel != null) {
                kernel.release();
            }
        }
    }

    private static Point[] findAdaptiveCorners(Mat gray) {
        Mat blurred = new Mat();
        Mat thresholded = new Mat();
        Mat closed = new Mat();
        Mat kernel = null;
        try {
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Imgproc.adaptiveThreshold(blurred, thresholded, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 7);
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
            Imgproc.morphologyEx(thresholded, closed, Imgproc.MORPH_CLOSE, kernel);
            return findCornersFromContours(closed, FALLBACK_MIN_DOCUMENT_AREA,
                    new double[]{0.03, 0.05, 0.07}, Imgproc.RETR_EXTERNAL, true);
        } finally {
            blurred.release();
            thresholded.release();
            closed.release();
            if (kernel != null) {
                kernel.release();
            }
        }
    }

    private static Point[] findCornersFromContours(Mat contourImage,
                                                    double minAreaFraction,
                                                    double[] approximationFactors,
                                                    int retrievalMode,
                                                    boolean useBoundingRectangle) {
        Mat input = contourImage.clone();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        Point[] best = null;
        double largestArea = 0;
        try {
            Imgproc.findContours(input, contours, hierarchy,
                    retrievalMode, Imgproc.CHAIN_APPROX_SIMPLE);
            double imageArea = contourImage.cols() * (double) contourImage.rows();
            for (MatOfPoint contour : contours) {
                double area = Math.abs(Imgproc.contourArea(contour));
                double fraction = area / imageArea;
                if (fraction < minAreaFraction || fraction > MAX_DOCUMENT_AREA
                        || area <= largestArea) {
                    continue;
                }
                MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
                MatOfPoint2f approximation = new MatOfPoint2f();
                try {
                    double perimeter = Imgproc.arcLength(curve, true);
                    boolean accepted = false;
                    for (double factor : approximationFactors) {
                        Imgproc.approxPolyDP(curve, approximation, perimeter * factor, true);
                        if (approximation.total() != 4) {
                            continue;
                        }
                        Point[] ordered = order(approximation.toArray());
                        if (isValidDocumentQuad(ordered, contourImage.cols(),
                                contourImage.rows())) {
                            best = ordered;
                            largestArea = area;
                            accepted = true;
                            break;
                        }
                    }
                    if (!accepted && useBoundingRectangle) {
                        org.opencv.core.RotatedRect bounds = Imgproc.minAreaRect(curve);
                        double rectangleArea = bounds.size.width * bounds.size.height;
                        if (rectangleArea > 0 && area / rectangleArea >= 0.55) {
                            Point[] rectangle = new Point[4];
                            bounds.points(rectangle);
                            Point[] ordered = order(rectangle);
                            if (isValidDocumentQuad(ordered, contourImage.cols(),
                                    contourImage.rows())) {
                                best = ordered;
                                largestArea = area;
                            }
                        }
                    }
                } finally {
                    curve.release();
                    approximation.release();
                }
            }
            return best;
        } finally {
            for (MatOfPoint contour : contours) {
                contour.release();
            }
            input.release();
            hierarchy.release();
        }
    }

    private static boolean isValidDocumentQuad(Point[] points, int width, int height) {
        MatOfPoint polygon = new MatOfPoint(points);
        try {
            return Imgproc.isContourConvex(polygon) && !touchesFrame(points, width, height);
        } finally {
            polygon.release();
        }
    }

    private static Point[] pointsFromNormalizedQuad(Quad quad, int width, int height) {
        if (quad.points == null || quad.points.length != 8 || width <= 0 || height <= 0) {
            return null;
        }
        Point[] points = new Point[4];
        for (int index = 0; index < points.length; index++) {
            float x = quad.points[index * 2];
            float y = quad.points[index * 2 + 1];
            if (!Float.isFinite(x) || !Float.isFinite(y)
                    || x < 0 || x > 1 || y < 0 || y > 1) {
                return null;
            }
            points[index] = new Point(x * width, y * height);
        }
        return points;
    }

    private static boolean touchesFrame(Point[] points, int width, int height) {
        double marginX = width * 0.01;
        double marginY = height * 0.01;
        for (Point point : points) {
            if (point.x <= marginX || point.y <= marginY
                    || point.x >= width - marginX || point.y >= height - marginY) {
                return true;
            }
        }
        return false;
    }

    private static Point[] order(Point[] points) {
        double sumX = 0;
        double sumY = 0;
        for (Point point : points) {
            sumX += point.x;
            sumY += point.y;
        }
        double centerX = sumX / points.length;
        double centerY = sumY / points.length;
        Point[] ordered = points.clone();
        Arrays.sort(ordered, (first, second) -> Double.compare(
                Math.atan2(first.y - centerY, first.x - centerX),
                Math.atan2(second.y - centerY, second.x - centerX)));
        int start = 0;
        double minSum = Double.MAX_VALUE;
        for (int index = 0; index < ordered.length; index++) {
            double sum = ordered[index].x + ordered[index].y;
            if (sum < minSum) {
                minSum = sum;
                start = index;
            }
        }
        return new Point[]{ordered[start], ordered[(start + 1) % 4],
                ordered[(start + 2) % 4], ordered[(start + 3) % 4]};
    }

    private static Quad normalized(Point[] points, int width, int height) {
        float[] values = new float[8];
        for (int index = 0; index < points.length; index++) {
            values[index * 2] = (float) (points[index].x / width);
            values[index * 2 + 1] = (float) (points[index].y / height);
        }
        return new Quad(values, width / (float) height);
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

    private static void rotate(Mat source, Mat destination, int degrees) {
        switch (degrees) {
            case 90:
                Core.rotate(source, destination, Core.ROTATE_90_CLOCKWISE);
                break;
            case 180:
                Core.rotate(source, destination, Core.ROTATE_180);
                break;
            case 270:
                Core.rotate(source, destination, Core.ROTATE_90_COUNTERCLOCKWISE);
                break;
            default:
                source.copyTo(destination);
        }
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        void wipe() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
