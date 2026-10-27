package com.cretorix.securepasswordmanager;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

import java.util.ArrayList;
import java.util.List;

final class PremiumLimits {
    static final int FREE_FILE_LIMIT = 1;
    static final int FREE_DOCUMENT_LIMIT = 1;
    static final int FREE_PASSWORD_HISTORY_LIMIT = 2;
    static final int FREE_IMPORT_EXPORT_ENTRY_LIMIT = 5;

    private PremiumLimits() {
    }

    static boolean canAddFile(boolean isPro, int currentCount) {
        return isPro || currentCount < FREE_FILE_LIMIT;
    }

    static boolean canAddDocument(boolean isPro, int currentCount) {
        return isPro || currentCount < FREE_DOCUMENT_LIMIT;
    }

    static int passwordHistoryLimit(boolean isPro) {
        return isPro ? VaultItem.MAX_PASSWORD_HISTORY_ENTRIES
                : FREE_PASSWORD_HISTORY_LIMIT;
    }

    static <T> List<T> limitImportExportEntries(List<T> entries, boolean isPro) {
        int count = isPro ? entries.size()
                : Math.min(entries.size(), FREE_IMPORT_EXPORT_ENTRY_LIMIT);
        return new ArrayList<>(entries.subList(0, count));
    }

    static List<VaultItem> csvExportEntries(List<VaultItem> items, boolean isPro) {
        List<VaultItem> logins = new ArrayList<>();
        for (VaultItem item : items) {
            if (VaultItem.TYPE_LOGIN.equals(item.type)) {
                logins.add(item);
            }
        }
        return limitImportExportEntries(logins, isPro);
    }
}
