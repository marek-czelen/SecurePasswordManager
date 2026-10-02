package com.example.securepasswordmanager;

/** Filters inconsistent live detections and smooths small frame-to-frame movements. */
final class DocumentEdgeStabilizer {
    private static final int MAX_MISSED_FRAMES = 7;
    private static final int REQUIRED_REPLACEMENT_FRAMES = 3;
    private static final float SMOOTHING = 0.3f;
    private static final double MAX_CORNER_MOVEMENT = 0.10;
    private static final double MIN_AREA_RATIO = 0.5;
    private static final double MAX_AREA_RATIO = 2.0;

    private DocumentEdgeDetector.Quad current;
    private DocumentEdgeDetector.Quad replacementCandidate;
    private int missedFrames;
    private int replacementFrames;

    DocumentEdgeDetector.Quad update(DocumentEdgeDetector.Quad candidate) {
        if (candidate == null) {
            replacementCandidate = null;
            replacementFrames = 0;
            if (current == null || ++missedFrames > MAX_MISSED_FRAMES) {
                current = null;
                missedFrames = 0;
            }
            return current;
        }

        missedFrames = 0;
        if (current == null) {
            current = candidate;
            clearReplacement();
            return current;
        }
        if (isContinuous(current, candidate)) {
            current = blend(current, candidate);
            clearReplacement();
            return current;
        }

        if (replacementCandidate != null
                && isContinuous(replacementCandidate, candidate)) {
            replacementCandidate = blend(replacementCandidate, candidate);
            replacementFrames++;
        } else {
            replacementCandidate = candidate;
            replacementFrames = 1;
        }
        if (replacementFrames >= REQUIRED_REPLACEMENT_FRAMES) {
            current = replacementCandidate;
            clearReplacement();
        }
        return current;
    }

    void reset() {
        current = null;
        clearReplacement();
        missedFrames = 0;
    }

    private static boolean isContinuous(DocumentEdgeDetector.Quad first,
                                        DocumentEdgeDetector.Quad second) {
        if (first.points == null || second.points == null
                || first.points.length != 8 || second.points.length != 8) {
            return false;
        }
        double totalMovement = 0;
        for (int index = 0; index < 8; index += 2) {
            totalMovement += Math.hypot(first.points[index] - second.points[index],
                    first.points[index + 1] - second.points[index + 1]);
        }
        double averageMovement = totalMovement / 4d;
        double firstArea = area(first.points);
        double secondArea = area(second.points);
        double areaRatio = firstArea <= 0 || secondArea <= 0
                ? Double.POSITIVE_INFINITY : secondArea / firstArea;
        return averageMovement <= MAX_CORNER_MOVEMENT
                && areaRatio >= MIN_AREA_RATIO && areaRatio <= MAX_AREA_RATIO;
    }

    private static double area(float[] points) {
        double area = 0;
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            area += points[index * 2] * points[next * 2 + 1]
                    - points[next * 2] * points[index * 2 + 1];
        }
        return Math.abs(area) / 2d;
    }

    private static DocumentEdgeDetector.Quad blend(DocumentEdgeDetector.Quad previous,
                                                    DocumentEdgeDetector.Quad candidate) {
        float[] points = new float[8];
        for (int index = 0; index < points.length; index++) {
            points[index] = previous.points[index] * (1f - SMOOTHING)
                    + candidate.points[index] * SMOOTHING;
        }
        return new DocumentEdgeDetector.Quad(points, candidate.aspectRatio);
    }

    private void clearReplacement() {
        replacementCandidate = null;
        replacementFrames = 0;
    }
}
