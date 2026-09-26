package com.example.securepasswordmanager.crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable in-memory collection of decrypted entries; never persisted as plaintext. */
public final class VaultData {
    public final List<VaultItem> items;
    public final List<VaultImage> images;

    public VaultData(List<VaultItem> items) {
        this(items, Collections.<VaultImage>emptyList());
    }

    public VaultData(List<VaultItem> items, List<VaultImage> images) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.images = Collections.unmodifiableList(new ArrayList<>(images));
    }

    public static VaultData empty() {
        return new VaultData(Collections.<VaultItem>emptyList());
    }
}
