package com.hatch.bijlibill;

import com.hatch.bijlibill.LescoBillFetcher.BillInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.4.0 slim bill summary — pure Java, no Android dependencies.
 *
 * The user asked for LESS, not more: the bill screen's top card shows
 * ONLY what a user checks monthly — name, payable, due date, the
 * late-payment line, and this month's units. The full official bill
 * stays in the WebView below (parsing never blocks display).
 *
 * fields() returns one Field per row: key in {"name","payable","due",
 * "lateSurcharge","lateTotal","units"}. The Activity maps keys to
 * localized labels and composes the late line. A name row is ALWAYS
 * present (consumerName, else the reference number — never blank);
 * other rows appear only when the parse actually produced them.
 * Headless-unit-testable (see TestBillFetch).
 */
public final class BillSummary {

    private BillSummary() {}

    /** One summary row: key selects the localized label in the UI. */
    public static final class Field {
        public final String key;
        public final String value;
        public Field(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public static List<Field> fields(BillInfo b) {
        List<Field> out = new ArrayList<Field>();
        if (b == null) return out;
        String name = trim(b.consumerName);
        if (name.isEmpty()) name = trim(b.referenceNumber);
        if (!name.isEmpty()) out.add(new Field("name", name));
        if (!trim(b.amount).isEmpty()) {
            out.add(new Field("payable", trim(b.amount)));
        }
        if (!trim(b.dueDate).isEmpty()) {
            out.add(new Field("due", trim(b.dueDate)));
        }
        if (!trim(b.lpSurcharge).isEmpty()) {
            out.add(new Field("lateSurcharge", trim(b.lpSurcharge)));
        }
        if (!trim(b.amountAfterDueDate).isEmpty()) {
            out.add(new Field("lateTotal", trim(b.amountAfterDueDate)));
        }
        if (!trim(b.units).isEmpty()) {
            out.add(new Field("units", trim(b.units)));
        }
        return out;
    }

    /** Value of a single key, or null when absent. */
    public static String value(List<Field> fields, String key) {
        if (fields == null) return null;
        for (Field f : fields) {
            if (f.key.equals(key)) return f.value;
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
