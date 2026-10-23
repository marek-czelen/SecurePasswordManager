package com.cretorix.securepasswordmanager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.opencv.core.Point;

import java.util.Arrays;

/** Lets the user confirm or adjust the detected document corners on a captured photo. */
public final class DocumentReviewOverlay extends View {
    interface OnCornersChangedListener {
        void onCornersChanged(boolean valid);
    }

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float handleRadius;
    private final float touchRadius;
    private final float touchSlop;
    private final Matrix imageTransform = new Matrix();
    private float[] corners;
    private int imageWidth;
    private int imageHeight;
    private int draggedCorner = -1;
    private int pendingCornerCount;
    private float downX;
    private float downY;
    private boolean manualTapCandidate;
    private ZoomableImageView imageView;
    private OnCornersChangedListener listener;

    public DocumentReviewOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        handleRadius = 10f * density;
        touchRadius = 32f * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setClickable(true);

        maskPaint.setColor(0x66000000);
        maskPaint.setStyle(Paint.Style.FILL);
        outlinePaint.setColor(0xff7cf4c4);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(3f * density);
        outlinePaint.setStrokeJoin(Paint.Join.ROUND);
        outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        handleFillPaint.setColor(0xff7cf4c4);
        handleFillPaint.setStyle(Paint.Style.FILL);
        handleStrokePaint.setColor(0xffffffff);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(2f * density);
        numberPaint.setColor(0xff12352b);
        numberPaint.setTextAlign(Paint.Align.CENTER);
        numberPaint.setTextSize(12f * density);
        numberPaint.setFakeBoldText(true);
    }

    void setOnCornersChangedListener(OnCornersChangedListener value) {
        listener = value;
    }

    void setImageSize(int width, int height) {
        imageWidth = width;
        imageHeight = height;
        invalidate();
    }

    void setImageView(ZoomableImageView value) {
        imageView = value;
    }

    void setImageTransform(Matrix value) {
        imageTransform.set(value);
        invalidate();
    }

    void setCorners(Point[] points, int sourceWidth, int sourceHeight) {
        if (points == null || points.length != 4
                || sourceWidth <= 0 || sourceHeight <= 0) {
            clearCorners();
            return;
        }
        corners = new float[8];
        for (int index = 0; index < points.length; index++) {
            corners[index * 2] = (float) (points[index].x / sourceWidth);
            corners[index * 2 + 1] = (float) (points[index].y / sourceHeight);
        }
        pendingCornerCount = 4;
        notifyCornersChanged();
        invalidate();
    }

    void clearCorners() {
        corners = null;
        pendingCornerCount = 0;
        draggedCorner = -1;
        notifyCornersChanged();
        invalidate();
    }

    float[] getNormalizedCorners() {
        if (!isSelectionValid()) {
            return null;
        }
        return corners.clone();
    }

    boolean isSelectionValid() {
        if (corners == null || pendingCornerCount != 4 || corners.length != 8) {
            return false;
        }
        double signedArea = 0;
        double previousCross = 0;
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            int afterNext = (index + 2) % 4;
            float x = corners[index * 2];
            float y = corners[index * 2 + 1];
            float nextX = corners[next * 2];
            float nextY = corners[next * 2 + 1];
            float afterX = corners[afterNext * 2];
            float afterY = corners[afterNext * 2 + 1];
            if (!Float.isFinite(x) || !Float.isFinite(y)
                    || x < 0 || x > 1 || y < 0 || y > 1) {
                return false;
            }
            signedArea += x * nextY - nextX * y;
            double cross = (nextX - x) * (afterY - nextY)
                    - (nextY - y) * (afterX - nextX);
            if (Math.abs(cross) < 1e-5
                    || (index > 0 && cross * previousCross < 0)) {
                return false;
            }
            previousCross = cross;
        }
        return Math.abs(signedArea) >= 0.002;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (corners == null) {
            return;
        }

        Path polygon = new Path();
        for (int index = 0; index < Math.min(pendingCornerCount, 4); index++) {
            PointF point = toViewPoint(corners[index * 2], corners[index * 2 + 1]);
            if (index == 0) {
                polygon.moveTo(point.x, point.y);
            } else {
                polygon.lineTo(point.x, point.y);
            }
        }
        if (pendingCornerCount == 4) {
            polygon.close();
            Path outsideMask = new Path();
            outsideMask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            outsideMask.addPath(polygon);
            outsideMask.setFillType(Path.FillType.EVEN_ODD);
            canvas.drawPath(outsideMask, maskPaint);
        }
        canvas.drawPath(polygon, outlinePaint);
        for (int index = 0; index < pendingCornerCount; index++) {
            PointF point = toViewPoint(corners[index * 2], corners[index * 2 + 1]);
            canvas.drawCircle(point.x, point.y, handleRadius, handleFillPaint);
            canvas.drawCircle(point.x, point.y, handleRadius, handleStrokePaint);
            canvas.drawText(Integer.toString(index + 1), point.x,
                    point.y + numberPaint.getTextSize() * 0.35f, numberPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (corners != null && pendingCornerCount == 4) {
                    draggedCorner = findCorner(event.getX(), event.getY());
                    if (draggedCorner >= 0) {
                        return true;
                    }
                }
                PointF imagePoint = normalizedAt(event.getX(), event.getY(), false);
                manualTapCandidate = pendingCornerCount < 4 && imagePoint != null;
                downX = event.getX();
                downY = event.getY();
                forwardToImage(event);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                manualTapCandidate = false;
                forwardToImage(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggedCorner >= 0) {
                    setCorner(draggedCorner, event.getX(), event.getY());
                    notifyCornersChanged();
                    invalidate();
                    return true;
                }
                if (event.getPointerCount() > 1
                        || Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    manualTapCandidate = false;
                }
                forwardToImage(event);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                manualTapCandidate = false;
                forwardToImage(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (draggedCorner >= 0) {
                    draggedCorner = -1;
                    return true;
                }
                PointF selectedCorner = manualTapCandidate
                        && Math.hypot(event.getX() - downX, event.getY() - downY) <= touchSlop
                        ? normalizedAt(event.getX(), event.getY(), false) : null;
                forwardToImage(event);
                manualTapCandidate = false;
                if (selectedCorner != null && pendingCornerCount < 4) {
                    addCorner(selectedCorner);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggedCorner = -1;
                manualTapCandidate = false;
                forwardToImage(event);
                return true;
            default:
                forwardToImage(event);
                return true;
        }
    }

    private void addCorner(PointF point) {
        if (corners == null) {
            corners = new float[8];
        }
        corners[pendingCornerCount * 2] = point.x;
        corners[pendingCornerCount * 2 + 1] = point.y;
        pendingCornerCount++;
        if (pendingCornerCount == 4) {
            orderCorners();
        }
        notifyCornersChanged();
        invalidate();
    }

    private PointF normalizedAt(float x, float y, boolean clamp) {
        Matrix inverse = new Matrix();
        if (!imageTransform.invert(inverse)) {
            return null;
        }
        float[] point = {x, y};
        inverse.mapPoints(point);
        if (!clamp && (point[0] < 0 || point[0] > imageWidth
                || point[1] < 0 || point[1] > imageHeight)) {
            return null;
        }
        return new PointF(Math.max(0f, Math.min(1f, point[0] / imageWidth)),
                Math.max(0f, Math.min(1f, point[1] / imageHeight)));
    }

    private PointF toViewPoint(float x, float y) {
        float[] point = {x * imageWidth, y * imageHeight};
        imageTransform.mapPoints(point);
        return new PointF(point[0], point[1]);
    }

    private void setCorner(int index, float x, float y) {
        PointF point = normalizedAt(x, y, true);
        if (point != null) {
            corners[index * 2] = point.x;
            corners[index * 2 + 1] = point.y;
        }
    }

    private int findCorner(float x, float y) {
        for (int index = 0; index < 4; index++) {
            PointF point = toViewPoint(corners[index * 2], corners[index * 2 + 1]);
            if (Math.hypot(point.x - x, point.y - y) <= touchRadius) {
                return index;
            }
        }
        return -1;
    }

    private void forwardToImage(MotionEvent event) {
        if (imageView != null) {
            imageView.dispatchTouchEvent(event);
        }
    }

    private void orderCorners() {
        PointF[] ordered = new PointF[4];
        float centerX = 0;
        float centerY = 0;
        for (int index = 0; index < ordered.length; index++) {
            ordered[index] = new PointF(corners[index * 2], corners[index * 2 + 1]);
            centerX += ordered[index].x;
            centerY += ordered[index].y;
        }
        centerX /= ordered.length;
        centerY /= ordered.length;
        final float centroidX = centerX;
        final float centroidY = centerY;
        Arrays.sort(ordered, (first, second) -> Double.compare(
                Math.atan2(first.y - centroidY, first.x - centroidX),
                Math.atan2(second.y - centroidY, second.x - centroidX)));
        int start = 0;
        float smallestSum = Float.MAX_VALUE;
        for (int index = 0; index < ordered.length; index++) {
            float sum = ordered[index].x + ordered[index].y;
            if (sum < smallestSum) {
                smallestSum = sum;
                start = index;
            }
        }
        float[] sorted = new float[8];
        for (int index = 0; index < ordered.length; index++) {
            PointF point = ordered[(start + index) % ordered.length];
            sorted[index * 2] = point.x;
            sorted[index * 2 + 1] = point.y;
        }
        corners = sorted;
    }

    private void notifyCornersChanged() {
        if (listener != null) {
            listener.onCornersChanged(isSelectionValid());
        }
    }
}
