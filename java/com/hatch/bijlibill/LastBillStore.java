package com.hatch.bijlibill;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.7.40 — last fetched bill per saved meter, for Home's
 * "Total Payable" card. One record per meter (disco|mode|ref):
 * the latest fetched amount + bill month + paid state (resolved
 * by PaidStatus at record time). Written by BillActivity after a
 * bill renders; read by HomeActivity on resume. A record is
 * overwritten by the next fetch of the same meter, so the total
 * never double-counts an old bill.
 *
 * The encode/decode helpers are pure static methods so the format
 * is unit-tested headlessly; only record()/loadAll() touch
 * Android (SharedPreferences, same pattern as BillCache).
 */
public final class LastBillStore {

    private LastBillStore() {}

    private static final String PREFS = "bijli_last_bill_v1";

    public static final class Record {
        public String disco;
        public String mode;
        public String ref;
        /** Amount as printed, e.g. "2,229". */
        public String amount;
        /** Bill month as printed, e.g. "NOV 26". */
        public String billMonth;
        public PaidStatus.State state;
        public long savedAt;
        /** v3.7.43: due date as printed, e.g. "23 NOV 26". */
        public String dueDate;
        /** v3.7.43: after-due amount as printed, e.g. "2,271". */
        public String afterDue;
    }

    /** Storage key for one meter: "lesco|ref|02118240618604U". */
    public static String key(String disco, String mode, String ref) {
        return (disco == null ? "" : disco) + "|"
                + (mode == null ? "" : mode) + "|"
                + (ref == null ? "" : ref);
    }

    /**
     * Encodes one record as a single |-separated string:
     * disco|mode|ref|amount|billMonth|STATE|savedAt|dueDate|afterDue.
     * v3.7.43 adds the last two fields; decode still accepts the
     * old 7-field form (dueDate/afterDue read as null).
     */
    public static String encode(Record r) {
        if (r == null) return "";
        return (r.disco == null ? "" : r.disco) + "|"
                + (r.mode == null ? "" : r.mode) + "|"
                + (r.ref == null ? "" : r.ref) + "|"
                + (r.amount == null ? "" : r.amount) + "|"
                + (r.billMonth == null ? "" : r.billMonth) + "|"
                + (r.state == null ? PaidStatus.State.UNKNOWN : r.state)
                + "|" + r.savedAt + "|"
                + (r.dueDate == null ? "" : r.dueDate) + "|"
                + (r.afterDue == null ? "" : r.afterDue);
    }

    /** Decodes an encoded record; null when the string is not one. */
    public static Record decode(String key, String s) {
        if (s == null) return null;
        String[] p = s.split("\\|", -1);
        if (p.length != 7 && p.length != 9) return null;
        Record r = new Record();
        r.disco = p[0];
        r.mode = p[1];
        r.ref = p[2];
        r.amount = p[3].isEmpty() ? null : p[3];
        r.billMonth = p[4].isEmpty() ? null : p[4];
        try {
            r.state = PaidStatus.State.valueOf(p[5]);
        } catch (Exception e) {
            r.state = PaidStatus.State.UNKNOWN;
        }
        try {
            r.savedAt = Long.parseLong(p[6]);
        } catch (Exception e) {
            r.savedAt = 0;
        }
        if (p.length == 9) {
            r.dueDate = p[7].isEmpty() ? null : p[7];
            r.afterDue = p[8].isEmpty() ? null : p[8];
        }
        return r;
    }

    /**
     * v3.7.43: the after-due amount for the Overdue row — the
     * bill's own payableAfter25 when the fetcher split it out,
     * else the amountAfterDueDate when it is a single amount.
     * A compound "Till ... After ..." string is NOT guessed at:
     * null when no clean single amount exists. Public so the
     * headless tests (default package) can cover it. */
    public static String afterDueOf(LescoBillFetcher.BillInfo b) {
        if (b == null) return null;
        if (b.payableAfter25 != null
                && !b.payableAfter25.trim().isEmpty()) {
            return b.payableAfter25.trim();
        }
        if (b.amountAfterDueDate == null) return null;
        String s = b.amountAfterDueDate.trim();
        if (s.isEmpty()) return null;
        String up = s.toUpperCase();
        if (up.contains("TILL") || up.contains("AFTER")) return null;
        Double v = PaidStatus.parseFirstAmount(s);
        if (v == null) return null;
        return TotalPayable.formatAmount(v.doubleValue());
    }

    /** Records the latest fetched bill for one meter. Never throws. */
    public static void record(Context ctx, String disco, String mode,
            String ref, LescoBillFetcher.BillInfo b) {
        if (ctx == null || b == null) return;
        try {
            Record r = new Record();
            r.disco = disco;
            r.mode = mode;
            r.ref = ref;
            r.amount = b.amount;
            r.billMonth = b.billMonth;
            r.state = PaidStatus.resolveBill(b).state;
            r.savedAt = System.currentTimeMillis();
            r.dueDate = b.dueDate;
            r.afterDue = afterDueOf(b);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(key(disco, mode, ref), encode(r))
                    .apply();
        } catch (Exception ignored) {
            // Recording must never break the bill screen.
        }
    }

    /** All last-bill records, one per meter. Never null, never throws. */
    public static List<Record> loadAll(Context ctx) {
        List<Record> out = new ArrayList<Record>();
        if (ctx == null) return out;
        try {
            SharedPreferences p = ctx.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            Map<String, ?> all = p.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                Record r = decode(e.getKey(), (String) e.getValue());
                if (r != null) out.add(r);
            }
        } catch (Exception ignored) {
            // A broken store reads as empty — Home shows Rs. 0.
        }
        return out;
    }
}
