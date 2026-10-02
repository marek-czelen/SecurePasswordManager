package com.example.securepasswordmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class DocumentEdgeStabilizerTest {
    @Test
    public void smoothsSmallFrameToFrameMovements() {
        DocumentEdgeStabilizer stabilizer = new DocumentEdgeStabilizer();
        DocumentEdgeDetector.Quad initial = quad(0.2f, 0.2f, 0.8f, 0.8f);

        stabilizer.update(initial);
        DocumentEdgeDetector.Quad smoothed =
                stabilizer.update(quad(0.24f, 0.2f, 0.84f, 0.8f));

        assertEquals(0.212f, smoothed.points[0], 0.0001f);
    }

    @Test
    public void requiresRepeatedFramesBeforeReplacingAnUnrelatedQuad() {
        DocumentEdgeStabilizer stabilizer = new DocumentEdgeStabilizer();
        DocumentEdgeDetector.Quad initial = quad(0.1f, 0.2f, 0.6f, 0.8f);
        DocumentEdgeDetector.Quad unrelated = quad(0.35f, 0.2f, 0.85f, 0.8f);

        stabilizer.update(initial);
        assertEquals(initial, stabilizer.update(unrelated));
        assertEquals(initial, stabilizer.update(unrelated));
        DocumentEdgeDetector.Quad replaced = stabilizer.update(unrelated);

        assertEquals(0.35f, replaced.points[0], 0.0001f);
    }

    @Test
    public void holdsLastQuadBrieflyAndClearsItAfterRepeatedMisses() {
        DocumentEdgeStabilizer stabilizer = new DocumentEdgeStabilizer();
        DocumentEdgeDetector.Quad detected = quad(0.2f, 0.2f, 0.8f, 0.8f);

        stabilizer.update(detected);
        for (int miss = 0; miss < 7; miss++) {
            assertNotNull(stabilizer.update(null));
        }

        assertNull(stabilizer.update(null));
    }

    private static DocumentEdgeDetector.Quad quad(float left, float top,
                                                   float right, float bottom) {
        return new DocumentEdgeDetector.Quad(new float[]{
                left, top, right, top, right, bottom, left, bottom
        }, 1f);
    }
}
