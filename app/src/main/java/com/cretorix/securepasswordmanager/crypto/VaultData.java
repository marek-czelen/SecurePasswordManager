package com.cretorix.securepasswordmanager.crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable in-memory collection of decrypted VLT3 text entries. */
public final class VaultData {
    public final List<VaultItem> items;

    public VaultData(List<VaultItem> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public static VaultData empty() {
        return new VaultData(Collections.<VaultItem>emptyList());
    }
}
