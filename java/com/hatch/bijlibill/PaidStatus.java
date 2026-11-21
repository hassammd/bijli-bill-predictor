package com.hatch.bijlibill;

import java.util.List;

/**
 * v3.7.38 — Paid/Unpaid status for a bill month, derived ONLY from
 * the PITC bill-page history table that LescoBillFetcher already
 * parses into BillInfo.history. Wired to BillActivity.payCard: the
 * badge shows "Paid" (green) / "Unpaid" (red) right after the
 * amount, and NOTHING when the state is UNKNOWN. There is NO
 * payment date in the PITC data — the badge never shows one.
 *
 * GROUND TRUTH — verified 2026-11-21 against REAL PITC /general
 * HTML (debug dump 2026-09-20, LESCO, ref 02118240618604, served
 * from https://bill.pitc.com.pk/lescobill/general?refno=...):
 *
 *  1. History headers are exactly:
 *     MONTH | STATUS | UNITS | BILL (RS.) | PAYMENT (RS.)
 *  2. The STATUS cell is a coloured pill SPAN with NO usable text:
 *     empty for most months, "SS" for two (DEC25/JAN26, 0 units).
 *     The words "Paid"/"Unpaid" appear NOWHERE in the whole page
 *     (zero matches in 49KB). Status text can NEVER be read.
 *  3. The PAYMENT column is an AMOUNT in rupees (e.g. 1066, 712,
 *     1539), NOT a date. NO payment date exists anywhere in the
 *     PITC /general page: the only date-like tokens are the bill
 *     month, reading/issue/due dates, and history month codes.
 *     A "Paid on <date>" display CANNOT be sourced from PITC.
 *  4. History covers the 12 months BEFORE the current bill month
 *     only (e.g. SEP-26 bill -> SEP25..AUG26). The CURRENT bill
 *     month NEVER has a row in its own fetched history, so the
 *     bill on screen always resolves to UNKNOWN. A Paid/Unpaid
 *     badge for the current bill cannot be derived from the
 *     bill's own fetch; it becomes knowable only when a LATER
 *     bill is fetched (its history then contains this month).
 *  5. The unpaid signal is real: a row CAN have an empty payment
 *     cell while the bill column holds an amount (APR26: bill 970,
 *     payment blank). Payment present means recorded; payment
 *     blank means not recorded.
 *
 * Derivation rule (conservative — never invent a status):
 *   row for billMonth found AND payment empty/0 -> UNPAID
 *   row found AND payment &gt;= bill amount  -> PAID
 *   row found AND 0 &lt; payment &lt; bill      -> UNKNOWN (partial)
 *   no row / no history / unparseable       -> UNKNOWN (show nothing)
 *
 * The payment DATE is deliberately not exposed: PITC does not
 * print one. Any UI showing "Paid on ..." must come from a
 * different, verified source — never from this class.
 *
 * Pure Java, no Android dependencies.
 */
public final class PaidStatus {

    private PaidStatus() {}

    public enum State { PAID, UNPAID, UNKNOWN }

    public static final class Result {
        public final State state;
        /** Payment cell as printed, trimmed; null when absent. */
        public final String paymentPrinted;
        /** Parsed payment amount; -1 when absent/unparseable. */
        public final double paymentAmount;
        /** Parsed bill amount for the matched row; -1 when absent. */
        public final double billAmount;

        Result(State state, String paymentPrinted,
               double paymentAmount, double billAmount) {
            this.state = state;
            this.paymentPrinted = paymentPrinted;
            this.paymentAmount = paymentAmount;
            this.billAmount = billAmount;
        }
    }

    private static final Result UNKNOWN =
            new Result(State.UNKNOWN, null, -1, -1);

    /**
     * Normalises a month label to a comparable key: "OCT 26",
     * "OCT-26", "Oct 2026" and "OCT26" all become "OCT26".
     * Returns "" when nothing usable is present.
     */
    public static String monthKey(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        // "OCT2026" -> "OCT26" (keep the 3-letter month + 2-digit year).
        if (s.length() == 7 && s.substring(0, 3).matches("[A-Z]{3}")
                && s.substring(3).matches("[0-9]{4}")) {
            s = s.substring(0, 3) + s.substring(5);
        }
        return s;
    }

    /** Parses a printed amount ("1,066", "1066.00", "Rs 1,066"). */
    public static Double parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace(",", "").replace(" ", "");
        if (s.isEmpty()) return null;
        if (s.regionMatches(true, 0, "rs", 0, 2)) s = s.substring(2).trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolves the paid state of one bill month against parsed PITC
     * history tables (BillInfo.history). First matching row wins.
     * Never throws; any unexpected shape yields UNKNOWN.
     */
    public static Result resolve(List<List<String[]>> history,
                                 String billMonth) {
        String want = monthKey(billMonth);
        if (want.isEmpty() || history == null) return UNKNOWN;
        try {
            for (List<String[]> table : history) {
                if (table == null) continue;
                Result r = resolveTable(table, want);
                if (r != null) return r;
            }
        } catch (Exception e) {
            return UNKNOWN;
        }
        return UNKNOWN;
    }

    /**
     * Resolves one history table. Returns null when this table has
     * no row for the wanted month (caller tries the next table).
     */
    private static Result resolveTable(List<String[]> table,
                                       String want) {
        int payCol = -1;
        int billCol = -1;
        int start = 0;
        // Header row (if any) names the columns: MONTH | STATUS |
        // UNITS | BILL (RS.) | PAYMENT (RS.).
        for (int i = 0; i < table.size(); i++) {
            String[] row = table.get(i);
            if (row == null) continue;
            for (int c = 0; c < row.length; c++) {
                String cell = row[c] == null ? "" : row[c].trim();
                if (cell.equalsIgnoreCase("MONTH")) {
                    start = i + 1;
                } else if (cell.toUpperCase().contains("PAYMENT")) {
                    payCol = c;
                } else if (cell.toUpperCase().contains("BILL")) {
                    billCol = c;
                }
            }
            if (start > 0) break;
        }
        // No header row: fall back to the v2.0.6 layout
        // [month, status, units, bill, payment].
        if (start == 0) {
            boolean anyFive = false;
            for (String[] row : table) {
                if (row != null && row.length >= 5) { anyFive = true; break; }
            }
            if (!anyFive) return null;
            payCol = 4;
            billCol = 3;
        }
        if (payCol < 0) return null;
        for (int i = start; i < table.size(); i++) {
            String[] row = table.get(i);
            if (row == null || row.length <= payCol) continue;
            if (!monthKey(row[0]).equals(want)) continue;
            String payRaw = row[payCol] == null ? "" : row[payCol].trim();
            Double pay = parseAmount(payRaw);
            double bill = -1;
            if (billCol >= 0 && row.length > billCol) {
                Double b = parseAmount(row[billCol]);
                if (b != null) bill = b;
            }
            if (pay == null || pay <= 0) {
                // Blank/zero payment cell: nothing recorded -> unpaid.
                // (A cell with non-numeric text is NOT a payment.)
                if (payRaw.isEmpty()) {
                    return new Result(State.UNPAID, null, 0, bill);
                }
                return UNKNOWN;
            }
            if (bill < 0 || pay >= bill) {
                return new Result(State.PAID, payRaw, pay, bill);
            }
            // Partial payment (0 < payment < bill): do not guess.
            return UNKNOWN;
        }
        return null;
    }
}
