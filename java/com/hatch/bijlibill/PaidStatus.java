package com.hatch.bijlibill;

import java.util.List;

/**
 * v3.7.40 — Paid/Unpaid status for a bill, from the BILL's OWN paid
 * fields first, with the PITC history table kept only as a fallback
 * for bills saved before these fields existed.
 *
 * PRIMARY SOURCE (v3.7.40, user-verified 2026-11-21): the bill
 * document itself prints a paid box at the top-right —
 * "AMOUNT PAID 2,229" + red PAID stamp + "PAYMENT DATE 17-Nov-26"
 * (LESCO NOV 26 bill, NAMOOS TAHIRA MINHAS, ref 02118240618604,
 * screenshot). LescoBillFetcher parses both labels into
 * BillInfo.amountPaid / BillInfo.paymentDate, and sets
 * BillInfo.paidFieldsChecked on every fresh parse. So a freshly
 * fetched bill resolves directly:
 *   AMOUNT PAID present and &gt; 0 -&gt; PAID (+ "Paid on &lt;date&gt;" when
 *     a PAYMENT DATE was printed)
 *   AMOUNT PAID blank / absent / 0 -&gt; UNPAID (current bill)
 *   PAYMENT DATE absent -&gt; "Paid" with NO date (never invented)
 *
 * CORRECTION — the v3.7.38 header claimed "NO payment date exists
 * anywhere in PITC". That was WRONG: it was based on an UNPAID-bill
 * capture (Sep-20 dump), where the paid box is blank. On a PAID
 * bill the date IS printed. The history-table facts below are
 * still true — the history table itself carries no date — but the
 * bill document does, and that is now the primary source.
 *
 * FALLBACK (old saved bills, paidFieldsChecked == false): the PITC
 * history table (MONTH | STATUS | UNITS | BILL (RS.) | PAYMENT
 * (RS.)). The STATUS cell is a coloured pill SPAN with NO usable
 * text; the PAYMENT column is an AMOUNT in rupees, NOT a date.
 * History covers the 12 months BEFORE the bill month, so a fresh
 * current bill never has a row in its own history.
 *
 * Derivation rule (conservative — never invent a status):
 *   v3.7.41: the paid box must first be POSITIVELY LOCATED in
 *     the fetched HTML (LescoBillFetcher.parsePaidBox sets
 *     paidFieldsChecked only then). v3.7.40 set the flag after
 *     every parse, so a paid box in the slip section — invisible
 *     to the label-row/table harvesters — still resolved UNPAID
 *     on the live NOV 26 bill. That false Unpaid is the bug
 *     fixed here.
 *   paid box located + red PAID stamp -&gt; PAID (even when the
 *     amount cell could not be read)
 *   paid box located + amountPaid &gt; 0 -&gt; PAID
 *   paid box located + amount blank/0 -&gt; UNPAID
 *   paid box NOT located -&gt; history fallback; no row -&gt;
 *     UNKNOWN (show nothing, NEVER Unpaid)
 *   history: row found AND payment empty/0 -&gt; UNPAID
 *   history: row found AND payment &gt;= bill amount -&gt; PAID
 *   history: row found AND 0 &lt; payment &lt; bill -&gt; UNKNOWN (partial)
 *   no row / no history / unparseable -&gt; UNKNOWN (show nothing)
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
        /** "17 Nov 26" when a PAYMENT DATE was printed, else null. */
        public final String paymentDateDisplay;

        Result(State state, String paymentPrinted,
               double paymentAmount, double billAmount) {
            this(state, paymentPrinted, paymentAmount, billAmount, null);
        }

        Result(State state, String paymentPrinted,
               double paymentAmount, double billAmount,
               String paymentDateDisplay) {
            this.state = state;
            this.paymentPrinted = paymentPrinted;
            this.paymentAmount = paymentAmount;
            this.billAmount = billAmount;
            this.paymentDateDisplay = paymentDateDisplay;
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
     * First number inside a printed value ("2,229 (via bank)" -&gt; 2229).
     * The paid-box value cell sometimes carries a note after the
     * figure; the figure itself is what decides PAID. Null when no
     * number is present.
     */
    public static Double parseFirstAmount(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "[0-9][0-9,]*+(\\.[0-9]+)?").matcher(raw);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR",
            "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    /**
     * "17-Nov-26" -&gt; "17 Nov 26". Also accepts "17 Nov 26",
     * "17-Nov-2026", "17 Nov 2026" and "17/11/2026" / "17-11-2026".
     * Returns null for anything unparseable (never invent a date).
     * The day prints without a leading zero, the month as a 3-letter
     * title-case word, the year as 2 digits. v3.7.44: the badge
     * reads "Paid on 17 Nov 26" — "Paid on" stays complete (user
     * order), only the date is shortened so the badge never clips
     * next to a big amount. This is the badge's only date source
     * (paymentDateDisplay); nothing else displays it.
     */
    public static String formatPaymentDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Numeric: dd/MM/yyyy or dd-MM-yyyy.
        java.util.regex.Matcher num =
                java.util.regex.Pattern.compile(
                        "^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})$")
                        .matcher(s);
        if (num.find()) {
            int day = Integer.parseInt(num.group(1));
            int mon = Integer.parseInt(num.group(2));
            int year = Integer.parseInt(num.group(3));
            if (year < 100) year += 2000;
            if (day < 1 || day > 31 || mon < 1 || mon > 12) return null;
            return day + " " + MONTHS[mon - 1].substring(0, 1)
                    + MONTHS[mon - 1].substring(1).toLowerCase()
                    + " " + String.format("%02d", year % 100);
        }
        // Named month: dd-Mon-yy(yy) or dd Mon yy(yy).
        java.util.regex.Matcher named =
                java.util.regex.Pattern.compile(
                        "^(\\d{1,2})[\\s-]+([A-Za-z]{3,9})[\\s-]+(\\d{2,4})$")
                        .matcher(s);
        if (!named.find()) return null;
        int day;
        int year;
        try {
            day = Integer.parseInt(named.group(1));
            year = Integer.parseInt(named.group(3));
        } catch (NumberFormatException e) {
            return null;
        }
        if (year < 100) year += 2000;
        if (day < 1 || day > 31) return null;
        String monRaw = named.group(2).toUpperCase();
        String[] full = {"JANUARY", "FEBRUARY", "MARCH", "APRIL",
                "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER",
                "OCTOBER", "NOVEMBER", "DECEMBER"};
        int mon = -1;
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(monRaw)
                    || ("SEPT".equals(monRaw) && i == 8)
                    || full[i].equals(monRaw)) {
                mon = i + 1;
                break;
            }
        }
        if (mon < 1) return null;
        return day + " " + MONTHS[mon - 1].substring(0, 1)
                + MONTHS[mon - 1].substring(1).toLowerCase()
                + " " + String.format("%02d", year % 100);
    }

    /**
     * Primary resolver: the bill's OWN paid fields. Freshly fetched
     * bills whose paid box was positively located
     * (paidFieldsChecked, v3.7.41 — see LescoBillFetcher
     * .parsePaidBox) decide from the box: the red PAID stamp alone
     * proves PAID (even when the amount cell could not be read);
     * AMOUNT PAID &gt; 0 is PAID; a located box with a blank/zero
     * amount is UNPAID. Bills whose paid box was never located
     * (paidFieldsChecked == false — old saved bills, or a page
     * shape the scan did not recognise) fall back to the history
     * table, and a current bill with no history row is UNKNOWN
     * (no badge, NEVER Unpaid). Never throws; any unexpected
     * shape yields UNKNOWN.
     */
    public static Result resolveBill(
            LescoBillFetcher.BillInfo b) {
        if (b == null) return UNKNOWN;
        try {
            if (b.paidFieldsChecked) {
                // v3.7.41: the red PAID stamp is printed only on
                // paid bills — it proves PAID even when the amount
                // cell could not be read.
                if (b.paidStampFound) {
                    Double paid = parseFirstAmount(b.amountPaid);
                    return new Result(State.PAID, b.amountPaid,
                            paid == null ? -1 : paid, -1,
                            formatPaymentDate(b.paymentDate));
                }
                Double paid = parseFirstAmount(b.amountPaid);
                if (paid != null && paid > 0) {
                    return new Result(State.PAID, b.amountPaid, paid,
                            -1, formatPaymentDate(b.paymentDate));
                }
                return new Result(State.UNPAID, null, 0, -1, null);
            }
            return resolve(b.history, b.billMonth);
        } catch (Exception e) {
            return UNKNOWN;
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
