package com.cretorix.securepasswordmanager;

import org.opencv.core.Point;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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

}
