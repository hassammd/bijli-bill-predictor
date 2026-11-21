package com.hatch.bijlibill;

/**
 * v3.7.44: pure-Java line-break math for FlowLayout — NO Android
 * imports, so the JVM test suite can call it without android.jar
 * on the runtime classpath (TestBillFetch runs on plain JVM).
 *
 * One rule, used everywhere: a child wraps to the next line only
 * when something is already on the line (x &gt; 0) AND it would
 * overflow the available width. A child wider than one line still
 * gets its own line (never dropped, never split).
 */
public final class FlowMath {

    private FlowMath() {
    }

    /** True when the next child (width w) no longer fits on the
     *  current line (x already used) of the given available width. */
    public static boolean shouldWrap(int x, int w, int availWidth) {
        return x > 0 && x + w > availWidth;
    }

    /** How many lines the given child widths need in the available
     *  width. Empty input needs no lines. */
    public static int lineCount(int[] widths, int availWidth) {
        if (widths == null || widths.length == 0) return 0;
        int lines = 1;
        int x = 0;
        for (int w : widths) {
            if (shouldWrap(x, w, availWidth)) {
                lines++;
                x = 0;
            }
            x += w;
        }
        return lines;
    }
}
