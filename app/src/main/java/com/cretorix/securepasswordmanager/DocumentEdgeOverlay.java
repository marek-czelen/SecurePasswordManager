package com.cretorix.securepasswordmanager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Draws a dark mask outside the live document boundary. */
public final class DocumentEdgeOverlay extends View {
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private DocumentEdgeDetector.Quad quad;

    public DocumentEdgeOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        maskPaint.setColor(0x66000000);
        maskPaint.setStyle(Paint.Style.FILL);
        outlinePaint.setColor(0xff7cf4c4);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(getResources().getDisplayMetrics().density * 3f);
        outlinePaint.setStrokeJoin(Paint.Join.ROUND);
        outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        guidePaint.setColor(0xbfffffff);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        guidePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{12f, 8f}, 0));
    }

    public void setQuad(DocumentEdgeDetector.Quad value) {
        quad = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float horizontal = getWidth() * 0.05f;
        float vertical = getHeight() * 0.08f;
        RectF targetFrame = new RectF(horizontal, vertical,
                getWidth() - horizontal, getHeight() - vertical);
        float cornerRadius = 18f * getResources().getDisplayMetrics().density;
        if (quad == null) {
            canvas.drawRoundRect(targetFrame, cornerRadius, cornerRadius, guidePaint);
            return;
        }

        float scale = Math.max(getWidth(), getHeight() * quad.aspectRatio);
        float horizontalOffset = (getWidth() - scale) / 2f;
        float verticalOffset = (getHeight() - scale / quad.aspectRatio) / 2f;
        Path polygon = new Path();
        for (int index = 0; index < 4; index++) {
            float x = quad.points[index * 2] * scale + horizontalOffset;
            float y = quad.points[index * 2 + 1] * scale / quad.aspectRatio + verticalOffset;
            if (index == 0) {
                polygon.moveTo(x, y);
            } else {
                polygon.lineTo(x, y);
            }
        }
        polygon.close();

        Path outsideMask = new Path();
        outsideMask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        outsideMask.addPath(polygon);
        outsideMask.setFillType(Path.FillType.EVEN_ODD);
        canvas.drawPath(outsideMask, maskPaint);
        canvas.drawPath(polygon, outlinePaint);
        guidePaint.setAlpha(110);
        canvas.drawRoundRect(targetFrame, cornerRadius, cornerRadius, guidePaint);
        guidePaint.setAlpha(191);
    }
}
