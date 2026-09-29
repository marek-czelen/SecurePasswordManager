package com.example.securepasswordmanager;

import org.opencv.core.Point;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DocumentEdgeDetectorTest {
    @Test
    public void edgePaddingExpandsEverySideOfTheDetectedQuadrilateral() {
        Point[] detected = {
                new Point(10, 20), new Point(110, 20),
                new Point(110, 220), new Point(10, 220)
        };
        Point[] padded = DocumentGeometry.expandQuadrilateral(detected, 10);

        assertEquals(0, padded[0].x, 0.001);
        assertEquals(10, padded[0].y, 0.001);
        assertEquals(120, padded[1].x, 0.001);
        assertEquals(10, padded[1].y, 0.001);
        assertEquals(120, padded[2].x, 0.001);
        assertEquals(230, padded[2].y, 0.001);
        assertEquals(0, padded[3].x, 0.001);
        assertEquals(230, padded[3].y, 0.001);
    }

    @Test
    public void calibratedHomographyRecoversPhysicalAspectUnderPerspective() {
        double width = DocumentCameraCalibration.ID1_WIDTH_MM;
        double height = DocumentCameraCalibration.ID1_HEIGHT_MM;
        double cosine = Math.cos(Math.toRadians(48));
        double sine = Math.sin(Math.toRadians(48));
        double[][] homography = {
                {width * cosine, 0, 0.2},
                {0, height, -0.15},
                {-width * sine, 0, 2.2}
        };
        double[] projected = new double[8];
        double[][] unitSquare = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int index = 0; index < 4; index++) {
            double x = unitSquare[index][0];
            double y = unitSquare[index][1];
            double denominator = homography[2][0] * x
                    + homography[2][1] * y + homography[2][2];
            projected[index * 2] = (homography[0][0] * x
                    + homography[0][1] * y + homography[0][2]) / denominator;
            projected[index * 2 + 1] = (homography[1][0] * x
                    + homography[1][1] * y + homography[1][2]) / denominator;
        }

        assertEquals(width / height, DocumentGeometry.metricAspectRatio(projected), 1e-9);
    }

    @Test
    public void standardAspectOverridesPerspectiveAndFollowsPageOrientation() {
        Point[] portrait = {new Point(20, 10), new Point(620, 50),
                new Point(540, 870), new Point(5, 800)};
        double landscapeCard = 85.60 / 53.98;

        assertEquals(1d / landscapeCard,
                DocumentGeometry.orientAspectRatio(landscapeCard, portrait), 1e-9);

        int[] dimensions = DocumentGeometry.outputDimensions(600, 1200,
                DocumentGeometry.orientAspectRatio(landscapeCard, portrait));
        assertEquals(757, dimensions[0]);
        assertEquals(1200, dimensions[1]);
    }

    @Test
    public void outputSizingPreservesRatioWhenCappedAndAfterQualityScaling() {
        int[] dimensions = DocumentGeometry.outputDimensions(6000, 4000, 1.5);

        assertEquals(3000, dimensions[0]);
        assertEquals(2000, dimensions[1]);
        assertEquals(1.5, dimensions[0] / (double) dimensions[1], 0.001);

        int[] scaled = DocumentGeometry.scaleDimensions(dimensions[0], dimensions[1], 0.8);
        assertEquals(2400, scaled[0]);
        assertEquals(1600, scaled[1]);
        assertEquals(1.5, scaled[0] / (double) scaled[1], 0.001);
    }

    @Test
    public void metricAspectIsNotInferredFromPerspectiveEdgeLengthAverage() {
        double apparentAverageAspect = 434d / 905d;
        double calibratedCardAspect = 85.60 / 53.98;
        int[] dimensions = DocumentGeometry.outputDimensions(434, 905,
                calibratedCardAspect);

        assertTrue(Math.abs(apparentAverageAspect
                - dimensions[0] / (double) dimensions[1]) > 0.5);
    }

    @Test
    public void calibrationRequiresPlausibleIntrinsicParameters() {
        DocumentCameraCalibration valid = new DocumentCameraCalibration("0",
                4000, 3000, 2500, 2500, 2000, 1500, 0,
                new double[5], 1.2);
        DocumentCameraCalibration invalid = new DocumentCameraCalibration("0",
                4000, 3000, Double.NaN, 2500, 2000, 1500, 0,
                new double[5], 1.2);

        assertTrue(valid.isValid());
        assertTrue(!invalid.isValid());
        assertTrue(DocumentCameraCalibration.passesReprojectionValidation(12,
                4000, 3000));
        assertTrue(!DocumentCameraCalibration.passesReprojectionValidation(17,
                4000, 3000));
        assertTrue(!DocumentCameraCalibration.passesReprojectionValidation(Double.NaN,
                4000, 3000));
    }
}
