package com.cretorix.securepasswordmanager;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.cretorix.securepasswordmanager.crypto.VaultItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CredentialProviderSession {
    private static final long CACHE_TTL_MILLIS = 60_000L;
    private static final Handler EXPIRY_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<String, Snapshot> SNAPSHOTS = new HashMap<>();

    private CredentialProviderSession() {
    }

    static synchronized String remember(List<VaultItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        Map<String, VaultItem> credentials = new HashMap<>();
        for (VaultItem item : items) {
            credentials.put(item.entryId, item);
        }
        long expiresAt = SystemClock.elapsedRealtime() + CACHE_TTL_MILLIS;
        SNAPSHOTS.put(token, new Snapshot(credentials, expiresAt));
        EXPIRY_HANDLER.postDelayed(() -> forget(token), CACHE_TTL_MILLIS);
        return token;
    }

    static synchronized VaultItem get(String token, String entryId) {
        Snapshot snapshot = SNAPSHOTS.get(token);
        if (snapshot == null) {
            return null;
        }
        if (SystemClock.elapsedRealtime() >= snapshot.expiresAt) {
            SNAPSHOTS.remove(token);
            return null;
        }
        return snapshot.credentials.get(entryId);
    }

    static synchronized void forget(String token) {
        SNAPSHOTS.remove(token);
    }

    static synchronized void clearAll() {
        SNAPSHOTS.clear();
    }

    private static final class Snapshot {
        final Map<String, VaultItem> credentials;
        final long expiresAt;

        Snapshot(Map<String, VaultItem> credentials, long expiresAt) {
            this.credentials = credentials;
            this.expiresAt = expiresAt;
        }
    }
}
