package com.example.securepasswordmanager;

import org.opencv.core.Point;

/** Pure geometry helpers used to preserve document proportions during rectification. */
final class DocumentGeometry {
    static final int MAX_RECTIFIED_EDGE = 3000;

    private DocumentGeometry() {
    }

    static Point[] expandQuadrilateral(Point[] corners, double padding) {
        if (corners == null || corners.length != 4
                || !Double.isFinite(padding) || padding < 0) {
            throw new IllegalArgumentException("Invalid document quadrilateral padding");
        }
        double signedArea = 0;
        for (int index = 0; index < corners.length; index++) {
            Point current = corners[index];
            Point next = corners[(index + 1) % corners.length];
            if (current == null || next == null
                    || !Double.isFinite(current.x) || !Double.isFinite(current.y)
                    || !Double.isFinite(next.x) || !Double.isFinite(next.y)) {
                throw new IllegalArgumentException("Invalid document corner");
            }
            signedArea += current.x * next.y - next.x * current.y;
        }
        if (Math.abs(signedArea) < 1e-9) {
            throw new IllegalArgumentException("Degenerate document quadrilateral");
        }

        double outwardDirection = signedArea > 0 ? 1d : -1d;
        Point[] expanded = new Point[4];
        for (int index = 0; index < corners.length; index++) {
            Point previous = corners[(index + 3) % 4];
            Point vertex = corners[index];
            Point next = corners[(index + 1) % 4];
            double previousDx = vertex.x - previous.x;
            double previousDy = vertex.y - previous.y;
            double nextDx = next.x - vertex.x;
            double nextDy = next.y - vertex.y;
            double previousLength = Math.hypot(previousDx, previousDy);
            double nextLength = Math.hypot(nextDx, nextDy);
            if (previousLength < 1e-9 || nextLength < 1e-9) {
                throw new IllegalArgumentException("Degenerate document edge");
            }

            double previousX = previous.x
                    + outwardDirection * previousDy / previousLength * padding;
            double previousY = previous.y
                    - outwardDirection * previousDx / previousLength * padding;
            double nextX = vertex.x + outwardDirection * nextDy / nextLength * padding;
            double nextY = vertex.y - outwardDirection * nextDx / nextLength * padding;
            double denominator = previousDx * nextDy - previousDy * nextDx;
            if (Math.abs(denominator) < 1e-9) {
                throw new IllegalArgumentException("Parallel document edges");
            }
            double offsetX = nextX - previousX;
            double offsetY = nextY - previousY;
            double distance = (offsetX * nextDy - offsetY * nextDx) / denominator;
            expanded[index] = new Point(previousX + distance * previousDx,
                    previousY + distance * previousDy);
        }
        return expanded;
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

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x - second.x, first.y - second.y);
    }
}
