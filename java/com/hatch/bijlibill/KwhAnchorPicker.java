package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.2.0 kWh-anchored OCR candidate picker — pure Java, no Android
 * dependencies. Headless-unit-testable (see TestBillFetch.runV220Tests).
 *
 * The user's problem: digital meters cycle through kWh / kW / volts and
 * the full cycle takes longer than a few seconds, so a video contains
 * several DIFFERENT readings and the user can't tell which one is the
 * billing (kWh) reading. ML Kit returns text elements with bounding
 * boxes; this picker finds the digit run nearest a "kWh" token.
 *
 * Real-world anchor (user's meter photo): the LCD shows "765.03" with
 * "kWh" printed immediately to its RIGHT, on the same line. So the
 * picker strongly prefers candidates that (a) share a line with a kWh
 * token (vertical box overlap) and (b) sit LEFT of it (reading order).
 *
 * Scoring: |dx| + 2*|dy| minus 100000 for same-line minus 50000 for
 * digits-left-of-kWh; lowest wins, ties go to the first seen. A digit
 * run inside the SAME element as the kWh token is a perfect anchor.
 * When no frame has a kWh token, falls back to the old behavior —
 * most frequent digit run across frames (MeterCheckCalc.pickDigits).
 *
 * Decimal handling: billing reading = integer part ("765.03" -> "765");
 * the full raw value is still shown on the confirm screen.
 */
public final class KwhAnchorPicker {

    private KwhAnchorPicker() {}

    /** "kWh", case-insensitive, anywhere in the element text. */
    private static final Pattern KWH =
            Pattern.compile("kwh", Pattern.CASE_INSENSITIVE);
    /**
     * Digit runs with optional decimals: "765", "765.03", "00765".
     * 3-6 integer digits keep voltages ("220") out of the anchored
     * path unless they are genuinely near a kWh token.
     */
    private static final Pattern DIGITS =
            Pattern.compile("\\d{3,6}(\\.\\d{1,2})?");

    /** One OCR text element with its bounding box (pixels, any scale). */
    public static final class OcrElement {
        public final String text;
        public final int left, top, right, bottom;

        public OcrElement(String text, int left, int top, int right,
                int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int cx() { return (left + right) / 2; }
        public int cy() { return (top + bottom) / 2; }
    }

    /** Pick outcome. Never null fields when returned (null = no digits). */
    public static final class Picked {
        /** As seen on the meter, e.g. "765.03". */
        public final String rawValue;
        /** Integer part used for billing math, e.g. "765". */
        public final String billingValue;
        /** True when a kWh token anchored the pick. */
        public final boolean kwhAnchored;

        public Picked(String rawValue, String billingValue,
                boolean kwhAnchored) {
            this.rawValue = rawValue;
            this.billingValue = billingValue;
            this.kwhAnchored = kwhAnchored;
        }
    }

    /**
     * Picks the billing reading from per-frame OCR elements.
     * Returns null when no frame produced any digit run.
     */
    public static Picked pick(List<List<OcrElement>> frames) {
        if (frames == null) return null;

        String bestRaw = null;
        long bestScore = 0;
        boolean found = false;

        for (List<OcrElement> frame : frames) {
            if (frame == null) continue;
            List<OcrElement> kwhs = new ArrayList<OcrElement>();
            for (OcrElement e : frame) {
                if (e != null && e.text != null
                        && KWH.matcher(e.text).find()) {
                    kwhs.add(e);
                }
            }
            for (OcrElement e : frame) {
                if (e == null || e.text == null) continue;
                boolean hasKwh = KWH.matcher(e.text).find();
                Matcher dm = DIGITS.matcher(e.text);
                while (dm.find()) {
                    String raw = dm.group();
                    long score;
                    if (hasKwh) {
                        // Digits and "kWh" inside one element: perfect.
                        score = Long.MIN_VALUE / 4;
                    } else if (kwhs.isEmpty()) {
                        continue; // no anchor in this frame
                    } else {
                        long bs = Long.MAX_VALUE;
                        for (OcrElement k : kwhs) {
                            long dx = (long) k.cx() - e.cx();
                            long dy = (long) k.cy() - e.cy();
                            boolean sameLine = e.top <= k.bottom
                                    && k.top <= e.bottom;
                            long s = Math.abs(dx) + 2 * Math.abs(dy);
                            if (sameLine) s -= 100000L;
                            // Digits almost always sit LEFT of "kWh".
                            if (dx > 0) s -= 50000L;
                            if (s < bs) bs = s;
                        }
                        score = bs;
                    }
                    if (!found || score < bestScore) {
                        found = true;
                        bestScore = score;
                        bestRaw = raw;
                    }
                }
            }
        }

        if (found) {
            return new Picked(bestRaw, billingInteger(bestRaw), true);
        }

        // Fallback: most frequent digit run across frames.
        List<List<String>> runs = new ArrayList<List<String>>();
        for (List<OcrElement> frame : frames) {
            List<String> fr = new ArrayList<String>();
            if (frame != null) {
                for (OcrElement e : frame) {
                    if (e != null) {
                        fr.addAll(MeterCheckCalc.digitRuns(e.text));
                    }
                }
            }
            runs.add(fr);
        }
        String fb = MeterCheckCalc.pickDigits(runs);
        if (fb == null) return null;
        return new Picked(fb, billingInteger(fb), false);
    }

    /**
     * "765.03" -> "765". Pure. The billing math always uses the
     * integer part; the raw value is shown for transparency.
     */
    public static String billingInteger(String raw) {
        if (raw == null) return null;
        int dot = raw.indexOf('.');
        String s = dot >= 0 ? raw.substring(0, dot) : raw;
        s = s.replace(",", "");
        return s.isEmpty() ? raw : s;
    }

    /**
     * v2.3.0: the app picks the best frame ITSELF — no manual frame
     * selection in the flow. Index of the highest sharpness score;
     * ties keep the first frame. -1 when null/empty.
     */
    public static int bestFrameIndex(double[] scores) {
        if (scores == null || scores.length == 0) return -1;
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) best = i;
        }
        return best;
    }

    /**
     * v2.2.0 guard: gallery-picked videos can be hours long. Rejects
     * clips longer than 60s or bigger than 150MB BEFORE frame
     * extraction. Unknown (-1) values are allowed through (can't
     * verify). Pure — unit-testable with mocked metadata.
     */
    public static final long MAX_VIDEO_DURATION_MS = 60_000L;
    public static final long MAX_VIDEO_BYTES = 150L * 1024L * 1024L;

    public enum VideoCheck { OK, TOO_LONG, TOO_BIG }

    public static VideoCheck checkVideo(long durationMs, long sizeBytes) {
        if (durationMs > MAX_VIDEO_DURATION_MS) return VideoCheck.TOO_LONG;
        if (sizeBytes > MAX_VIDEO_BYTES) return VideoCheck.TOO_BIG;
        return VideoCheck.OK;
    }

    /**
     * Adaptive frame count: about one frame per second of video,
     * bounded to [1, maxFrames]. 45s -> 12, 8s -> 8, 0.5s -> 1.
     */
    public static int frameCountFor(long durMs, int maxFrames) {
        int c = (int) (durMs / 1000L);
        if (c < 1) c = 1;
        if (c > maxFrames) c = maxFrames;
        return c;
    }

    /**
     * Evenly-spread frame timestamps (microseconds, for
     * MediaMetadataRetriever.getFrameAtTime). Count comes from
     * {@link #frameCountFor}; never empty.
     */
    public static List<Long> frameTimestampsUs(long durMs, int maxFrames) {
        int count = frameCountFor(durMs, maxFrames);
        List<Long> out = new ArrayList<Long>(count);
        for (int i = 0; i < count; i++) {
            out.add(durMs * 1000L * (2L * i + 1) / (2L * count));
        }
        return out;
    }
}
