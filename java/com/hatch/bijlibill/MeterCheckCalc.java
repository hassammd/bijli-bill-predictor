package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.1.0 Meter Check — ALL math, pure Java, no Android dependencies.
 * Headless-unit-testable (see TestBillFetch).
 *
 * v2.2.1 BUGFIX (user-reported, live-tested): the base reading is the
 * bill's PRESENT reading (the latest billed reading, e.g. 765) — NOT
 * the bill's previous reading (652). unitsSinceBill = current - base,
 * i.e. consumption SINCE the bill, so already-billed units are not
 * counted twice. Falls back to the previous reading only when the
 * present reading is missing/zero.
 *
 * v2.4.0: ALL reading/units math is double — the manual entry accepts
 * decimal readings like 352.32 ("," and "٫" separators normalized to
 * "."). Display helpers trim trailing zeros (22.0 -> "22").
 * NEW: breakdown() returns the estimate as plain-language rows built
 * from the estimator's REAL terms (no invented numbers) so the UI can
 * show "Andaza bill kaise bana?" using LAST MONTH's actual tax fields
 * from the fetched bill.
 *
 * Flow: video of the meter -> current reading; subtract the bill's
 * present reading -> units used since the bill; energy estimate via
 * the NEPRA slab table (Tariff); taxes via the PARSED bill's real ratios;
 * slab/cliff alerts; month-end projection.
 *
 * EVERY figure this class produces is an ANDAZA (estimate) — the UI must
 * label it as such. Never present as official.
 */
public final class MeterCheckCalc {

    private MeterCheckCalc() {}

    /** Exact validation message (spec, v2.2.1 wording). */
    public static final String ERR_READING_ORDER =
            "Nayi reading bill wali reading se kam hai \u2014 dobara check karo";
    /** When a reading cannot be parsed at all. */
    public static final String ERR_READING_UNPARSEABLE =
            "Reading parhi nahi ja saki \u2014 dobara check karo";

    /** Slab boundaries for the alert ladder. */
    public static final int[] SLAB_BOUNDS = {100, 200, 300, 400, 500, 700};
    /** Warn when this many (or fewer) units remain to the next slab. */
    public static final int SLAB_WARN_WITHIN = 15;
    /** Protected -> Non-Protected cliff boundary. */
    public static final int PROTECTED_CLIFF = 200;
    /** GST-only fallback tax ratio when the bill's tax fields are missing. */
    public static final double FALLBACK_TAX_RATIO = 0.18;

    private static final Pattern DIGIT_RUNS = Pattern.compile("[0-9]{2,6}");
    private static final Pattern NUM_INT = Pattern.compile("[\\d,]+");
    private static final Pattern NUM_DEC =
            Pattern.compile("[\\d,]+(\\.\\d+)?");

    /** Calculation outcome. {@link #error} != null means validation failed. */
    public static final class Result {
        public String error;
        /** v2.2.1: bill's PRESENT reading (the base), NOT previous. */
        public double baseReading;
        /** v2.4.0: double — manual entry may be "352.32". */
        public double currReading;
        /** v2.4.0: double units since the bill. */
        public double unitsUsed;
        public double energyEstimate;   // slab-wise energy Rs (ANDAZA)
        public double taxRatio;         // from the parsed bill
        public boolean taxRatioFallback;// true => 0.18 GST-only fallback
        /** v2.4.0: real parsed amounts kept for the breakdown. */
        public double taxesAmt = -1;
        public double gstAmt = -1;
        public double fpaAmt = -1;
        public double elecAmt = -1;
        public double estimatedTotal;   // Rs (ANDAZA)
        public int nextSlab = -1;       // -1 => already past top slab
        /** v2.4.0: double — units left to nextSlab. */
        public double remaining = -1;
        public boolean slabWarning;     // remaining <= 15
        public boolean protectedCliff;  // protected && unitsUsed < 200
        public boolean isProtected;
        public int projectedMonthUnits = -1; // -1 => dayOfMonth invalid
    }

    /**
     * v2.2.1: resolves the calculation base from the parsed bill.
     * Returns the PRESENT reading when it parses to a positive number;
     * falls back to the PREVIOUS reading only when present is
     * missing/zero/unparseable. Pure — unit-testable.
     */
    public static String resolveBase(String presentStr, String prevStr) {
        if (presentStr != null && parseReading(presentStr) > 0) {
            return presentStr;
        }
        return prevStr;
    }

