package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.2.1 OCR hardening — pure Java image helpers, NO Android
 * dependencies. Headless-unit-testable (see TestBillFetch.runV221Tests).
 *
 * The user's live-test complaint: OCR was too weak on real meter
 * videos (seven-segment LCD digits, motion blur, small digits,
 * inverted LCD polarity). This class provides:
 *
 *  1. sharpness() — Laplacian variance per frame; the pipeline OCRs
 *     only the sharpest frames instead of wasting calls on blurry ones.
 *  2. Preprocessing variants per frame, all operating on int[] ARGB
 *     pixels (the Android Bitmap <-> int[] conversion lives in
 *     MeterOcr, at the boundary):
 *       - ENHANCED: grayscale -> percentile contrast stretch -> 2x
 *         bilinear upscale (LCD digits get bigger and punchier);
 *       - INVERTED: luminance inversion (some meters show light digits
 *         on dark glass, others the reverse);
 *       - CROP: center 60% at 2x (the meter is usually centered when
 *         filming; drops distracting background text).
 *  3. buildOcrPlan() — the bounded job list: sharpest 8 frames x
 *     {ORIGINAL, ENHANCED, INVERTED} + CROP of the top-2 sharpest =
 *     max 26 OCR invocations. Every pass feeds KwhAnchorPicker, where
 *     a kWh-anchored candidate from ANY pass wins.
 */
public final class FramePrep {

    private FramePrep() {}

    /** Hard cap on ML Kit invocations per video (latency bound). */
    public static final int MAX_OCR_CALLS = 26;
    /** How many of the sharpest frames get OCR passes. */
    public static final int MAX_SHARP_FRAMES = 8;
    /** Center-crop passes are only worth it on the sharpest frames. */
    public static final int CROP_TOP_N = 2;

    /** OCR input variants for one frame. */
    public enum Variant { ORIGINAL, ENHANCED, INVERTED, CROP }

    /** One bounded OCR job: frame index (into the sharpest list). */
    public static final class Job {
        public final int frameIdx;
        public final Variant variant;

        public Job(int frameIdx, Variant variant) {
            this.frameIdx = frameIdx;
            this.variant = variant;
        }
    }

    /**
     * Builds the bounded OCR job list for the sharpest frames.
     * Order: all ORIGINALs first (fastest path to a kWh anchor), then
     * ENHANCED, then INVERTED, then CROP passes of the top frames.
     * Size is always &lt;= MAX_OCR_CALLS. Pure.
     */
    public static List<Job> buildOcrPlan(int sharpFrameCount) {
        int n = Math.max(0, Math.min(sharpFrameCount, MAX_SHARP_FRAMES));
        List<Job> jobs = new ArrayList<Job>(MAX_OCR_CALLS);
        for (int i = 0; i < n; i++) {
            jobs.add(new Job(i, Variant.ORIGINAL));
        }
        for (int i = 0; i < n; i++) {
            jobs.add(new Job(i, Variant.ENHANCED));
        }
        for (int i = 0; i < n; i++) {
            jobs.add(new Job(i, Variant.INVERTED));
        }
        for (int i = 0; i < Math.min(CROP_TOP_N, n); i++) {
            jobs.add(new Job(i, Variant.CROP));
        }
        return jobs;
    }

