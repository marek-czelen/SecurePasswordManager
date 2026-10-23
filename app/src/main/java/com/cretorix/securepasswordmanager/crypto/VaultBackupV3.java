package com.cretorix.securepasswordmanager.crypto;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Single-file backup framing for already encrypted VLT3 components. */
public final class VaultBackupV3 {
    private static final int MAGIC = 0x56424b33;
    private static final int END_MAGIC = 0x454e4433;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    public static final long MAX_BACKUP_BYTES = 120L * 1024 * 1024;

    private VaultBackupV3() {
    }

    public static void write(OutputStream destination, byte[] encodedHeader,
                             byte[] encodedManifest, VaultManifest manifest,
                             VaultObjectStore objectStore) throws IOException {
        if (destination == null || encodedHeader == null
                || encodedHeader.length < 1 || encodedHeader.length > MAX_HEADER_BYTES
                || encodedManifest == null || encodedManifest.length < 1
                || encodedManifest.length > MAX_MANIFEST_BYTES
                || manifest == null || objectStore == null) {
            throw new IOException("Invalid VLT3 backup input");
        }
        CountingOutputStream counted = new CountingOutputStream(destination, MAX_BACKUP_BYTES);
        DataOutputStream output = new DataOutputStream(counted);
        List<VaultObjectRef> refs = objectRefs(manifest);
        output.writeInt(MAGIC);
        output.writeInt(VaultV3Format.BACKUP_CONTAINER_VERSION);
        output.writeInt(VaultV3Format.CONTAINER_FORMAT_VERSION);
        output.writeInt(encodedHeader.length);
        output.write(encodedHeader);
        output.writeInt(encodedManifest.length);
        output.write(encodedManifest);
        output.writeInt(refs.size());
        for (VaultObjectRef ref : refs) {
            output.write(ref.objectId);
            output.writeLong(VaultObjectStore.encodedLength(ref));
            objectStore.copyVerified(ref, output);
        }
        output.writeInt(END_MAGIC);
        output.writeInt(refs.size());
        output.flush();
    }

    public static BackupReader open(InputStream source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Backup input is required");
        }
        DataInputStream input = new DataInputStream(source);
        if (input.readInt() != MAGIC
                || input.readInt() != VaultV3Format.BACKUP_CONTAINER_VERSION
                || input.readInt() != VaultV3Format.CONTAINER_FORMAT_VERSION) {
            throw new IOException("Unsupported VLT3 backup container");
        }
        byte[] header = readBounded(input, MAX_HEADER_BYTES, "header");
        byte[] manifest = readBounded(input, MAX_MANIFEST_BYTES, "manifest");
        int objectCount = input.readInt();
        if (objectCount < 1 || objectCount > VaultManifest.MAX_RESOURCES + 1) {
            throw new IOException("Invalid VLT3 backup object count");
        }
        return new BackupReader(input, header, manifest, objectCount);
    }

    private static byte[] readBounded(DataInputStream input, int max, String label)
            throws IOException {
        int length = input.readInt();
        if (length < 1 || length > max) {
            throw new IOException("Invalid VLT3 backup " + label + " length");
        }
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    private static List<VaultObjectRef> objectRefs(VaultManifest manifest) {
        List<VaultObjectRef> refs = new ArrayList<>(manifest.resources.size() + 1);
        refs.add(manifest.textBlock);
        refs.addAll(manifest.resources);
        return refs;
    }

    public static final class BackupReader {
        private final DataInputStream input;
        private final byte[] encodedHeader;
        private final byte[] encodedManifest;
        private final int objectCount;
        private boolean consumed;

        private BackupReader(DataInputStream input, byte[] encodedHeader,
                             byte[] encodedManifest, int objectCount) {
            this.input = input;
            this.encodedHeader = encodedHeader;
            this.encodedManifest = encodedManifest;
            this.objectCount = objectCount;
        }

        public byte[] encodedHeader() {
            return encodedHeader.clone();
        }

        public byte[] encodedManifest() {
            return encodedManifest.clone();
        }

        public void importObjects(VaultManifest manifest, VaultObjectStore destination)
                throws IOException {
            if (consumed || manifest == null || destination == null) {
                throw new IOException("Invalid VLT3 backup reader state");
            }
            List<VaultObjectRef> refs = objectRefs(manifest);
            if (objectCount != refs.size()) {
                throw new IOException("VLT3 backup object count does not match manifest");
            }
            for (VaultObjectRef ref : refs) {
                byte[] objectId = new byte[32];
                try {
                    input.readFully(objectId);
                    long length = input.readLong();
                    if (!Crypto.constantTimeEquals(objectId, ref.objectId)
                            || length != VaultObjectStore.encodedLength(ref)) {
                        throw new IOException("VLT3 backup object order or size mismatch");
                    }
                    destination.importObject(ref, input, length);
                } finally {
                    Crypto.wipe(objectId);
                }
            }
            if (input.readInt() != END_MAGIC || input.readInt() != refs.size()
                    || input.read() != -1) {
                throw new IOException("Incomplete or trailing VLT3 backup data");
            }
            consumed = true;
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private final long limit;
        private long count;

        CountingOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensure(1);
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensure(length);
            out.write(bytes, offset, length);
            count += length;
        }

        private void ensure(int additional) throws IOException {
            if (additional < 0 || additional > limit - count) {
                throw new IOException("VLT3 backup exceeds 120 MiB");
            }
        }
    }
}
