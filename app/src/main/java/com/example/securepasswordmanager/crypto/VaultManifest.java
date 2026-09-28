package com.example.securepasswordmanager.crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One authenticated VLT3 catalog generation. */
public final class VaultManifest {
    public static final int MAX_RESOURCES = 30_000;
    public static final long MAX_ACTIVE_BYTES = 100L * 1024 * 1024;

    public final int schemaVersion;
    public final byte[] generationId;
    public final VaultObjectRef textBlock;
    public final List<VaultObjectRef> resources;

    public VaultManifest(byte[] generationId, VaultObjectRef textBlock,
                         List<VaultObjectRef> resources) {
        if (!VaultObjectRef.validId(generationId) || textBlock == null
                || textBlock.type != VaultObjectRef.Type.TEXT_BLOCK
                || resources == null || resources.size() > MAX_RESOURCES) {
            throw new IllegalArgumentException("Invalid VLT3 manifest");
        }
        Set<String> ids = new HashSet<>();
        ids.add(hex(textBlock.objectId));
        Set<String> generations = new HashSet<>();
        generations.add(hex(textBlock.generationId));
        long activeBytes = textBlock.plaintextLength;
        List<VaultObjectRef> copied = new ArrayList<>(resources.size());
        for (VaultObjectRef resource : resources) {
            if (resource == null || resource.type == VaultObjectRef.Type.TEXT_BLOCK
                    || !ids.add(hex(resource.objectId))
                    || !generations.add(hex(resource.generationId))) {
                throw new IllegalArgumentException("Duplicate or invalid VLT3 resource");
            }
            activeBytes = Math.addExact(activeBytes, resource.plaintextLength);
            if (activeBytes > MAX_ACTIVE_BYTES) {
                throw new IllegalArgumentException("VLT3 active data limit exceeded");
            }
            copied.add(resource);
        }
        if (!generations.add(hex(generationId))) {
            throw new IllegalArgumentException("Duplicate VLT3 manifest generation");
        }
        schemaVersion = VaultV3Format.MANIFEST_SCHEMA_VERSION;
        this.generationId = generationId.clone();
        this.textBlock = textBlock;
        this.resources = Collections.unmodifiableList(copied);
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(item & 0x0f, 16));
        }
        return output.toString();
    }
}
