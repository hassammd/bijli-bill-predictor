package com.hatch.bijlibill;

/**
 * v3.1.1 — automatic previous-dues status, derived ONLY from the
 * fetched bill's printed Arrears figure.
 *
 * Ground truth (verified 2026-09-20): the bill document is a demand,
 * not a receipt — it carries NO paid/unpaid field for the current
 * bill, and the parser must never invent one. The one real signal the
 * bill does print is "Arrears" (previous unpaid amount carried
 * forward), parsed by LescoBillFetcher into BillInfo.arrears.
 *
 * Derivation rule:
 *   arrears &gt; 0            -&gt; HAS_ARREARS  (amber "PICHLA BAQAYA")
 *   arrears &lt;= 0 (or "0")  -&gt; NO_ARREARS   (green "KOI BAQAYA NAHI")
 *   missing / unparseable   -&gt; UNKNOWN      (show nothing)
 *
 * This describes PREVIOUS dues only — never the current bill's
 * paid state. Pure Java, no Android dependencies.
 */
public final class ArrearsStatus {

    private ArrearsStatus() {}

    public enum Kind { HAS_ARREARS, NO_ARREARS, UNKNOWN }

    public static final class Result {
        public final Kind kind;
        /** Parsed amount; meaningful only for HAS_ARREARS. */
        public final double amount;
        /** The bill's printed figure, trimmed (for display). */
        public final String printed;

        Result(Kind kind, double amount, String printed) {
            this.kind = kind;
            this.amount = amount;
            this.printed = printed;
        }
    }

    /**
     * Parses the raw arrears string as printed on the bill
     * ("0.00", "1,500.00", ...). Returns null when unparseable.
     */
    public static Double parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace(",", "").replace(" ", "");
        if (s.isEmpty()) return null;
        // Strip common currency prefixes the printer may add.
        if (s.regionMatches(true, 0, "rs", 0, 2)) s = s.substring(2).trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Derives the previous-dues status from the raw arrears text. */
    public static Result derive(String arrearsRaw) {
        if (arrearsRaw == null || arrearsRaw.trim().isEmpty()) {
            return new Result(Kind.UNKNOWN, 0, null);
        }
        Double amt = parseAmount(arrearsRaw);
        if (amt == null) {
            return new Result(Kind.UNKNOWN, 0, null);
        }
        if (amt > 0) {
            return new Result(Kind.HAS_ARREARS, amt,
                    arrearsRaw.trim());
        }
        // Zero or negative (credit) => no dues outstanding.
        return new Result(Kind.NO_ARREARS, amt, arrearsRaw.trim());
    }
}