    /**
     * Full Meter Check calculation.
     *
     * @param baseStr  bill's PRESENT reading (see {@link #resolveBase});
     *                 units are counted FROM this reading (bill ke baad se)
     * @param currStr  meter reading just captured (String, may be decimal
     *                 like "352.32"; "," / "٫" separators normalized)
     * @param category bill's category text, e.g. "Protected"
     * @param taxesStr parsed bill taxes (may be null)
     * @param gstStr   parsed bill GST (may be null)
     * @param fpaStr   parsed bill FPA (may be null)
     * @param elecStr  parsed bill electricity charges (may be null)
     * @param dayOfMonth 1-31 from Calendar (projection), &lt;1 disables it
     */
    public static Result compute(String baseStr, String currStr,
            String category, String taxesStr, String gstStr, String fpaStr,
            String elecStr, int dayOfMonth) {
        Result r = new Result();
        r.baseReading = parseReadingD(baseStr);
        r.currReading = parseReadingD(currStr);
        if (r.baseReading < 0 || r.currReading < 0) {
            r.error = ERR_READING_UNPARSEABLE;
            return r;
        }
        if (r.currReading <= r.baseReading) {
            r.error = ERR_READING_ORDER;
            return r;
        }
        r.unitsUsed = r.currReading - r.baseReading;

        // v3.1.3: classify carefully — the old contains("protected")
        // matched "Non-Protected" as PROTECTED. Check lifeline first,
        // then the non-protected spellings, then protected.
        String catLower = category == null ? ""
                : category.toLowerCase(Locale.US);
        boolean isLifeline = catLower.contains("lifeline");
        boolean isNonProtected = catLower.contains("non-protected")
                || catLower.contains("non protected")
                || catLower.contains("nonprotected");
        r.isProtected = !isLifeline && !isNonProtected
                && catLower.contains("protected");
        int tariffCat = r.isProtected ? Tariff.PROTECTED
                : (isLifeline ? Tariff.LIFELINE : Tariff.NON_PROTECTED);

        r.energyEstimate = Tariff.energyCharges(r.unitsUsed, tariffCat);

        r.taxesAmt = parseAmount(taxesStr);
        r.gstAmt = parseAmount(gstStr);
        r.fpaAmt = parseAmount(fpaStr);
        r.elecAmt = parseAmount(elecStr);
        double taxes = r.taxesAmt;
        double gst = r.gstAmt;
        double fpa = r.fpaAmt;
        double elec = r.elecAmt;
        boolean taxFieldsMissing = taxesStr == null && gstStr == null
                && fpaStr == null;
        if (elec > 0 && !taxFieldsMissing) {
            if (taxes < 0) taxes = 0;
            if (gst < 0) gst = 0;
            if (fpa < 0) fpa = 0;
            r.taxesAmt = taxes;
            r.gstAmt = gst;
            r.fpaAmt = fpa;
            r.taxRatio = (taxes + gst + fpa) / elec;
            r.taxRatioFallback = false;
        } else {
            r.taxRatio = FALLBACK_TAX_RATIO;
            r.taxRatioFallback = true;
        }
        r.estimatedTotal = r.energyEstimate * (1.0 + r.taxRatio);

        for (int b : SLAB_BOUNDS) {
            if (b > r.unitsUsed) {
                r.nextSlab = b;
                r.remaining = b - r.unitsUsed;
                break;
            }
        }
        r.slabWarning = r.remaining > 0
                && r.remaining <= SLAB_WARN_WITHIN;
        r.protectedCliff = r.isProtected && r.unitsUsed < PROTECTED_CLIFF;

        if (dayOfMonth >= 1) {
            r.projectedMonthUnits = (int) Math.round(
                    r.unitsUsed / (double) dayOfMonth * 30.0);
        }
        return r;
    }

    // ------------------------------------------------------------------
    // v2.4.0: "Andaza bill kaise bana?" — plain-language breakdown.
    // ------------------------------------------------------------------

    /** Row kinds for the estimate breakdown. */
    public enum BreakKind {
        ENERGY, TAXES, GST, FPA, TAX_FALLBACK, TOTAL
    }

    /**
     * One breakdown row: kind + aux (units for ENERGY, % for tax rows,
     * unused for TAX_FALLBACK/TOTAL) + the Rs amount. Every row's amount
     * is a term of the REAL computation in compute() — the non-TOTAL
     * rows sum to estimatedTotal exactly.
     */
    public static final class BreakRow {
        public final BreakKind kind;
        public final double aux;
        public final double amount;
        public BreakRow(BreakKind kind, double aux, double amount) {
            this.kind = kind;
            this.aux = aux;
            this.amount = amount;
        }
    }

