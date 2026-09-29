package com.example.securepasswordmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.util.Base64;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Camera intrinsics in the sensor pixel-array coordinate system. */
final class DocumentCameraCalibration {
    private static final String PREFERENCES = "document_camera_calibration";
    private static final String PREFIX = "camera_";
    static final double ID1_WIDTH_MM = 85.60;
    static final double ID1_HEIGHT_MM = 53.98;
    static final int REQUIRED_VIEWS = 6;

    final String cameraId;
    final int pixelWidth;
    final int pixelHeight;
    final double fx;
    final double fy;
    final double cx;
    final double cy;
    final double skew;
    final double[] distortion;
    final double reprojectionError;

    DocumentCameraCalibration(String cameraId, int pixelWidth, int pixelHeight,
                              double fx, double fy, double cx, double cy, double skew,
                              double[] distortion, double reprojectionError) {
        this.cameraId = cameraId;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.fx = fx;
        this.fy = fy;
        this.cx = cx;
        this.cy = cy;
        this.skew = skew;
        this.distortion = distortion == null ? new double[5] : distortion.clone();
        this.reprojectionError = reprojectionError;
    }

    static DocumentCameraCalibration fromCamera2(String cameraId, int pixelWidth,
                                                  int pixelHeight, float[] intrinsic,
                                                  float[] androidDistortion) {
        if (intrinsic == null || intrinsic.length < 4
                || pixelWidth <= 0 || pixelHeight <= 0) {
            return null;
        }
        double[] coefficients = new double[5];
        if (androidDistortion != null && androidDistortion.length >= 5) {
            coefficients[0] = androidDistortion[0];
            coefficients[1] = androidDistortion[1];
            coefficients[2] = androidDistortion[3];
            coefficients[3] = androidDistortion[4];
            coefficients[4] = androidDistortion[2];
        }
        DocumentCameraCalibration result = new DocumentCameraCalibration(cameraId,
                pixelWidth, pixelHeight, intrinsic[0], intrinsic[1], intrinsic[2],
                intrinsic[3], intrinsic.length > 4 ? intrinsic[4] : 0,
                coefficients, 0);
        return result.isValid() ? result : null;
    }

    static DocumentCameraCalibration load(Context context, String cameraId,
                                          int pixelWidth, int pixelHeight) {
        if (cameraId == null) {
            return null;
        }
        String key = key(cameraId);
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES,
                Context.MODE_PRIVATE);
        try {
            int storedWidth = preferences.getInt(key + "_width", 0);
            int storedHeight = preferences.getInt(key + "_height", 0);
            if (storedWidth != pixelWidth || storedHeight != pixelHeight) {
                return null;
            }
            String[] values = preferences.getString(key + "_values", "").split(",");
            if (values.length != 11) {
                return null;
            }
            double[] parsed = new double[values.length];
            for (int index = 0; index < values.length; index++) {
                parsed[index] = Double.parseDouble(values[index]);
            }
            DocumentCameraCalibration calibration = new DocumentCameraCalibration(cameraId,
                    pixelWidth, pixelHeight, parsed[0], parsed[1], parsed[2], parsed[3],
                    parsed[4], Arrays.copyOfRange(parsed, 5, 10), parsed[10]);
            return calibration.isValid() ? calibration : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    void save(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES,
                Context.MODE_PRIVATE);
        String key = key(cameraId);
        StringBuilder values = new StringBuilder();
        append(values, fx);
        append(values, fy);
        append(values, cx);
        append(values, cy);
        append(values, skew);
        for (double coefficient : distortion) {
            append(values, coefficient);
        }
        append(values, reprojectionError);
        preferences.edit()
                .putInt(key + "_width", pixelWidth)
                .putInt(key + "_height", pixelHeight)
                .putString(key + "_values", values.toString())
                .apply();
    }

