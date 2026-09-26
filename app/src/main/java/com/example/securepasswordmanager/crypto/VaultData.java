package com.example.securepasswordmanager.crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VaultData {
    public final List<VaultItem> items;

    public VaultData(List<VaultItem> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public static VaultData empty() {
        return new VaultData(Collections.<VaultItem>emptyList());
    }
}