    /**
     * Builds the estimate breakdown from a computed Result. Uses LAST
     * MONTH's actual tax fields (stored on the Result by compute()).
     * Pure — unit-testable.
     */
    public static List<BreakRow> breakdown(Result r) {
        List<BreakRow> rows = new ArrayList<BreakRow>();
        if (r == null || r.error != null) return rows;
        rows.add(new BreakRow(BreakKind.ENERGY, r.unitsUsed,
                r.energyEstimate));
        if (!r.taxRatioFallback && r.elecAmt > 0) {
            if (r.taxesAmt > 0) {
                rows.add(new BreakRow(BreakKind.TAXES,
                        r.taxesAmt / r.elecAmt * 100.0,
                        r.energyEstimate * r.taxesAmt / r.elecAmt));
            }
            if (r.gstAmt > 0) {
                rows.add(new BreakRow(BreakKind.GST,
                        r.gstAmt / r.elecAmt * 100.0,
                        r.energyEstimate * r.gstAmt / r.elecAmt));
            }
            if (r.fpaAmt > 0) {
                rows.add(new BreakRow(BreakKind.FPA,
                        r.fpaAmt / r.elecAmt * 100.0,
                        r.energyEstimate * r.fpaAmt / r.elecAmt));
            }
        } else {
            rows.add(new BreakRow(BreakKind.TAX_FALLBACK, 18.0,
                    r.energyEstimate * FALLBACK_TAX_RATIO));
        }
        rows.add(new BreakRow(BreakKind.TOTAL, 0, r.estimatedTotal));
        return rows;
    }

    // ------------------------------------------------------------------
    // Display helpers (v2.4.0)
    // ------------------------------------------------------------------

    /**
     * Trims a double for display: 22.0 -> "22", 22.5 -> "22.5",
     * 22.32 -> "22.32". Never scientific notation for sane inputs.
     */
    public static String fmtUnits(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        double r = Math.round(v * 100.0) / 100.0;
        if (r == Math.floor(r) && !Double.isInfinite(r)) {
            return Long.toString((long) r);
        }
        String s = String.format(Locale.US, "%.2f", r);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Trims a percentage for display: 18.0 -> "18", 17.647 -> "17.6".
     */
    public static String fmtPct(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        double r = Math.round(v * 10.0) / 10.0;
        if (r == Math.floor(r)) return Long.toString((long) r);
        return String.format(Locale.US, "%.1f", r);
    }

    /**
     * OCR candidate picker (pure): the MOST FREQUENT digit run across all
     * frames wins; ties go to the run seen FIRST. Returns null when no
     * frame produced any candidate.
     */
    public static String pickDigits(List<List<String>> perFrameRuns) {
        if (perFrameRuns == null) return null;
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (List<String> frame : perFrameRuns) {
            if (frame == null) continue;
            for (String run : frame) {
                if (run == null || run.isEmpty()) continue;
                Integer c = counts.get(run);
                counts.put(run, c == null ? 1 : c + 1);
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** Extracts digit runs ([0-9]{2,6}) from OCR text, in order. */
    public static List<String> digitRuns(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        Matcher m = DIGIT_RUNS.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Parses a meter reading ("652", "1,240") -> int, -1 when absent. */
    static int parseReading(String s) {
        if (s == null) return -1;
        Matcher m = NUM_INT.matcher(s);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * v2.4.0: parses a meter reading as double — "652", "1,240",
     * "352.32", "352,32" (comma decimal), "352٫32" all work. -1 when
     * absent. The FIRST numeric run wins. Public so the headless
     * test (default package) can exercise the separator logic.
     */
    public static double parseReadingD(String s) {
        if (s == null) return -1;
        String n = s.trim().replace('\u066B', '.');
        if (n.indexOf(',') >= 0) {
            if (n.indexOf('.') >= 0) {
                // "1,234.56" — comma is a thousands separator.
                n = n.replace(",", "");
            } else if (n.matches(".*\\d,\\d{3}([^\\d].*)?$")) {
                // "1,240" / "12,345" — comma + exactly 3 digits =
                // thousands separator.
                n = n.replace(",", "");
            } else {
                // "352,32" — comma is the decimal separator.
                n = n.replace(',', '.');
            }
        }
        Matcher m = Pattern.compile("[\\d]+(\\.[\\d]+)?").matcher(n);
        if (!m.find()) return -1;
        try {
            return Double.parseDouble(m.group());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses a money-ish field ("2,065", "1294.20") -> double, -1 when absent. */
    static double parseAmount(String s) {
        if (s == null) return -1;
        Matcher m = NUM_DEC.matcher(s);
        if (!m.find()) return -1;
        try {
            return Double.parseDouble(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
