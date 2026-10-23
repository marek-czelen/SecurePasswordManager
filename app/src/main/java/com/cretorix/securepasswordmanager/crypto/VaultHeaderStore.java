package com.cretorix.securepasswordmanager.crypto;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Atomic persistence for the independently versioned VLT3 header. */
public final class VaultHeaderStore {
    private final AtomicFile atomicFile;

    public VaultHeaderStore(File headerFile) {
        atomicFile = new AtomicFile(headerFile);
    }

    public VaultHeaderV3 read() throws IOException {
        try (FileInputStream input = atomicFile.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 64 * 1024 - total) {
                    throw new IOException("VLT3 header is too large");
                }
                bytes.write(buffer, 0, read);
                total += read;
            }
            return VaultHeaderCodec.decode(bytes.toByteArray());
        }
    }

    public boolean exists() {
        File base = atomicFile.getBaseFile();
        return base.isFile() || new File(base.getPath() + ".bak").isFile();
    }

    public void delete() {
        atomicFile.delete();
    }

    public void write(VaultHeaderV3 header) throws IOException {
        FileOutputStream output = atomicFile.startWrite();
        try {
            output.write(header.encode());
            atomicFile.finishWrite(output);
        } catch (IOException | RuntimeException exception) {
            atomicFile.failWrite(output);
            throw exception;
        }
    }
}
