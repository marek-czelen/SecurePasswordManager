package com.example.securepasswordmanager.crypto;

public final class VaultV3Format {
    public static final int CONTAINER_FORMAT_VERSION = 3;
    public static final int HEADER_SCHEMA_VERSION = 1;
    public static final int MANIFEST_SCHEMA_VERSION = 1;
    public static final int TEXT_DATA_SCHEMA_VERSION = 2;
    public static final int OBJECT_SCHEMA_VERSION = 1;
    public static final int BACKUP_CONTAINER_VERSION = 1;
    public static final int MAX_KDF_MEMORY_KIB = 256 * 1024;
    public static final int MAX_KDF_TIME_COST = 10;
    public static final int MAX_KDF_PARALLELISM = 4;

    public static boolean isSupportedKdf(KdfParameters parameters) {
        return parameters != null && KdfParameters.ALGORITHM.equals(parameters.algorithm)
                && parameters.memoryKiB <= MAX_KDF_MEMORY_KIB
                && parameters.timeCost <= MAX_KDF_TIME_COST
                && parameters.parallelism <= MAX_KDF_PARALLELISM;
    }

    private VaultV3Format() {
    }
}
