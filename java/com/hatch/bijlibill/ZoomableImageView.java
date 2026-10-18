package com.hatch.bijlibill;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

/**
 * v3.6.6: ImageView with pinch-zoom + pan (user request — bill shown as an
 * image instead of a WebView, so there are no WebView/ScrollView touch
 * conflicts). Single-finger drag when NOT zoomed is ignored (returns false)
 * so the outer ScrollView scrolls the page normally. When zoomed, all touch
 * is consumed for panning. Double-tap toggles zoom.
 */
public class ZoomableImageView extends ImageView {

    private final Matrix matrix = new Matrix();
    private final float[] vals = new float[9];

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 4f;

    private float scale = MIN_SCALE;
    private float lastX, lastY;

    // Pinch state
    private float pinchStartDist;
    private float pinchStartScale;
    private PointF pinchMid = new PointF();
    private boolean pinching;

    private final android.view.GestureDetector gestureDetector;

    // Image dimensions (set when bitmap is set)
    private int imgW, imgH;
    private int viewW, viewH;

    public ZoomableImageView(Context ctx) {
        super(ctx);
        setScaleType(ScaleType.MATRIX);
        setImageMatrix(matrix);
        gestureDetector = new android.view.GestureDetector(ctx,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (scale > MIN_SCALE) {
                            applyScale(MIN_SCALE, e.getX(), e.getY());
                        } else {
                            applyScale(2f, e.getX(), e.getY());
                        }
                        getParent().requestDisallowInterceptTouchEvent(scale > MIN_SCALE);
                        return true;
                    }
                });
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        if (bm != null) {
            imgW = bm.getWidth();
            imgH = bm.getHeight();
        }
        resetZoom();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        if (scale == MIN_SCALE) fitToWidth();
    }

    /** Fit image width to view width, top-aligned. */
    private void fitToWidth() {
        if (imgW == 0 || viewW == 0) return;
        scale = MIN_SCALE;
        matrix.reset();
        float s = (float) viewW / imgW;
        matrix.postScale(s, s);
        // Height of scaled image; view height is set by layout (wrap-ish).
        setImageMatrix(matrix);
    }

    private void resetZoom() {
        scale = MIN_SCALE;
        matrix.reset();
        fitToWidth();
    }

    /** Apply scale around a focal point, then clamp pan. */
    private void applyScale(float newScale, float fx, float fy) {
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        float factor = newScale / scale;
        scale = newScale;
        matrix.postScale(factor, factor, fx, fy);
        clampPan();
        setImageMatrix(matrix);
    }

    /** Keep the image within reasonable bounds. */
    private void clampPan() {
        matrix.getValues(vals);
        float tx = vals[Matrix.MTRANS_X];
        float ty = vals[Matrix.MTRANS_Y];
        float sw = imgW * vals[Matrix.MSCALE_X];
        float sh = imgH * vals[Matrix.MSCALE_Y];

        float dx = 0, dy = 0;
        if (sw <= viewW) {
            dx = -tx; // center horizontally when smaller than view
            // actually left-align: dx = -tx puts x at 0
        } else {
            if (tx > 0) dx = -tx;
            else if (tx + sw < viewW) dx = viewW - (tx + sw);
        }
        if (sh <= viewH) {
            dy = -ty;
        } else {
            if (ty > 0) dy = -ty;
            else if (ty + sh < viewH) dy = viewH - (ty + sh);
        }
        if (dx != 0 || dy != 0) {
            matrix.postTranslate(dx, dy);
        }
    }

    private float pinchDist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // Let the gesture detector check for double-tap first.
        gestureDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    pinching = true;
                    pinchStartDist = pinchDist(e);
                    pinchStartScale = scale;
                    pinchMid.set((e.getX(0) + e.getX(1)) / 2,
                            (e.getY(0) + e.getY(1)) / 2);
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() == 2) {
                    float d = pinchDist(e);
                    if (pinchStartDist > 0) {
                        applyScale(pinchStartScale * (d / pinchStartDist),
                                pinchMid.x, pinchMid.y);
                    }
                    return true;
                }
                if (scale > MIN_SCALE && e.getPointerCount() == 1) {
                    // Zoomed: pan in all directions
                    float dx = e.getX() - lastX;
                    float dy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    matrix.postTranslate(dx, dy);
                    clampPan();
                    setImageMatrix(matrix);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                // Not zoomed, single finger: don't consume — parent scrolls.
                lastX = e.getX();
                lastY = e.getY();
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pinching = false;
                if (scale <= MIN_SCALE) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                pinching = false;
                if (scale <= MIN_SCALE) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;
        }
        return true;
    }
}
