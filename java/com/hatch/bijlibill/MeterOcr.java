package com.hatch.bijlibill;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * v2.1.0 on-device OCR via the ML Kit THIN client
 * (play-services-mlkit-text-recognition — the recognition model is
 * downloaded by Google Play Services, so the APK stays small).
 *
 * Fully defensive: ANY failure (no Play Services, model not downloaded
 * yet / offline, bad frame, timeout) yields empty results and the UI
 * falls back to the frame picker + manual entry. The confirm screen is
 * MANDATORY — OCR output is only ever a SUGGESTION.
 *
 * Must be called off the UI thread (uses Tasks.await internally).
 */
public final class MeterOcr {

    /** Per-frame OCR budget. */
    private static final long PER_FRAME_TIMEOUT_SEC = 12;

    private MeterOcr() {}

    /** True when the bundled ML Kit client classes are present. */
    public static boolean isAvailable() {
        try {
            Class.forName(
                    "com.google.mlkit.vision.text.TextRecognition");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs OCR on each frame and returns the digit runs per frame
     * (same order as {@code frames}; empty list for frames that failed).
     * Never returns null, never throws.
     */
    public static List<List<String>> recognizeFrames(
            List<Bitmap> frames) {
        List<List<String>> out = new ArrayList<List<String>>();
        if (frames == null) return out;
        for (int i = 0; i < frames.size(); i++) {
            out.add(new ArrayList<String>());
        }
        TextRecognizer recognizer = null;
        try {
            recognizer = TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS);
            for (int i = 0; i < frames.size(); i++) {
                Bitmap bm = frames.get(i);
                if (bm == null || bm.isRecycled()) continue;
                try {
                    InputImage image = InputImage.fromBitmap(bm, 0);
                    Text text = Tasks.await(recognizer.process(image),
                            PER_FRAME_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (text != null) {
                        out.set(i, MeterCheckCalc.digitRuns(
                                text.getText()));
                    }
                } catch (Throwable ignored) {
                    // This frame failed — keep its list empty; other
                    // frames (and manual entry) still work.
                }
            }
        } catch (Throwable ignored) {
            // Client creation failed (e.g. no Play Services) — caller
            // falls back to manual entry.
        } finally {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /**
     * v2.2.1 STRONG pipeline (user-reported weak OCR on real meter
     * videos — seven-segment LCD, motion blur, small digits):
     *
     *  1. Sharpness-ranks all frames (Laplacian variance) and keeps
     *     only the sharpest 8 — blurry frames waste OCR calls.
     *  2. Runs the bounded job plan from
     *     {@link FramePrep#buildOcrPlan}: per frame ORIGINAL,
     *     ENHANCED (gray -&gt; contrast stretch -&gt; 2x) and INVERTED
     *     (LCD polarity) variants, plus a center-crop 2x pass on the
     *     top-2 sharpest — max {@link FramePrep#MAX_OCR_CALLS} calls.
     *  3. Every pass returns boxed elements; the caller merges all
     *     passes into {@link KwhAnchorPicker#pick} where a kWh-anchored
     *     candidate from ANY pass wins.
     *
     * On low-RAM devices only the ORIGINAL variant of the top 6
     * frames is attempted. Never returns null, never throws; variant
     * bitmaps are recycled immediately. Must be called off the UI
     * thread.
     */
    public static List<List<KwhAnchorPicker.OcrElement>>
            recognizeStrong(List<Bitmap> frames, boolean lowRam) {
        List<List<KwhAnchorPicker.OcrElement>> out =
                new ArrayList<List<KwhAnchorPicker.OcrElement>>();
        if (frames == null || frames.isEmpty()) return out;

        // ---- 1. sharpness rank ----
        int n = frames.size();
        double[] scores = new double[n];
        List<int[]> px = new ArrayList<int[]>(n);
        List<Integer> ws = new ArrayList<Integer>(n);
        List<Integer> hs = new ArrayList<Integer>(n);
        for (int i = 0; i < n; i++) {
            Bitmap bm = frames.get(i);
            px.add(null);
            ws.add(0);
            hs.add(0);
            scores[i] = -1.0;
            if (bm == null || bm.isRecycled()) continue;
            try {
                int w = bm.getWidth();
                int h = bm.getHeight();
                if (w < 3 || h < 3) continue;
                int[] p = new int[w * h];
                bm.getPixels(p, 0, w, 0, 0, w, h);
                px.set(i, p);
                ws.set(i, w);
                hs.set(i, h);
                scores[i] = FramePrep.sharpness(p, w, h);
            } catch (Throwable ignored) {
                scores[i] = -1.0;
            }
        }
        int want = lowRam ? 6 : FramePrep.MAX_SHARP_FRAMES;
        int[] top = FramePrep.topSharpestIndices(scores, want);
        List<Integer> sharp = new ArrayList<Integer>();
        for (int t : top) {
            if (scores[t] >= 0.0) sharp.add(t);
        }
        if (sharp.isEmpty()) return out;

        // ---- 2. bounded job plan ----
        List<FramePrep.Job> plan = lowRam
                ? originalOnlyPlan(sharp.size())
                : FramePrep.buildOcrPlan(sharp.size());

        // ---- 3. run the jobs ----
        TextRecognizer recognizer = null;
        try {
            recognizer = TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS);
            for (FramePrep.Job job : plan) {
                int fi = sharp.get(job.frameIdx);
                Bitmap variant = null;
                boolean ownVariant = false;
                try {
                    if (job.variant == FramePrep.Variant.ORIGINAL) {
                        variant = frames.get(fi);
                    } else {
                        variant = buildVariant(px.get(fi), ws.get(fi),
                                hs.get(fi), job.variant);
                        ownVariant = variant != null;
                    }
                    if (variant == null || variant.isRecycled()) {
                        out.add(
                            new ArrayList<KwhAnchorPicker.OcrElement>());
                        continue;
                    }
                    InputImage image = InputImage.fromBitmap(variant, 0);
                    Text text = Tasks.await(recognizer.process(image),
                            PER_CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
                    List<KwhAnchorPicker.OcrElement> els =
                            new ArrayList<KwhAnchorPicker.OcrElement>();
                    if (text != null) {
                        for (Text.TextBlock b : text.getTextBlocks()) {
                            for (Text.Line l : b.getLines()) {
                                for (Text.Element e : l.getElements()) {
                                    Rect box = e.getBoundingBox();
                                    if (box == null) continue;
                                    els.add(
                                        new KwhAnchorPicker.OcrElement(
                                            e.getText(), box.left,
                                            box.top, box.right,
                                            box.bottom));
                                }
                            }
                        }
                    }
                    out.add(els);
                } catch (Throwable ignored) {
                    out.add(new ArrayList<KwhAnchorPicker.OcrElement>());
                } finally {
                    if (ownVariant && variant != null
                            && !variant.isRecycled()) {
                        try { variant.recycle(); }
                        catch (Throwable ignored) { }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Client creation failed (e.g. no Play Services).
        } finally {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Throwable ignored) { }
            }
        }
        return out;
    }

    /** Per-call budget for the strong pipeline (latency bound). */
    private static final long PER_CALL_TIMEOUT_SEC = 8;

    /** Low-RAM plan: ORIGINAL variant only. */
    private static List<FramePrep.Job> originalOnlyPlan(int n) {
        List<FramePrep.Job> jobs = new ArrayList<FramePrep.Job>();
        for (int i = 0; i < Math.max(0, n); i++) {
            jobs.add(new FramePrep.Job(i, FramePrep.Variant.ORIGINAL));
        }
        return jobs;
    }

    /**
     * Builds the ENHANCED / INVERTED / CROP variant bitmap for one
     * frame's pixels. Returns null on any failure. The caller owns
     * (and must recycle) the returned bitmap.
     */
    private static Bitmap buildVariant(int[] argb, int w, int h,
            FramePrep.Variant variant) {
        try {
            if (argb == null || w < 3 || h < 3) return null;
            int[] gray = FramePrep.toGrayscale(argb);
            int[] stretched = FramePrep.contrastStretch(gray);
            int[] work;
            int ww;
            int hh;
            if (variant == FramePrep.Variant.CROP) {
                int[] cropped = FramePrep.centerCrop(stretched, w, h,
                        0.6f, 0.6f);
                int cw = Math.max(1, (int) (w * 0.6f));
                int ch = Math.max(1, (int) (h * 0.6f));
                work = FramePrep.upscale2x(cropped, cw, ch);
                ww = cw * 2;
                hh = ch * 2;
            } else {
                int[] base = variant == FramePrep.Variant.INVERTED
                        ? FramePrep.invert(stretched) : stretched;
                work = FramePrep.upscale2x(base, w, h);
                ww = w * 2;
                hh = h * 2;
            }
            if (work.length != ww * hh) return null;
            return Bitmap.createBitmap(FramePrep.toArgb(work), ww, hh,
                    Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
    }
}
