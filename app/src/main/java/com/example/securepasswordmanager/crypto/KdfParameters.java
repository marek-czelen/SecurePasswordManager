package com.example.securepasswordmanager.crypto;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Versioned, serialized parameters for the memory-hard PIN/passphrase KDF. */
public final class KdfParameters {
    public static final String ALGORITHM = "Argon2id";
    public static final String LEGACY_ALGORITHM = "PBKDF2-HMAC-SHA256";
    public static final int DEFAULT_MEMORY_KIB = 64 * 1024;
    public static final int DEFAULT_TIME_COST = 3;
    public static final int DEFAULT_PARALLELISM = 2;
    /** Kept for source compatibility with the VLT1 reader. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int SALT_SIZE = 16;

    public final String algorithm;
    public final int memoryKiB;
    public final int timeCost;
    public final int parallelism;
    /** Legacy PBKDF2 iteration count; never used by VLT2. */
    public final int iterations;
    public final byte[] salt;

    public KdfParameters(byte[] salt, int iterations) {
        this(LEGACY_ALGORITHM, salt, 0, iterations, 0, iterations);
    }

    public KdfParameters(byte[] salt, int memoryKiB, int timeCost, int parallelism) {
        this(ALGORITHM, salt, memoryKiB, timeCost, parallelism, 0);
    }

    public KdfParameters(String algorithm, byte[] salt, int iterations) {
        this(algorithm, salt, 0, iterations, 0, iterations);
    }

    private KdfParameters(String algorithm, byte[] salt, int memoryKiB, int timeCost,
                          int parallelism, int iterations) {
        boolean legacy = LEGACY_ALGORITHM.equals(algorithm);
        boolean validArgon = ALGORITHM.equals(algorithm)
                && memoryKiB >= 8 * 1024 && memoryKiB <= 1024 * 1024
                && timeCost >= 1 && timeCost <= 20
                && parallelism >= 1 && parallelism <= 16;
        boolean validLegacy = legacy && iterations >= 100_000 && iterations <= 5_000_000;
        if (salt == null || salt.length != SALT_SIZE || (!validArgon && !validLegacy)) {
            throw new IllegalArgumentException("Unsupported KDF parameters");
        }
        this.algorithm = algorithm;
        this.memoryKiB = memoryKiB;
        this.timeCost = timeCost;
        this.parallelism = parallelism;
        this.salt = salt.clone();
        this.iterations = iterations;
    }

    /** VLT1 encoding retained solely so old files can be identified and migrated. */
    public void write(DataOutputStream output) throws IOException {
        output.writeUTF(algorithm);
        output.writeInt(iterations);
        output.writeByte(salt.length);
        output.write(salt);
    }

    public static KdfParameters read(DataInputStream input) throws IOException {
        String algorithm = input.readUTF();
        int iterations = input.readInt();
        int saltLength = input.readUnsignedByte();
        if (saltLength != SALT_SIZE) {
            throw new IOException("Invalid KDF salt");
        }
        byte[] salt = new byte[saltLength];
        input.readFully(salt);
        return new KdfParameters(algorithm, salt, iterations);
    }

    public void writeV2(DataOutputStream output) throws IOException {
        if (!ALGORITHM.equals(algorithm)) {
            throw new IOException("VLT2 requires Argon2id");
        }
        output.writeUTF(algorithm);
        output.writeInt(memoryKiB);
        output.writeInt(timeCost);
        output.writeInt(parallelism);
        output.writeByte(salt.length);
        output.write(salt);
    }

    public static KdfParameters readV2(DataInputStream input) throws IOException {
        String algorithm = input.readUTF();
        int memory = input.readInt();
        int time = input.readInt();
        int parallel = input.readInt();
        int saltLength = input.readUnsignedByte();
        if (saltLength != SALT_SIZE) {
            throw new IOException("Invalid Argon2id salt");
        }
        byte[] salt = new byte[saltLength];
        input.readFully(salt);
        try {
            return new KdfParameters(algorithm, salt, memory, time, parallel, 0);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Argon2id parameters", exception);
        }
    }
}
