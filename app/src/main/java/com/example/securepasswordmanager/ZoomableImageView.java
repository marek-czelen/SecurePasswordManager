package com.example.securepasswordmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/** Full-screen image view with pinch zoom, double-tap zoom, and panning. */
public final class ZoomableImageView extends AppCompatImageView {
    private static final float MAX_USER_SCALE = 6f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix displayMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Bitmap bitmap;
    private float userScale = 1f;
    private float lastTouchX;
    private float lastTouchY;

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (userScale > 1.05f) {
                    resetToFit();
                } else {
                    zoomTo(DOUBLE_TAP_SCALE, event.getX(), event.getY());
                }
                return true;
            }
        });
    }

    @Override
    public void setImageBitmap(Bitmap image) {
        bitmap = image;
        super.setImageBitmap(image);
        resetToFit();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetToFit();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && userScale > 1f) {
                    float deltaX = event.getX() - lastTouchX;
                    float deltaY = event.getY() - lastTouchY;
                    displayMatrix.postTranslate(deltaX, deltaY);
                    constrainTranslation();
                    setImageMatrix(displayMatrix);
                }
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void resetToFit() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        RectF source = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        RectF target = new RectF(0, 0, getWidth(), getHeight());
        displayMatrix.setRectToRect(source, target, Matrix.ScaleToFit.CENTER);
        userScale = 1f;
        setImageMatrix(displayMatrix);
    }

    private void scaleBy(float factor, float focusX, float focusY) {
        float nextScale = Math.max(1f, Math.min(MAX_USER_SCALE, userScale * factor));
        float appliedScale = nextScale / userScale;
        displayMatrix.postScale(appliedScale, appliedScale, focusX, focusY);
        userScale = nextScale;
        constrainTranslation();
        setImageMatrix(displayMatrix);
    }

    private void zoomTo(float scale, float focusX, float focusY) {
        displayMatrix.postScale(scale / userScale, scale / userScale, focusX, focusY);
        userScale = scale;
        constrainTranslation();
        setImageMatrix(displayMatrix);
    }

    private void constrainTranslation() {
        if (bitmap == null) {
            return;
        }
        RectF image = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        displayMatrix.mapRect(image);
        float deltaX = image.width() <= getWidth()
                ? (getWidth() - image.width()) / 2f - image.left
                : image.left > 0 ? -image.left
                : image.right < getWidth() ? getWidth() - image.right : 0;
        float deltaY = image.height() <= getHeight()
                ? (getHeight() - image.height()) / 2f - image.top
                : image.top > 0 ? -image.top
                : image.bottom < getHeight() ? getHeight() - image.bottom : 0;
        displayMatrix.postTranslate(deltaX, deltaY);
    }
}
