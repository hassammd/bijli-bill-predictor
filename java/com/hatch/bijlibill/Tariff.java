package com.hatch.bijlibill;

/**
 * Bijli Bill — tariff math (no Android dependencies).
 *
 * Copied (slimmed) from the v1.x calculator project's Tariff.java:
 * only the slab tables + slab-wise energy math are kept here. The
 * v1.x Rates/remote-update machinery is NOT needed for Meter Check.
 *
 * ============================================================================
 * TARIFF CONFIG — UPDATE THESE NUMBERS WHEN NEPRA REVISES RATES.
 * ============================================================================
 * Source: NEPRA S.R.O. No. 279(I)/2026 — domestic base tariff notified for
 * CY2026 (Jan-Dec 2026), applicable to all DISCOs (LESCO, FESCO, IESCO, MEPCO,
 * PESCO, HESCO, GEPCO, SEPCO, QESCO, K-Electric).
 * Figures below as reported by ajjkibaat.com.pk "Explain the Slab System of
 * Electricity Billing in Pakistan: 2026 Guide" (updated ~May 2026),
 * cross-verified against Pakistan Observer report dated 2026-01-09 and
 * nepra.org.pk. Last checked: 2026-09-17.
 *
 * *** ANDAZA — rates stale ho sakte hain. ***
 * Meter Check ka bill ANDAZA inhi rates se lagta hai; asal bill hamesha
 * LESCO/PITC ki website wala hota hai. NEPRA rates badle to sirf isi
 * file ke neeche walay numbers badlo.
 * ============================================================================
 */
public final class Tariff {

    public static final int PROTECTED = 0;
    public static final int NON_PROTECTED = 1;
    public static final int LIFELINE = 2;

    /** Slab tables: {upperBoundInclusive, ratePerUnit}. Last slab uses a huge bound. */
    public static final double[][] SLABS_NON_PROTECTED = {
        {100, 22.44}, {200, 28.91}, {300, 33.10}, {400, 37.99},
        {500, 40.22}, {600, 41.62}, {700, 42.76}, {1e12, 47.69}
    };
    public static final double[][] SLABS_PROTECTED = {
        {100, 10.54}, {200, 13.01}
    };
    public static final double[][] SLABS_LIFELINE = {
        {50, 3.95}, {100, 7.74}
    };

    private Tariff() {}

    public static double[][] slabsFor(int category) {
        switch (category) {
            case PROTECTED: return SLABS_PROTECTED;
            case LIFELINE:  return SLABS_LIFELINE;
            default:        return SLABS_NON_PROTECTED;
        }
    }

    /**
     * Slab-wise ENERGY charges (Rs) for the given units — no fixed
     * charges, no taxes. Mirrors the v1.x Tariff.calculate() slab loop,
     * including the Protected/Lifeline reality check: above 200 units a
     * Protected connection is billed at Non-Protected rates.
     *
     * v2.4.0: double overload — fractional units are billed at the slab
     * rate of the unit they fall into.
     */
    public static double energyCharges(double units, int category) {
        if (units <= 0) return 0.0;
        double[][] slabs = slabsFor(category);
        if (category == PROTECTED && units > 200) {
            slabs = slabsFor(NON_PROTECTED);
        } else if (category == LIFELINE && units > 100) {
            slabs = slabsFor(units <= 200 ? PROTECTED : NON_PROTECTED);
        }
        double total = 0.0;
        double prev = 0.0;
        for (double[] s : slabs) {
            double upper = Math.min(s[0], 1e12);
            if (units <= prev) break;
            double inSlab = Math.min(units, upper) - prev;
            total += inSlab * s[1];
            prev = upper;
        }
        return total;
    }

    /** Int convenience wrapper (v2.4.0: delegates to the double loop). */
    public static double energyCharges(int units, int category) {
        return energyCharges((double) units, category);
    }

    /**
     * "1,318.87" — same rounding/grouping as money(), no currency
     * prefix. UI-only helper for receipt rows (v3.0.0).
     */
    public static String moneyPlain(double v) {
        String m = money(v);
        return m.startsWith("Rs ") ? m.substring(3) : m;
    }

    /** "Rs 1,318.87" style money formatting (no Android deps). */
    public static String money(double v) {
        long rounded = Math.round(v * 100);
        long rupees = rounded / 100;
        long paisa = Math.abs(rounded % 100);
        String s = Long.toString(Math.abs(rupees));
        StringBuilder out = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            out.append(s.charAt(i));
            int pos = n - i - 1;
            if (pos > 0 && pos % 3 == 0) out.append(',');
        }
        return "Rs " + (rupees < 0 ? "-" : "") + out + "."
                + (paisa < 10 ? "0" : "") + paisa;
    }
}
