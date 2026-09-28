package com.example.securepasswordmanager.repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Recoverable same-filesystem directory switch used when importing a complete vault. */
public final class VaultRootTransaction {
    private VaultRootTransaction() {
    }

    public static void recover(File current, File previous, DirectorySync sync)
            throws IOException {
        if (!current.exists() && previous.exists()) {
            move(previous, current, sync);
        }
    }

    public static void install(File current, File previous, File staging, DirectorySync sync)
            throws IOException {
        if (previous.exists() || !staging.isDirectory()) {
            throw new IOException("Invalid VLT3 root switch state");
        }
        if (current.exists()) {
            try {
                move(current, previous, sync);
            } catch (IOException exception) {
                if (!current.exists() && previous.exists()) {
                    try {
                        move(previous, current, sync);
                    } catch (IOException restoreException) {
                        exception.addSuppressed(restoreException);
                    }
                }
                throw exception;
            }
        }
        try {
            move(staging, current, sync);
        } catch (IOException exception) {
            if (!current.exists() && previous.exists()) {
                try {
                    move(previous, current, sync);
                } catch (IOException restoreException) {
                    exception.addSuppressed(restoreException);
                }
            }
            throw exception;
        }
    }

    private static void move(File source, File destination, DirectorySync sync)
            throws IOException {
        if (destination.exists()) {
            throw new IOException("VLT3 directory destination already exists");
        }
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic VLT3 directory rename is unavailable", exception);
        }
        File parent = destination.getParentFile();
        if (parent != null) {
            sync.sync(parent);
        }
    }

    public interface DirectorySync {
        void sync(File directory) throws IOException;
    }
}
