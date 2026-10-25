package com.cretorix.securepasswordmanager;

final class PremiumAttachmentLimits {
    private static final int FREE_FILE_LIMIT = 1;
    private static final int FREE_DOCUMENT_LIMIT = 1;

    private PremiumAttachmentLimits() {
    }

    static boolean canAddFile(boolean isPro, int currentCount) {
        return isPro || currentCount < FREE_FILE_LIMIT;
    }

    static boolean canAddDocument(boolean isPro, int currentCount) {
        return isPro || currentCount < FREE_DOCUMENT_LIMIT;
    }
}
