package com.cretorix.securepasswordmanager.crypto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local-only health analysis; plaintext passwords are never persisted or sent anywhere. */
/** Local-only weak/reused password analysis. It never stores or transmits findings. */
public final class PasswordHealth {
    public final int weak;
    public final int reused;
    public final int total;

    private PasswordHealth(int weak, int reused, int total) {
        this.weak = weak;
        this.reused = reused;
        this.total = total;
    }

    public static PasswordHealth analyze(List<VaultItem> items) {
        Set<String> seen = new HashSet<>();
        int weak = 0;
        int reused = 0;
        int total = 0;
        for (VaultItem item : items) {
            if (item.password.isEmpty()) {
                continue;
            }
            total++;
            if (!seen.add(item.password)) {
                reused++;
            }
            if (item.password.length() < 12
                    || item.password.equalsIgnoreCase(item.username)
                    || item.password.matches("(?i)(password|qwerty|admin|12345678).*")) {
                weak++;
            }
        }
        return new PasswordHealth(weak, reused, total);
    }
}
