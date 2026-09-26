package com.example.securepasswordmanager.crypto;

import java.util.Arrays;
import java.util.UUID;

/** One JPG stored inside the encrypted vault data. */
public final class VaultImage {
    public final String id;
    public final byte[] jpeg;
    public final long createdAt;

    public VaultImage(byte[] jpeg) {
        this(UUID.randomUUID().toString(), jpeg, System.currentTimeMillis());
    }

    public VaultImage(String id, byte[] jpeg, long createdAt) {
        if (id == null || id.isEmpty() || jpeg == null || jpeg.length < 1
                || jpeg.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid vault image");
        }
        this.id = id;
        this.jpeg = jpeg.clone();
        this.createdAt = createdAt;
    }

    public void clear() {
        Arrays.fill(jpeg, (byte) 0);
    }
}
