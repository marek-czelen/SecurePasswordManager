package com.cretorix.securepasswordmanager.crypto;

import java.io.IOException;

/** Explicit failure when user-controlled data exceeds a VLT3 size limit. */
public final class VaultSizeLimitException extends IOException {
    public VaultSizeLimitException(String message) {
        super(message);
    }
}