    /**
     * Sharpness score: variance of the Laplacian over the grayscale
     * image. Higher = sharper. A uniform (fully blurred) frame scores
     * exactly 0. Pure.
     */
    public static double sharpness(int[] argb, int w, int h) {
        if (argb == null || w < 3 || h < 3
                || argb.length < w * h) {
            return 0.0;
        }
        // Grayscale row cache to avoid recomputation.
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int c = gray(argb[y * w + x]);
                int lap = 4 * c
                        - gray(argb[y * w + x - 1])
                        - gray(argb[y * w + x + 1])
                        - gray(argb[(y - 1) * w + x])
                        - gray(argb[(y + 1) * w + x]);
                sum += lap;
                sumSq += (double) lap * lap;
                count++;
            }
        }
        if (count == 0) return 0.0;
        double mean = sum / count;
        return sumSq / count - mean * mean;
    }

    /**
     * Indices of the n highest scores, descending; ties keep the
     * original (lower-index) frame first — stable. Pure.
     */
    public static int[] topSharpestIndices(double[] scores, int n) {
        if (scores == null || n <= 0) return new int[0];
        int m = Math.min(n, scores.length);
        int[] idx = new int[scores.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        // Insertion sort: m is tiny (<=12), stable by construction.
        for (int i = 1; i < idx.length; i++) {
            int key = idx[i];
            int j = i - 1;
            while (j >= 0 && scores[idx[j]] < scores[key]) {
                idx[j + 1] = idx[j];
                j--;
            }
            idx[j + 1] = key;
        }
        int[] out = new int[m];
        System.arraycopy(idx, 0, out, 0, m);
        return out;
    }

    /** ARGB -> 0..255 luminance (Rec. 601). */
    public static int gray(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** Full ARGB array -> grayscale int[] (0..255 per pixel). Pure. */
    public static int[] toGrayscale(int[] argb) {
        if (argb == null) return new int[0];
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) out[i] = gray(argb[i]);
        return out;
    }

    /**
     * Percentile contrast stretch: maps [p2, p98] -> [0, 255].
     * Degenerate (p98 &lt;= p2) returns the input unchanged. Pure.
     */
    public static int[] contrastStretch(int[] gray) {
        if (gray == null || gray.length == 0) return new int[0];
        int[] hist = new int[256];
        for (int v : gray) {
            int c = v < 0 ? 0 : (v > 255 ? 255 : v);
            hist[c]++;
        }
        int total = gray.length;
        int lo = percentile(hist, total, 2);
        int hi = percentile(hist, total, 98);
        if (hi <= lo) {
            int[] copy = new int[gray.length];
            System.arraycopy(gray, 0, copy, 0, gray.length);
            return copy;
        }
        int[] out = new int[gray.length];
        int span = hi - lo;
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i];
            int mapped = v <= lo ? 0 : (v >= hi ? 255
                    : (v - lo) * 255 / span);
            out[i] = mapped;
        }
        return out;
    }

    private static int percentile(int[] hist, int total, int p) {
        long target = (long) total * p / 100L;
        long acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc > target) return i;
        }
        return 255;
    }

    /**
     * 2x bilinear upscale of a grayscale int[] (w,h) -> (2w,2h). Pure.
     */
    public static int[] upscale2x(int[] gray, int w, int h) {
        if (gray == null || w < 1 || h < 1
                || gray.length < w * h) {
            return new int[0];
        }
        int w2 = w * 2;
        int h2 = h * 2;
        int[] out = new int[w2 * h2];
        for (int y = 0; y < h2; y++) {
            double sy = (y + 0.5) / 2.0 - 0.5;
            int y0 = (int) Math.floor(sy);
            int y1 = y0 + 1;
            double fy = sy - y0;
            if (y0 < 0) { y0 = 0; fy = 0; }
            if (y1 >= h) { y1 = h - 1; }
            for (int x = 0; x < w2; x++) {
                double sx = (x + 0.5) / 2.0 - 0.5;
                int x0 = (int) Math.floor(sx);
                int x1 = x0 + 1;
                double fx = sx - x0;
                if (x0 < 0) { x0 = 0; fx = 0; }
                if (x1 >= w) { x1 = w - 1; }
                double v = gray[y0 * w + x0] * (1 - fx) * (1 - fy)
                        + gray[y0 * w + x1] * fx * (1 - fy)
                        + gray[y1 * w + x0] * (1 - fx) * fy
                        + gray[y1 * w + x1] * fx * fy;
                out[y * w2 + x] = (int) Math.round(v);
            }
        }
        return out;
    }

    /** Luminance inversion of a grayscale int[] (LCD polarity). Pure. */
    public static int[] invert(int[] gray) {
        if (gray == null) return new int[0];
        int[] out = new int[gray.length];
        for (int i = 0; i < gray.length; i++) out[i] = 255 - gray[i];
        return out;
    }

    /**
     * Center crop of a grayscale int[]: middle fracW x fracH region.
     * (w,h,0.6f,0.6f) keeps the centered meter, drops background text.
     * Pure.
     */
    public static int[] centerCrop(int[] gray, int w, int h, float fracW,
            float fracH) {
        if (gray == null || w < 1 || h < 1
                || gray.length < w * h) {
            return new int[0];
        }
        int cw = Math.max(1, (int) (w * fracW));
        int ch = Math.max(1, (int) (h * fracH));
        int x0 = (w - cw) / 2;
        int y0 = (h - ch) / 2;
        int[] out = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            System.arraycopy(gray, (y0 + y) * w + x0, out, y * cw, cw);
        }
        return out;
    }

    /**
     * The ENHANCED variant pipeline: grayscale -> contrast stretch ->
     * 2x upscale. Returns {pixels, width, height} packed in an int[3]
     * header + pixels: out[0]=w, out[1]=h, pixels follow. Pure.
     */
    public static int[] enhanced(int[] argb, int w, int h) {
        int[] g = toGrayscale(argb);
        int[] s = contrastStretch(g);
        int[] u = upscale2x(s, w, h);
        int[] packed = new int[2 + u.length];
        packed[0] = w * 2;
        packed[1] = h * 2;
        System.arraycopy(u, 0, packed, 2, u.length);
        return packed;
    }

    /** Packs a grayscale int[] as opaque ARGB for Bitmap.createBitmap. */
    public static int[] toArgb(int[] gray) {
        if (gray == null) return new int[0];
        int[] out = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i] < 0 ? 0 : (gray[i] > 255 ? 255 : gray[i]);
            out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return out;
    }
}
