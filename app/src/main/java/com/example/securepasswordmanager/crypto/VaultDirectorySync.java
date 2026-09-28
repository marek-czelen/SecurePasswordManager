package com.example.securepasswordmanager.crypto;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/** Android directory durability hook used after immutable object renames. */
public final class VaultDirectorySync {
    private VaultDirectorySync() {
    }

    public static void sync(File directory) throws IOException {
        java.io.FileDescriptor descriptor;
        try {
            descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(descriptor);
            } finally {
                Os.close(descriptor);
            }
        } catch (ErrnoException exception) {
            throw new IOException("Could not sync VLT3 object directory", exception);
        }
    }
}
