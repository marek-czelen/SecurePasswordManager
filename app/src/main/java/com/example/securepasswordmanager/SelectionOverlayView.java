package com.example.securepasswordmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive OCR overlay with pinch-to-zoom and pan. The user draws, moves, resizes and
 * deletes selection rectangles and assigns each to a role (name / login / password).
 * Selections are stored in bitmap pixel coordinates so they stay accurate at any zoom.
 * The app may pre-suggest boxes, but nothing is confirmed automatically.
 */
public final class SelectionOverlayView extends View {

    public static final int ROLE_NAME = 0;
    public static final int ROLE_LOGIN = 1;
    public static final int ROLE_PASSWORD = 2;

    private enum Mode { NONE, CREATE, MOVE, RESIZE }

    private static final int[] ROLE_COLORS = {
            Color.parseColor("#2E7D32"),
            Color.parseColor("#1565C0"),
            Color.parseColor("#C62828")
    };
    private static final float MAX_ZOOM = 6f;

    private static final class Box {
        final RectF rect; // bitmap coordinates
        final int role;

        Box(RectF rect, int role) {
            this.rect = rect;
            this.role = role;
        }
    }

    private final List<Box> boxes = new ArrayList<>();
    private final Matrix matrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] values = new float[9];
    private final RectF screenRect = new RectF();
    private final float[] point = new float[2];

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final float handleRadius;
    private final ScaleGestureDetector scaleDetector;

    private Bitmap bitmap;
    private float fitScale = 1f;
    private Box selected;
    private Mode mode = Mode.NONE;
    private int pendingRole = -1;
    private int activeHandle = -1;
    private float lastBitmapX;
    private float lastBitmapY;
    private float createStartX;
    private float createStartY;
    private boolean transforming;
    private float lastFocusX;
    private float lastFocusY;
    private String[] roleLabels = {"Nazwa", "Login", "Hasło"};

    public SelectionOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        handleRadius = 11 * density;
        strokePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(13 * density);
        labelBg.setStyle(Paint.Style.FILL);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    public void setRoleLabels(String name, String login, String password) {
        roleLabels = new String[]{name, login, password};
    }

    public void setBitmap(Bitmap value) {
        bitmap = value;
        resetMatrix();
        invalidate();
    }

    public void setPendingRole(int role) {
        pendingRole = role;
    }

    public boolean hasRole(int role) {
        return findByRole(role) != null;
    }

    public void deleteSelected() {
        if (selected != null) {
            boxes.remove(selected);
            selected = null;
            invalidate();
        }
    }

    /** Adds a pre-suggested rectangle given in bitmap pixel coordinates. */
    public void addSuggestion(int role, Rect bitmapRect) {
        if (bitmap == null || hasRole(role)) {
            return;
        }
        boxes.add(new Box(new RectF(bitmapRect), role));
        invalidate();
    }

    /** Returns the selection rectangle in bitmap pixel coordinates, or null. */
    @Nullable
    public Rect getBitmapRect(int role) {
        Box box = findByRole(role);
        if (box == null || bitmap == null) {
            return null;
        }
        int left = clamp(Math.round(box.rect.left), 0, bitmap.getWidth() - 1);
        int top = clamp(Math.round(box.rect.top), 0, bitmap.getHeight() - 1);
        int right = clamp(Math.round(box.rect.right), left + 1, bitmap.getWidth());
        int bottom = clamp(Math.round(box.rect.bottom), top + 1, bitmap.getHeight());
        return new Rect(left, top, right, bottom);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetMatrix();
    }

    private void resetMatrix() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        fitScale = Math.min(getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight());
        float dx = (getWidth() - bitmap.getWidth() * fitScale) / 2f;
        float dy = (getHeight() - bitmap.getHeight() * fitScale) / 2f;
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate(dx, dy);
    }

    private float currentScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            return;
        }
        canvas.drawBitmap(bitmap, matrix, bitmapPaint);
        for (Box box : boxes) {
            matrix.mapRect(screenRect, box.rect);
            int color = ROLE_COLORS[box.role];
            fillPaint.setColor(withAlpha(color, box == selected ? 55 : 32));
            canvas.drawRect(screenRect, fillPaint);
            strokePaint.setColor(color);
            strokePaint.setStrokeWidth((box == selected ? 3f : 2f) * density);
            canvas.drawRect(screenRect, strokePaint);

            String label = roleLabels[box.role];
            float tw = labelPaint.measureText(label);
            float pad = 6 * density;
            float lh = 20 * density;
            labelBg.setColor(color);
            canvas.drawRect(screenRect.left, screenRect.top - lh,
                    screenRect.left + tw + pad * 2, screenRect.top, labelBg);
            canvas.drawText(label, screenRect.left + pad, screenRect.top - 5 * density, labelPaint);

            if (box == selected) {
                drawHandle(canvas, screenRect.left, screenRect.top, color);
                drawHandle(canvas, screenRect.right, screenRect.top, color);
                drawHandle(canvas, screenRect.right, screenRect.bottom, color);
                drawHandle(canvas, screenRect.left, screenRect.bottom, color);
            }
        }
    }

    private void drawHandle(Canvas canvas, float x, float y, int color) {
        canvas.drawCircle(x, y, handleRadius, handlePaint);
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(2 * density);
        canvas.drawCircle(x, y, handleRadius, strokePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                transforming = false;
                startSelection(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Second finger: this is a zoom/pan gesture, cancel any selection draft.
                cancelTransientCreate();
                transforming = true;
                mode = Mode.NONE;
                updateFocus(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (transforming || event.getPointerCount() > 1) {
                    handlePan(event);
                } else if (!scaleDetector.isInProgress()) {
                    moveSelection(event.getX(), event.getY());
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                updateFocus(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!transforming) {
                    endSelection();
                }
                transforming = false;
                break;
            default:
                break;
        }
        return true;
    }

    private void updateFocus(MotionEvent event) {
        float x = 0;
        float y = 0;
        int count = event.getPointerCount();
        for (int i = 0; i < count; i++) {
            x += event.getX(i);
            y += event.getY(i);
        }
        lastFocusX = x / count;
        lastFocusY = y / count;
    }

    private void handlePan(MotionEvent event) {
        float x = 0;
        float y = 0;
        int count = event.getPointerCount();
        for (int i = 0; i < count; i++) {
            x += event.getX(i);
            y += event.getY(i);
        }
        x /= count;
        y /= count;
        matrix.postTranslate(x - lastFocusX, y - lastFocusY);
        lastFocusX = x;
        lastFocusY = y;
        clampMatrix();
        invalidate();
    }

    private void startSelection(float sx, float sy) {
        toBitmap(sx, sy);
        float bx = point[0];
        float by = point[1];
        if (pendingRole >= 0) {
            Box existing = findByRole(pendingRole);
            if (existing != null) {
                boxes.remove(existing);
            }
            selected = new Box(new RectF(bx, by, bx, by), pendingRole);
            boxes.add(selected);
            mode = Mode.CREATE;
            createStartX = bx;
            createStartY = by;
            invalidate();
            return;
        }
        if (selected != null) {
            int handle = hitHandle(selected, sx, sy);
            if (handle >= 0) {
                mode = Mode.RESIZE;
                activeHandle = handle;
                return;
            }
        }
        Box hit = topBoxAt(bx, by);
        if (hit != null) {
            selected = hit;
            mode = Mode.MOVE;
            lastBitmapX = bx;
            lastBitmapY = by;
        } else {
            selected = null;
            mode = Mode.NONE;
        }
        invalidate();
    }

    private void moveSelection(float sx, float sy) {
        if (selected == null || mode == Mode.NONE) {
            return;
        }
        toBitmap(sx, sy);
        float bx = clampX(point[0]);
        float by = clampY(point[1]);
        switch (mode) {
            case CREATE:
                selected.rect.set(Math.min(createStartX, bx), Math.min(createStartY, by),
                        Math.max(createStartX, bx), Math.max(createStartY, by));
                break;
            case RESIZE:
                resize(selected.rect, activeHandle, bx, by);
                break;
            case MOVE:
                offset(selected.rect, bx - lastBitmapX, by - lastBitmapY);
                lastBitmapX = bx;
                lastBitmapY = by;
                break;
            default:
                return;
        }
        invalidate();
    }

    private void endSelection() {
        if (mode == Mode.CREATE && selected != null) {
            normalize(selected.rect);
            float minSize = 10 * density / Math.max(currentScale(), 0.001f);
            if (selected.rect.width() < minSize || selected.rect.height() < minSize) {
                boxes.remove(selected);
                selected = null;
            }
            pendingRole = -1;
        } else if (selected != null) {
            normalize(selected.rect);
        }
        mode = Mode.NONE;
        activeHandle = -1;
        invalidate();
    }

    private void cancelTransientCreate() {
        if (mode == Mode.CREATE && selected != null) {
            boxes.remove(selected);
            selected = null;
        }
        mode = Mode.NONE;
    }

    private void resize(RectF rect, int handle, float x, float y) {
        switch (handle) {
            case 0: rect.left = x; rect.top = y; break;
            case 1: rect.right = x; rect.top = y; break;
            case 2: rect.right = x; rect.bottom = y; break;
            case 3: rect.left = x; rect.bottom = y; break;
            default: break;
        }
    }

    private void offset(RectF rect, float dx, float dy) {
        if (rect.left + dx < 0) {
            dx = -rect.left;
        }
        if (rect.right + dx > bitmap.getWidth()) {
            dx = bitmap.getWidth() - rect.right;
        }
        if (rect.top + dy < 0) {
            dy = -rect.top;
        }
        if (rect.bottom + dy > bitmap.getHeight()) {
            dy = bitmap.getHeight() - rect.bottom;
        }
        rect.offset(dx, dy);
    }

    private void normalize(RectF rect) {
        float left = Math.max(0, Math.min(rect.left, rect.right));
        float right = Math.min(bitmap.getWidth(), Math.max(rect.left, rect.right));
        float top = Math.max(0, Math.min(rect.top, rect.bottom));
        float bottom = Math.min(bitmap.getHeight(), Math.max(rect.top, rect.bottom));
        rect.set(left, top, right, bottom);
    }

    private int hitHandle(Box box, float sx, float sy) {
        matrix.mapRect(screenRect, box.rect);
        float r = handleRadius * 1.8f;
        if (near(sx, sy, screenRect.left, screenRect.top, r)) return 0;
        if (near(sx, sy, screenRect.right, screenRect.top, r)) return 1;
        if (near(sx, sy, screenRect.right, screenRect.bottom, r)) return 2;
        if (near(sx, sy, screenRect.left, screenRect.bottom, r)) return 3;
        return -1;
    }

    private static boolean near(float x, float y, float px, float py, float r) {
        float dx = x - px;
        float dy = y - py;
        return dx * dx + dy * dy <= r * r;
    }

    private Box topBoxAt(float bx, float by) {
        for (int i = boxes.size() - 1; i >= 0; i--) {
            if (boxes.get(i).rect.contains(bx, by)) {
                return boxes.get(i);
            }
        }
        return null;
    }

    private Box findByRole(int role) {
        for (Box box : boxes) {
            if (box.role == role) {
                return box;
            }
        }
        return null;
    }

    private void toBitmap(float sx, float sy) {
        matrix.invert(inverse);
        point[0] = sx;
        point[1] = sy;
        inverse.mapPoints(point);
    }

    private float clampX(float x) {
        return Math.max(0, Math.min(x, bitmap.getWidth()));
    }

    private float clampY(float y) {
        return Math.max(0, Math.min(y, bitmap.getHeight()));
    }

    private void clampMatrix() {
        if (bitmap == null) {
            return;
        }
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float contentW = bitmap.getWidth() * scale;
        float contentH = bitmap.getHeight() * scale;
        float newX = clampTranslation(transX, contentW, getWidth());
        float newY = clampTranslation(transY, contentH, getHeight());
        matrix.postTranslate(newX - transX, newY - transY);
    }

    private static float clampTranslation(float trans, float contentSize, float viewSize) {
        if (contentSize <= viewSize) {
            return (viewSize - contentSize) / 2f;
        }
        return Math.max(viewSize - contentSize, Math.min(trans, 0));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float scale = currentScale();
            float projected = scale * factor;
            float minScale = fitScale;
            float maxScale = fitScale * MAX_ZOOM;
            if (projected < minScale) {
                factor = minScale / scale;
            } else if (projected > maxScale) {
                factor = maxScale / scale;
            }
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            clampMatrix();
            invalidate();
            return true;
        }
    }
}
