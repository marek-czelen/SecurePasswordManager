package com.cretorix.securepasswordmanager;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PremiumAttachmentLimitsTest {
    @Test
    public void freeAllowsExactlyOneFileAndProAllowsMore() {
        assertTrue(PremiumAttachmentLimits.canAddFile(false, 0));
        assertFalse(PremiumAttachmentLimits.canAddFile(false, 1));
        assertTrue(PremiumAttachmentLimits.canAddFile(true, 1));
        assertTrue(PremiumAttachmentLimits.canAddFile(true, 20));
    }

    @Test
    public void freeAllowsExactlyOneDocumentAndProAllowsMore() {
        assertTrue(PremiumAttachmentLimits.canAddDocument(false, 0));
        assertFalse(PremiumAttachmentLimits.canAddDocument(false, 1));
        assertTrue(PremiumAttachmentLimits.canAddDocument(true, 1));
        assertTrue(PremiumAttachmentLimits.canAddDocument(true, 20));
    }
}
