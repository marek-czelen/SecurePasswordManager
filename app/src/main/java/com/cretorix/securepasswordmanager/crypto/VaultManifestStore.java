package com.cretorix.securepasswordmanager.crypto;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Atomic current/previous manifest files; current replacement is the commit point. */
public final class VaultManifestStore {
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;

    private final AtomicFile current;
    private final AtomicFile previous;

    public VaultManifestStore(File directory) {
        current = new AtomicFile(new File(directory, "manifest.current"));
        previous = new AtomicFile(new File(directory, "manifest.previous"));
    }

    public synchronized boolean hasCurrent() {
        return exists(current);
    }

    public synchronized byte[] readCurrent() throws IOException {
        return read(current);
    }

    public synchronized byte[] readPrevious() throws IOException {
        return read(previous);
    }

    public synchronized void writeInitial(byte[] encoded) throws IOException {
        if (hasCurrent()) {
            throw new IOException("VLT3 current manifest already exists");
        }
        write(current, encoded);
    }

    public synchronized void writePrevious(byte[] encoded) throws IOException {
        write(previous, encoded);
    }

    public synchronized void commit(byte[] next) throws IOException {
        byte[] old = read(current);
        write(previous, old);
        write(current, next);
    }

    public synchronized void recoverCurrentFromPrevious() throws IOException {
        write(current, read(previous));
    }

    public synchronized boolean hasPrevious() {
        return exists(previous);
    }

    private static boolean exists(AtomicFile file) {
        File base = file.getBaseFile();
        return base.isFile() || new File(base.getPath() + ".bak").isFile();
    }

    public synchronized void deleteAll() {
        current.delete();
        previous.delete();
    }

    private static byte[] read(AtomicFile file) throws IOException {
        try (FileInputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_MANIFEST_BYTES - total) {
                    throw new IOException("VLT3 manifest is too large");
                }
                bytes.write(buffer, 0, read);
                total += read;
            }
            return bytes.toByteArray();
        }
    }

    private static void write(AtomicFile file, byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 1 || encoded.length > MAX_MANIFEST_BYTES) {
            throw new IOException("Invalid VLT3 manifest length");
        }
        FileOutputStream output = file.startWrite();
        try {
            output.write(encoded);
            file.finishWrite(output);
        } catch (IOException | RuntimeException exception) {
            file.failWrite(output);
            throw exception;
        }
    }
}
