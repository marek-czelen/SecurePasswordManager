package com.example.securepasswordmanager;

import org.opencv.core.Point;

/** Pure geometry helpers used to preserve document proportions during rectification. */
final class DocumentGeometry {
    static final int MAX_RECTIFIED_EDGE = 3000;

    private DocumentGeometry() {
    }

    static double metricAspectRatio(double[] normalizedCorners) {
        if (normalizedCorners == null || normalizedCorners.length != 8) {
            throw new IllegalArgumentException("Four normalized corners are required");
        }
        double[][] equations = new double[8][9];
        double[][] unitSquare = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int index = 0; index < 4; index++) {
            double x = unitSquare[index][0];
            double y = unitSquare[index][1];
            double u = normalizedCorners[index * 2];
            double v = normalizedCorners[index * 2 + 1];
            if (!Double.isFinite(u) || !Double.isFinite(v)) {
                throw new IllegalArgumentException("Invalid normalized corner");
            }
            int row = index * 2;
            equations[row][0] = x;
            equations[row][1] = y;
            equations[row][2] = 1;
            equations[row][6] = -u * x;
            equations[row][7] = -u * y;
            equations[row][8] = u;

            equations[row + 1][3] = x;
            equations[row + 1][4] = y;
            equations[row + 1][5] = 1;
            equations[row + 1][6] = -v * x;
            equations[row + 1][7] = -v * y;
            equations[row + 1][8] = v;
        }

        double[] homography = solve(equations);
        double firstBasis = Math.sqrt(homography[0] * homography[0]
                + homography[3] * homography[3] + homography[6] * homography[6]);
        double secondBasis = Math.sqrt(homography[1] * homography[1]
                + homography[4] * homography[4] + homography[7] * homography[7]);
        double ratio = firstBasis / secondBasis;
        if (!Double.isFinite(ratio) || ratio < 0.1 || ratio > 10) {
            throw new IllegalArgumentException("Invalid metric document aspect");
        }
        return ratio;
    }

    static double orientAspectRatio(double landscapeAspect, Point[] corners) {
        if (!Double.isFinite(landscapeAspect) || landscapeAspect <= 0
                || corners == null || corners.length != 4) {
            throw new IllegalArgumentException("Invalid document aspect");
        }
        double reciprocalAspect = 1d / landscapeAspect;
        if (!Double.isFinite(reciprocalAspect) || reciprocalAspect <= 0) {
            throw new IllegalArgumentException("Invalid document aspect");
        }
        double firstPair = (distance(corners[0], corners[1])
                + distance(corners[3], corners[2])) / 2d;
        double secondPair = (distance(corners[0], corners[3])
                + distance(corners[1], corners[2])) / 2d;
        double absoluteAspect = Math.max(landscapeAspect, reciprocalAspect);
        return firstPair >= secondPair ? absoluteAspect : 1d / absoluteAspect;
    }

    static int[] outputDimensions(double horizontalEdge, double verticalEdge,
                                  double aspectRatio) {
        if (!Double.isFinite(horizontalEdge) || !Double.isFinite(verticalEdge)
                || !Double.isFinite(aspectRatio) || horizontalEdge <= 0
                || verticalEdge <= 0 || aspectRatio <= 0) {
            throw new IllegalArgumentException("Invalid document dimensions");
        }
        double longestEdge = Math.max(horizontalEdge, verticalEdge);
        double width = aspectRatio >= 1d ? longestEdge : longestEdge * aspectRatio;
        double height = aspectRatio >= 1d ? longestEdge / aspectRatio : longestEdge;
        double scale = Math.min(1d, MAX_RECTIFIED_EDGE / Math.max(width, height));
        return new int[]{
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))
        };
    }

    static int[] scaleDimensions(int width, int height, double scale) {
        if (width <= 0 || height <= 0 || !Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
        return new int[]{Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))};
    }

    private static double[] solve(double[][] values) {
        int size = values.length;
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(values[row][column]) > Math.abs(values[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(values[pivot][column]) < 1e-12) {
                throw new IllegalArgumentException("Degenerate document quadrilateral");
            }
            double[] swap = values[column];
            values[column] = values[pivot];
            values[pivot] = swap;
            double divisor = values[column][column];
            for (int index = column; index <= size; index++) {
                values[column][index] /= divisor;
            }
            for (int row = 0; row < size; row++) {
                if (row == column) {
                    continue;
                }
                double factor = values[row][column];
                for (int index = column; index <= size; index++) {
                    values[row][index] -= factor * values[column][index];
                }
            }
        }
        double[] result = new double[size];
        for (int row = 0; row < size; row++) {
            result[row] = values[row][size];
        }
        return result;
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x - second.x, first.y - second.y);
    }
}