    static DocumentCameraCalibration calibrate(String cameraId, int pixelWidth,
                                                int pixelHeight, List<Point[]> objectViews,
                                                List<Point[]> imageViews) {
        if (objectViews == null || imageViews == null
                || objectViews.size() != imageViews.size()
                || objectViews.size() < REQUIRED_VIEWS
                || pixelWidth <= 0 || pixelHeight <= 0) {
            throw new IllegalArgumentException("More varied calibration views are required");
        }

        List<Mat> objectPoints = new ArrayList<>();
        List<Mat> imagePoints = new ArrayList<>();
        List<Mat> rotations = new ArrayList<>();
        List<Mat> translations = new ArrayList<>();
        Mat cameraMatrix = Mat.zeros(3, 3, CvType.CV_64F);
        Mat distortion = Mat.zeros(5, 1, CvType.CV_64F);
        try {
            double initialFocal = Math.max(pixelWidth, pixelHeight);
            cameraMatrix.put(0, 0, initialFocal);
            cameraMatrix.put(1, 1, initialFocal);
            cameraMatrix.put(0, 2, pixelWidth / 2d);
            cameraMatrix.put(1, 2, pixelHeight / 2d);
            cameraMatrix.put(2, 2, 1d);

            for (int view = 0; view < objectViews.size(); view++) {
                Point[] object = objectViews.get(view);
                Point[] image = imageViews.get(view);
                if (object == null || image == null || object.length != 4 || image.length != 4) {
                    throw new IllegalArgumentException("Each view must contain four corners");
                }
                Point3[] object3d = new Point3[4];
                for (int index = 0; index < 4; index++) {
                    object3d[index] = new Point3(object[index].x, object[index].y, 0);
                }
                objectPoints.add(new MatOfPoint3f(object3d));
                imagePoints.add(new MatOfPoint2f(image));
            }

            double rms = Calib3d.calibrateCamera(objectPoints, imagePoints,
                    new Size(pixelWidth, pixelHeight), cameraMatrix, distortion,
                    rotations, translations, Calib3d.CALIB_USE_INTRINSIC_GUESS
                            | Calib3d.CALIB_FIX_K3,
                    new TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 100, 1e-6));
            double[] coefficients = new double[5];
            for (int index = 0; index < coefficients.length; index++) {
                coefficients[index] = distortion.get(index, 0)[0];
            }
            DocumentCameraCalibration result = new DocumentCameraCalibration(cameraId,
                    pixelWidth, pixelHeight, cameraMatrix.get(0, 0)[0],
                    cameraMatrix.get(1, 1)[0], cameraMatrix.get(0, 2)[0],
                    cameraMatrix.get(1, 2)[0], cameraMatrix.get(0, 1)[0],
                    coefficients, rms);
            if (!result.isValid()
                    || !passesReprojectionValidation(rms, pixelWidth, pixelHeight)) {
                throw new IllegalArgumentException("Calibration reprojection error is too high");
            }
            return result;
        } finally {
            for (Mat point : objectPoints) {
                point.release();
            }
            for (Mat point : imagePoints) {
                point.release();
            }
            for (Mat point : rotations) {
                point.release();
            }
            for (Mat point : translations) {
                point.release();
            }
            cameraMatrix.release();
            distortion.release();
        }
    }

    boolean isValid() {
        if (cameraId == null || pixelWidth <= 0 || pixelHeight <= 0
                || !finitePositive(fx) || !finitePositive(fy)
                || !Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(skew)
                || distortion.length < 5 || !Double.isFinite(reprojectionError)) {
            return false;
        }
        double maxDimension = Math.max(pixelWidth, pixelHeight);
        if (fx < maxDimension * 0.2 || fx > maxDimension * 6
                || fy < maxDimension * 0.2 || fy > maxDimension * 6
                || cx < -pixelWidth * 0.2 || cx > pixelWidth * 1.2
                || cy < -pixelHeight * 0.2 || cy > pixelHeight * 1.2) {
            return false;
        }
        for (int index = 0; index < 5; index++) {
            if (!Double.isFinite(distortion[index]) || Math.abs(distortion[index]) > 10) {
                return false;
            }
        }
        return true;
    }

    static boolean passesReprojectionValidation(double rms, int pixelWidth, int pixelHeight) {
        if (!Double.isFinite(rms) || rms < 0 || pixelWidth <= 0 || pixelHeight <= 0) {
            return false;
        }
        return rms <= Math.max(4d, Math.max(pixelWidth, pixelHeight) * 0.004d);
    }

    Point undistortNormalized(Point distortedPixel) {
        double distortedY = (distortedPixel.y - cy) / fy;
        double distortedX = (distortedPixel.x - cx - skew * distortedY) / fx;
        double x = distortedX;
        double y = distortedY;
        for (int iteration = 0; iteration < 15; iteration++) {
            double radius2 = x * x + y * y;
            double radius4 = radius2 * radius2;
            double radial = 1d + distortion[0] * radius2 + distortion[1] * radius4
                    + distortion[4] * radius4 * radius2;
            double projectedX = x * radial + 2d * distortion[2] * x * y
                    + distortion[3] * (radius2 + 2d * x * x);
            double projectedY = y * radial + distortion[2] * (radius2 + 2d * y * y)
                    + 2d * distortion[3] * x * y;
            x += distortedX - projectedX;
            y += distortedY - projectedY;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Could not undistort camera point");
        }
        return new Point(x, y);
    }

    Point sensorPixelFromNormalized(Point normalized) {
        return new Point(fx * normalized.x + skew * normalized.y + cx,
                fy * normalized.y + cy);
    }

    static Point[] calibrationObjectCorners(boolean firstEdgeIsLong) {
        double first = firstEdgeIsLong ? ID1_WIDTH_MM : ID1_HEIGHT_MM;
        double second = firstEdgeIsLong ? ID1_HEIGHT_MM : ID1_WIDTH_MM;
        return new Point[]{new Point(0, 0), new Point(first, 0),
                new Point(first, second), new Point(0, second)};
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static String key(String cameraId) {
        return PREFIX + Base64.encodeToString(cameraId.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static void append(StringBuilder target, double value) {
        if (target.length() > 0) {
            target.append(',');
        }
        target.append(Double.toString(value));
    }

    /** Maps between the upright decoded JPEG and Camera2's sensor pixel-array coordinates. */
    static final class ImageTransform {
        private final int rawWidth;
        private final int rawHeight;
        private final int rotationDegrees;
        private final double cropLeft;
        private final double cropTop;
        private final double scaleX;
        private final double scaleY;

        ImageTransform(int sourceWidth, int sourceHeight, int rotationDegrees,
                       int pixelWidth, int pixelHeight, Rect activeArray) {
            if (sourceWidth <= 0 || sourceHeight <= 0
                    || pixelWidth <= 0 || pixelHeight <= 0) {
                throw new IllegalArgumentException("Invalid camera image dimensions");
            }
            this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360;
            if (this.rotationDegrees != 0 && this.rotationDegrees != 90
                    && this.rotationDegrees != 180 && this.rotationDegrees != 270) {
                throw new IllegalArgumentException("Invalid camera rotation");
            }
            boolean quarterTurn = this.rotationDegrees == 90 || this.rotationDegrees == 270;
            rawWidth = quarterTurn ? sourceHeight : sourceWidth;
            rawHeight = quarterTurn ? sourceWidth : sourceHeight;

            Rect active = activeArray == null ? new Rect(0, 0, pixelWidth, pixelHeight)
                    : new Rect(activeArray);
            active.left = Math.max(0, Math.min(pixelWidth, active.left));
            active.top = Math.max(0, Math.min(pixelHeight, active.top));
            active.right = Math.max(active.left + 1, Math.min(pixelWidth, active.right));
            active.bottom = Math.max(active.top + 1, Math.min(pixelHeight, active.bottom));
            double left = active.left;
            double top = active.top;
            double cropWidth = active.width();
            double cropHeight = active.height();
            double outputAspect = rawWidth / (double) rawHeight;
            if (cropWidth / cropHeight > outputAspect) {
                double adjustedWidth = cropHeight * outputAspect;
                left += (cropWidth - adjustedWidth) / 2d;
                cropWidth = adjustedWidth;
            } else {
                double adjustedHeight = cropWidth / outputAspect;
                top += (cropHeight - adjustedHeight) / 2d;
                cropHeight = adjustedHeight;
            }
            cropLeft = left;
            cropTop = top;
            scaleX = rawWidth / cropWidth;
            scaleY = rawHeight / cropHeight;
        }

        Point toSensor(Point upright) {
            return new Point(sensorXFromUpright(upright.x, upright.y),
                    sensorYFromUpright(upright.x, upright.y));
        }

        double sensorXFromUpright(double uprightX, double uprightY) {
            double rawX = rawXFromUpright(uprightX, uprightY);
            return cropLeft + (rawX + 0.5d) / scaleX - 0.5d;
        }

        double sensorYFromUpright(double uprightX, double uprightY) {
            double rawY = rawYFromUpright(uprightX, uprightY);
            return cropTop + (rawY + 0.5d) / scaleY - 0.5d;
        }

        private double rawXFromUpright(double uprightX, double uprightY) {
            switch (rotationDegrees) {
                case 90:
                    return uprightY;
                case 180:
                    return rawWidth - 1d - uprightX;
                case 270:
                    return rawWidth - 1d - uprightY;
                default:
                    return uprightX;
            }
        }

        private double rawYFromUpright(double uprightX, double uprightY) {
            switch (rotationDegrees) {
                case 90:
                    return rawHeight - 1d - uprightX;
                case 180:
                    return rawHeight - 1d - uprightY;
                case 270:
                    return uprightX;
                default:
                    return uprightY;
            }
        }

        Point toUpright(Point sensor) {
            return new Point(uprightXFromSensor(sensor.x, sensor.y),
                    uprightYFromSensor(sensor.x, sensor.y));
        }

        double uprightXFromSensor(double sensorX, double sensorY) {
            double rawY = rawYFromSensor(sensorY);
            switch (rotationDegrees) {
                case 90:
                    return rawHeight - 1d - rawY;
                case 180:
                    return rawWidth - 1d - rawXFromSensor(sensorX);
                case 270:
                    return rawY;
                default:
                    return rawXFromSensor(sensorX);
            }
        }

        double uprightYFromSensor(double sensorX, double sensorY) {
            double rawX = rawXFromSensor(sensorX);
            switch (rotationDegrees) {
                case 90:
                    return rawX;
                case 180:
                    return rawHeight - 1d - rawYFromSensor(sensorY);
                case 270:
                    return rawWidth - 1d - rawX;
                default:
                    return rawYFromSensor(sensorY);
            }
        }

        private double rawXFromSensor(double sensorX) {
            return (sensorX - cropLeft + 0.5d) * scaleX - 0.5d;
        }

        private double rawYFromSensor(double sensorY) {
            return (sensorY - cropTop + 0.5d) * scaleY - 0.5d;
        }
    }
}
