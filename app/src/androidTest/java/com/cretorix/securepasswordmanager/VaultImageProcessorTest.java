package com.cretorix.securepasswordmanager;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.cretorix.securepasswordmanager.crypto.VaultImageProcessor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class VaultImageProcessorTest {
    @Test
    public void generatedThumbnailRespectsV3Limit() throws Exception {
        Bitmap source = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 92, jpeg));
        source.recycle();

        byte[] thumbnail = VaultImageProcessor.createThumbnail(jpeg.toByteArray());
        assertTrue(thumbnail.length > 0);
        assertTrue(thumbnail.length <= VaultImageProcessor.MAX_THUMBNAIL_BYTES);
    }
}
