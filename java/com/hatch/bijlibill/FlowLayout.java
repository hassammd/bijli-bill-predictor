package com.hatch.bijlibill;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.7.44: simple left-to-right flow layout — children sit on one
 * line until they no longer fit the available width, then the rest
 * flow to the next line. A child is NEVER truncated or clipped to
 * make it fit: the whole child moves down instead.
 *
 * Used for the bill amount row (Rs + amount + badge + stamp): with
 * a big amount (thousands) the badge + stamp flow to a second line
 * under the amount instead of the badge text being cut off at the
 * card edge. With a small amount everything stays on one line, as
 * before. Children on a line are bottom-aligned, so the badge and
 * the stamp sit at the amount's baseline like the old row did.
 *
 * Margins on the children are honoured (MarginLayoutParams).
 * The wrap decision lives in the pure-Java FlowMath (no Android
 * imports), so the math is unit-testable on the JVM without
 * android.jar (see TestBillFetch runV3744Tests: Rs 19,615 and
 * Rs 1,25,000 fixtures wrap to two lines; a small amount on a
 * wide screen stays on one line).
 */
public class FlowLayout extends ViewGroup {

    /**
     * v3.7.46: per-child flag for the last child on a line.
     * alignEnd = true pins that child's right edge (incl. its
     * right margin) exactly to the line's right edge — no dead
     * gap on the right. Used by the bill amount row: the status
     * column (stamp + status text) sits flush against the
     * card's right edge, whether it shares the line with the
     * amount or wrapped onto its own line. The amount group
     * itself never sets this — it stays left.
     */
    public static class FlowLayoutParams extends MarginLayoutParams {
        public boolean alignEnd = false;

        public FlowLayoutParams(int w, int h) {
            super(w, h);
        }

        public FlowLayoutParams(ViewGroup.LayoutParams p) {
            super(p);
            if (p instanceof FlowLayoutParams) {
                alignEnd = ((FlowLayoutParams) p).alignEnd;
            }
        }
    }

    public FlowLayout(Context ctx) {
        super(ctx);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new FlowLayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new FlowLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    private static MarginLayoutParams lpOf(View ch) {
        return (MarginLayoutParams) ch.getLayoutParams();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int avail = (widthMode == MeasureSpec.UNSPECIFIED)
                ? Integer.MAX_VALUE
                : Math.max(0, widthSize - getPaddingLeft()
                        - getPaddingRight());
        int x = 0;
        int y = 0;
        int lineH = 0;
        int maxW = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            measureChildWithMargins(ch,
                    MeasureSpec.makeMeasureSpec(avail,
                            MeasureSpec.AT_MOST),
                    0, heightMeasureSpec, 0);
            MarginLayoutParams lp = lpOf(ch);
            int cw = ch.getMeasuredWidth()
                    + lp.leftMargin + lp.rightMargin;
            int chh = ch.getMeasuredHeight()
                    + lp.topMargin + lp.bottomMargin;
            if (FlowMath.shouldWrap(x, cw, avail)) {
                maxW = Math.max(maxW, x);
                x = 0;
                y += lineH;
                lineH = 0;
            }
            x += cw;
            lineH = Math.max(lineH, chh);
        }
        maxW = Math.max(maxW, x);
        int height = y + lineH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(maxW + getPaddingLeft() + getPaddingRight(),
                        widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int avail = Math.max(0, (r - l) - getPaddingLeft()
                - getPaddingRight());
        int x = getPaddingLeft();
        int y = getPaddingTop();
        List<View> line = new ArrayList<View>();
        int lineW = 0;
        int lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            MarginLayoutParams lp = lpOf(ch);
            int cw = ch.getMeasuredWidth()
                    + lp.leftMargin + lp.rightMargin;
            int chh = ch.getMeasuredHeight()
                    + lp.topMargin + lp.bottomMargin;
            if (FlowMath.shouldWrap(lineW, cw, avail)) {
                placeLine(line, x, y, lineH, lineW, avail);
                y += lineH;
                line.clear();
                lineW = 0;
                lineH = 0;
            }
            line.add(ch);
            lineW += cw;
            lineH = Math.max(lineH, chh);
        }
        placeLine(line, x, y, lineH, lineW, avail);
    }

    /**
     * Lays one line's children out left to right, bottom-aligned.
     * v3.7.46: a last child with LayoutParams.alignEnd has its
     * right edge (incl. right margin) pinned exactly to the
     * line's right edge — no dead gap on the right. The wrap
     * rule guarantees the line fits (lineW &lt;= avail), so the
     * end-aligned child never overlaps the child before it:
     * its left edge is at startX + avail - rightMargin -
     * width, which is &gt;= its natural flow position.
     */
    private void placeLine(List<View> line, int startX, int top,
            int lineH, int lineW, int avail) {
        int x = startX;
        for (int i = 0; i < line.size(); i++) {
            View ch = line.get(i);
            MarginLayoutParams lp = lpOf(ch);
            int cw = ch.getMeasuredWidth();
            int chh = ch.getMeasuredHeight();
            // Bottom-align: short children (badge) sit on the same
            // baseline as the tallest child (the amount / stamp).
            int childTop = top + lp.topMargin
                    + (lineH - lp.topMargin - lp.bottomMargin - chh);
            int childLeft = x + lp.leftMargin;
            boolean alignEnd = i == line.size() - 1
                    && lp instanceof FlowLayoutParams
                    && ((FlowLayoutParams) lp).alignEnd;
            if (alignEnd) {
                childLeft = startX + avail - lp.rightMargin - cw;
            }
            ch.layout(childLeft, childTop,
                    childLeft + cw, childTop + chh);
            x += lp.leftMargin + cw + lp.rightMargin;
        }
    }
}
