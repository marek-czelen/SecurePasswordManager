package com.example.securepasswordmanager.crypto;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Bounds processed image and thumbnail data before VLT3 object encryption. */
public final class VaultImageProcessor {
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_THUMBNAIL_BYTES = 256 * 1024;
    private static final int THUMBNAIL_EDGE = 256;

    private VaultImageProcessor() {
    }

    public static byte[] createThumbnail(byte[] jpeg) throws IOException {
        Bitmap decoded = decodeForDisplay(jpeg, MAX_IMAGE_BYTES, THUMBNAIL_EDGE * 4);
        Bitmap scaled = null;
        try {
            float scale = Math.min(1f, (float) THUMBNAIL_EDGE
                    / Math.max(decoded.getWidth(), decoded.getHeight()));
            int width = Math.max(1, Math.round(decoded.getWidth() * scale));
            int height = Math.max(1, Math.round(decoded.getHeight() * scale));
            scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
            for (int quality : new int[]{82, 70, 58, 46}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw new IOException("Could not encode VLT3 thumbnail");
                }
                byte[] thumbnail = output.toByteArray();
                if (thumbnail.length <= MAX_THUMBNAIL_BYTES) {
                    return thumbnail;
                }
            }
            throw new IOException("VLT3 thumbnail exceeds 256 KiB");
        } finally {
            if (scaled != null && scaled != decoded) {
                scaled.recycle();
            }
            decoded.recycle();
        }
    }

    public static Bitmap decodeForDisplay(byte[] jpeg, int maximumBytes, int maximumEdge)
            throws IOException {
        if (jpeg == null || jpeg.length < 1 || jpeg.length > maximumBytes
                || maximumEdge < 1) {
            throw new IOException("Invalid VLT3 image size");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1
                || bounds.outWidth > 100_000 || bounds.outHeight > 100_000) {
            throw new IOException("Invalid VLT3 image");
        }
        int sample = 1;
        while ((long) Math.max(bounds.outWidth / sample, bounds.outHeight / sample)
                > (long) maximumEdge * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (decoded == null) {
            throw new IOException("Could not decode VLT3 image");
        }
        float scale = Math.min(1f, (float) maximumEdge
                / Math.max(decoded.getWidth(), decoded.getHeight()));
        if (scale >= 1f) {
            return decoded;
        }
        int width = Math.max(1, Math.round(decoded.getWidth() * scale));
        int height = Math.max(1, Math.round(decoded.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        if (scaled != decoded) {
            decoded.recycle();
        }
        return scaled;
    }
}
