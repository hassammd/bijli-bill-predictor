package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * v3.7.43 — per-bill due dates: daysLeft() + pendingSorted()
 * power the card's nearest-due line and the Pending Bills list
 * (tap the card). Due within DUE_SOON_DAYS counts as urgent
 * (red); past due is overdue.
 *
 * Pure Java, no Android dependencies.
 */
public final class TotalPayable {

    private TotalPayable() {}

    /** v3.7.43: due within this many days counts as urgent — red. */
    public static final int DUE_SOON_DAYS = 3;

    public static final class Record {
        /** Last bill amount as printed, e.g. "2,229". */
        public final String amountPrinted;
        public final PaidStatus.State state;
        // v3.7.43 detail — null for old records / unknown fields.
        /** DISCO code, e.g. "lesco". */
        public final String disco;
        /** "ref" or "cid". */
        public final String mode;
        /** Reference / customer id as entered. */
        public final String ref;
        /** Bill month as printed, e.g. "NOV 26". */
        public final String billMonth;
        /** Due date as printed, e.g. "23 NOV 26". */
        public final String dueDate;
        /** After-due amount as printed, e.g. "2,271". */
        public final String afterDue;

        public Record(String amountPrinted, PaidStatus.State state) {
            this(amountPrinted, state, null, null, null, null, null,
                    null);
        }

        public Record(String amountPrinted, PaidStatus.State state,
                String disco, String mode, String ref, String billMonth,
                String dueDate, String afterDue) {
            this.amountPrinted = amountPrinted;
            this.state = state == null
                    ? PaidStatus.State.UNKNOWN : state;
            this.disco = disco;
            this.mode = mode;
            this.ref = ref;
            this.billMonth = billMonth;
            this.dueDate = dueDate;
            this.afterDue = afterDue;
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

    /**
     * v3.7.43: whole days from today to the due date. Positive =
     * days left, 0 = due today, negative = overdue by that many
     * days. Null when the due date is unknown / unparseable —
     * never invented.
     */
    public static Integer daysLeft(String dueDate, long todayMs) {
        long dueMs = BillReminder.parseDueDate(dueDate);
        if (dueMs < 0) return null;
        return Integer.valueOf(
                DailyReminder.daysUntilDue(dueMs, todayMs));
    }

    /** v3.7.43: pending = not PAID and has a usable amount. */
    public static boolean isPending(Record r) {
        if (r == null) return false;
        if (r.state == PaidStatus.State.PAID) return false;
        Double amount = PaidStatus.parseFirstAmount(r.amountPrinted);
        return amount != null && amount > 0;
    }

    /**
     * v3.7.43: pending records, earliest due date first; bills
     * with no (parseable) due date go last, in input order.
     */
    public static List<Record> pendingSorted(List<Record> records) {
        List<Record> out = new ArrayList<Record>();
        if (records == null) return out;
        for (Record r : records) {
            if (isPending(r)) out.add(r);
        }
        Collections.sort(out, new Comparator<Record>() {
            @Override public int compare(Record a, Record b) {
                long da = BillReminder.parseDueDate(a.dueDate);
                long db = BillReminder.parseDueDate(b.dueDate);
                if (da < 0) da = Long.MAX_VALUE;
                if (db < 0) db = Long.MAX_VALUE;
                return da < db ? -1 : (da > db ? 1 : 0);
            }
        });
        return out;
    }
}
