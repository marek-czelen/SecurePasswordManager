package com.example.securepasswordmanager.crypto;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class KdfParameters {
    public static final String ALGORITHM = "PBKDF2-HMAC-SHA256";
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int SALT_SIZE = 16;

    public final String algorithm;
    public final int iterations;
    public final byte[] salt;

    public KdfParameters(byte[] salt, int iterations) {
        this(ALGORITHM, salt, iterations);
    }

    public KdfParameters(String algorithm, byte[] salt, int iterations) {
        if (!ALGORITHM.equals(algorithm) || salt.length != SALT_SIZE
                || iterations < 100_000 || iterations > 5_000_000) {
            throw new IllegalArgumentException("Unsupported KDF parameters");
        }
        this.algorithm = algorithm;
        this.salt = salt.clone();
        this.iterations = iterations;
    }

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
}
