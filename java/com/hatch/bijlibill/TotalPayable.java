package com.hatch.bijlibill;

import java.util.List;

/**
 * v3.7.40 — "Total Payable" for the Home company-cards grid: the
 * sum of the last fetched bill per saved meter that is NOT Paid.
 *
 * RULE (user order 2026-11-21): a bill counts as pending unless
 * its state is PAID. UNPAID counts, and UNKNOWN counts too — a bill
 * whose status the app could not determine yet is still money owed
 * until proven paid. No saved bills at all -&gt; total Rs. 0 and
 * 0 bills pending. A record with no usable amount (&lt;= 0 or
 * unparseable) is skipped from both the total and the count.
 *
 * Pure Java, no Android dependencies.
 */
public final class TotalPayable {

    private TotalPayable() {}

    public static final class Record {
        /** Last bill amount as printed, e.g. "2,229". */
        public final String amountPrinted;
        public final PaidStatus.State state;

        public Record(String amountPrinted, PaidStatus.State state) {
            this.amountPrinted = amountPrinted;
            this.state = state == null
                    ? PaidStatus.State.UNKNOWN : state;
        }
    }

    public static final class Result {
        /** Sum of pending amounts, in rupees. */
        public final double total;
        /** Bills counted in the total. */
        public final int pendingCount;

        public Result(double total, int pendingCount) {
            this.total = total;
            this.pendingCount = pendingCount;
        }
    }

    public static Result compute(List<Record> records) {
        double total = 0;
        int n = 0;
        if (records == null) return new Result(0, 0);
        for (Record r : records) {
            if (r == null) continue;
            if (r.state == PaidStatus.State.PAID) continue;
            Double amount = PaidStatus.parseFirstAmount(r.amountPrinted);
            if (amount == null || amount <= 0) continue;
            total += amount;
            n++;
        }
        return new Result(total, n);
    }

    /** "19615" -&gt; "19,615"; "2229.5" -&gt; "2,229.5". */
    public static String formatAmount(double v) {
        java.text.NumberFormat nf = java.text.NumberFormat.getInstance(
                java.util.Locale.US);
        nf.setGroupingUsed(true);
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(0);
        return nf.format(v);
    }
}
