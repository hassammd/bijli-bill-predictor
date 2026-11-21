package com.hatch.bijlibill;

import android.content.Context;
import android.content.SharedPreferences;

import com.hatch.bijlibill.LescoBillFetcher.BillInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.7.31 — bill snapshots for the AI Assistant (v1, SIMPLE).
 *
 * After every successful bill fetch, BillActivity records a small
 * snapshot of the parsed bill (units, amount, due date, FPA,
 * category, readings, bill month) under the meter key. The
 * Assistant answers ONLY from these on-device snapshots — no
 * internet, no API key, no server, no cost, fully on-device.
 *
 * Keeps the 3 most recent snapshots per meter (newest first). A
 * re-fetch of the same bill month replaces the old snapshot instead
 * of duplicating it, so "pichla bill" always means the previous
 * month's bill, never the same bill twice.
 *
 * Pure logic + a tiny Storage interface so unit tests run headless
 * with an in-memory map; the Android side wires SharedPreferences.
 * Stored as one JSON array per meter key, plus a key list.
 */
public final class BillSnapshots {

    private static final String PREFS = "bijli_bill_snapshots";
    /** Snapshots kept per meter (newest first). */
    public static final int MAX_PER_METER = 3;

    private BillSnapshots() {}

    /** Key-value backing store (SharedPreferences on Android). */
    public interface Storage {
        String load(String key);
        void save(String key, String value);
    }

    /** One fetched bill, trimmed to what the Assistant needs. */
    public static final class Snapshot {
        public String disco;      // Disco.code, e.g. "lesco"
        public String mode;       // "ref" or "cid"
        public String ref;        // as typed
        public String holderName; // consumer name from the bill
        public String billMonth;  // e.g. "SEP 26"
        public int units = -1;    // -1 when the bill did not print units
        public double amount = -1;        // payable within due date
        public double amountAfterDue = -1;
        public String dueDate;    // as printed on the bill
        public String fpa;        // FPA value as printed (raw)
        public String category;   // tariff category as printed
        public String prevReading;
        public String currReading;
        public String readingDate; // as printed on the bill
        public long fetchedAt;    // epoch ms
    }

    /** Newest snapshot across all meters, with its meter key. */
    public static final class Latest {
        public String key;
        public Snapshot snap;
    }

    // ------------------------------------------------------------------
    // Build from a parsed bill
    // ------------------------------------------------------------------

    /** Builds a snapshot from a freshly parsed BillInfo. */
    public static Snapshot fromBill(BillInfo b, String disco,
            String mode, String ref, long fetchedAt) {
        Snapshot s = new Snapshot();
        s.disco = disco == null ? "lesco" : disco;
        s.mode = "cid".equals(mode) ? "cid" : "ref";
        s.ref = ref == null ? "" : ref.trim();
        s.holderName = b == null ? "" : nn(b.consumerName);
        s.billMonth = b == null ? "" : nn(b.billMonth);
        s.units = b == null ? -1 : b.unitsAsInt();
        s.amount = b == null ? -1 : b.amountAsDouble();
        s.amountAfterDue = b == null ? -1
                : parseMoney(b.amountAfterDueDate);
        s.dueDate = b == null ? "" : nn(b.dueDate);
        s.fpa = b == null ? "" : nn(b.fpa);
        s.category = b == null ? ""
                : nn(b.tariffCategory == null || b.tariffCategory.isEmpty()
                        ? b.category : b.tariffCategory);
        s.prevReading = b == null ? "" : nn(b.prevReading);
        s.currReading = b == null ? "" : nn(b.currReading);
        s.readingDate = b == null ? "" : nn(b.readingDate);
        s.fetchedAt = fetchedAt;
        return s;
    }

    /** First decimal number in a money string, or -1 (tolerant of
     *  commas, "Rs", spaces). */
    static double parseMoney(String s) {
        if (s == null) return -1;
        StringBuilder digits = new StringBuilder();
        boolean seenDigit = false;
        boolean seenDot = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
                seenDigit = true;
            } else if (c == '.' && seenDigit && !seenDot) {
                digits.append(c);
                seenDot = true;
            } else if (c == ',' || c == ' ' || c == '\t') {
                // Thousands separator / padding — skip.
            } else if (seenDigit) {
                break;
            }
        }
        if (!seenDigit) return -1;
        try {
            return Double.parseDouble(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String nn(String s) {
        return s == null ? "" : s.trim();
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    /** Records a snapshot: same bill month replaces, otherwise
     *  prepended; cap {@link #MAX_PER_METER} (oldest evicted). */
    public static void record(Storage s, String key, Snapshot snap) {
        if (s == null || key == null || key.isEmpty() || snap == null) {
            return;
        }
        List<Snapshot> cur = list(s, key);
        List<Snapshot> out = new ArrayList<Snapshot>();
        boolean replaced = false;
        for (Snapshot old : cur) {
            if (!replaced && sameBill(old, snap)) {
                out.add(snap);
                replaced = true;
            } else {
                out.add(old);
            }
        }
        if (!replaced) out.add(0, snap);
        while (out.size() > MAX_PER_METER) {
            out.remove(out.size() - 1);
        }
        try {
            s.save("snap_" + key, encode(out));
            ensureKey(s, key);
        } catch (Exception ignored) {
        }
    }

    /** Same bill month (and same units when both known) = same bill. */
    static boolean sameBill(Snapshot a, Snapshot b) {
        if (a == null || b == null) return false;
        String ma = a.billMonth == null ? "" : a.billMonth.trim();
        String mb = b.billMonth == null ? "" : b.billMonth.trim();
        if (!ma.isEmpty() && !mb.isEmpty()) {
            return ma.equalsIgnoreCase(mb);
        }
        // No bill month on either side: same units + same amount
        // = same bill (a plain re-fetch).
        return a.units >= 0 && a.units == b.units
                && a.amount >= 0 && a.amount == b.amount;
    }

    /** Snapshots for one meter key, newest first. Never null. */
    public static List<Snapshot> list(Storage s, String key) {
        List<Snapshot> out = new ArrayList<Snapshot>();
        if (s == null || key == null) return out;
        String json;
        try {
            json = s.load("snap_" + key);
        } catch (Exception e) {
            return out;
        }
        if (json == null || json.trim().isEmpty()) return out;
        try {
            decodeInto(json.trim(), out);
        } catch (Exception e) {
            // Corrupt store -> treat as empty rather than crashing.
        }
        return out;
    }

    /** Newest snapshot for one meter key, or null. */
    public static Snapshot latest(Storage s, String key) {
        List<Snapshot> l = list(s, key);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Newest snapshot across ALL saved meters, or null when no
     *  bill has ever been fetched. */
    public static Latest latestAny(Storage s) {
        if (s == null) return null;
        Latest best = null;
        for (String key : keys(s)) {
            Snapshot snap = latest(s, key);
            if (snap == null) continue;
            if (best == null || snap.fetchedAt > best.snap.fetchedAt) {
                best = new Latest();
                best.key = key;
                best.snap = snap;
            }
        }
        return best;
    }

    /** Meter keys that have snapshots, in first-seen order. */
    static List<String> keys(Storage s) {
        List<String> out = new ArrayList<String>();
        String json = null;
        try {
            json = s.load("snap_keys");
        } catch (Exception ignored) {
        }
        if (json == null || json.trim().isEmpty()) return out;
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) return out;
        String inner = json.substring(1, json.length() - 1).trim();
        if (inner.isEmpty()) return out;
        for (String part : splitTopLevel(inner)) {
            String v = unquote(part.trim());
            if (v != null && !v.isEmpty() && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static void ensureKey(Storage s, String key) {
        List<String> ks = keys(s);
        if (ks.contains(key)) return;
        ks.add(key);
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < ks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(q(ks.get(i)));
        }
        sb.append(']');
        s.save("snap_keys", sb.toString());
    }

    // ------------------------------------------------------------------
    // Minimal JSON (same style as SavedMeters — no org.json on the
    // headless unit-test classpath)
    // ------------------------------------------------------------------

    static String encode(List<Snapshot> snaps) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (Snapshot m : snaps) {
            if (m == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"d\":").append(q(m.disco))
              .append(",\"m\":").append(q(m.mode))
              .append(",\"v\":").append(q(m.ref))
              .append(",\"n\":").append(q(m.holderName))
              .append(",\"bm\":").append(q(m.billMonth))
              .append(",\"u\":").append(m.units)
              .append(",\"a\":").append(m.amount)
              .append(",\"ad\":").append(m.amountAfterDue)
              .append(",\"due\":").append(q(m.dueDate))
              .append(",\"fpa\":").append(q(m.fpa))
              .append(",\"cat\":").append(q(m.category))
              .append(",\"pr\":").append(q(m.prevReading))
              .append(",\"cr\":").append(q(m.currReading))
              .append(",\"rd\":").append(q(m.readingDate))
              .append(",\"ts\":").append(m.fetchedAt).append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /** Parses the encode() format into out (tolerant: skips bad items). */
    static void decodeInto(String json, List<Snapshot> out) {
        int i = 0, n = json.length();
        if (n < 2 || json.charAt(0) != '[') return;
        i = 1;
        while (i < n) {
            char c = json.charAt(i);
            if (c == ']') return;
            if (c == '{') {
                int end = findObjectEnd(json, i);
                if (end < 0) return;
                Snapshot m = parseObject(json.substring(i, end + 1));
                if (m != null) out.add(m);
                i = end + 1;
            } else {
                i++;
            }
        }
    }

    private static int findObjectEnd(String json, int start) {
        boolean inStr = false, esc = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') inStr = !inStr;
            else if (c == '}' && !inStr) return i;
        }
        return -1;
    }

    private static Snapshot parseObject(String obj) {
        Snapshot m = new Snapshot();
        m.disco = field(obj, "\"d\"");
        m.mode = field(obj, "\"m\"");
        m.ref = field(obj, "\"v\"");
        m.holderName = field(obj, "\"n\"");
        m.billMonth = field(obj, "\"bm\"");
        m.dueDate = field(obj, "\"due\"");
        m.fpa = field(obj, "\"fpa\"");
        m.category = field(obj, "\"cat\"");
        m.prevReading = field(obj, "\"pr\"");
        m.currReading = field(obj, "\"cr\"");
        m.readingDate = field(obj, "\"rd\"");
        m.units = parseInt(fieldRaw(obj, "\"u\""), -1);
        m.amount = parseDouble(fieldRaw(obj, "\"a\""), -1);
        m.amountAfterDue = parseDouble(fieldRaw(obj, "\"ad\""), -1);
        m.fetchedAt = parseLong(fieldRaw(obj, "\"ts\""), 0L);
        if (m.ref == null || m.ref.isEmpty()) return null;
        if (m.disco == null || m.disco.isEmpty()) m.disco = "lesco";
        if (!"cid".equals(m.mode)) m.mode = "ref";
        return m;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Quoted string value for key (unescapes \" and \\). */
    private static String field(String obj, String key) {
        int k = obj.indexOf(key);
        if (k < 0) return null;
        int colon = obj.indexOf(':', k + key.length());
        if (colon < 0) return null;
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = q1 + 1; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (esc) { sb.append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') return sb.toString();
            sb.append(c);
        }
        return null;
    }

    /** Raw (unquoted) value for key, e.g. a number. */
    private static String fieldRaw(String obj, String key) {
        int k = obj.indexOf(key);
        if (k < 0) return null;
        int colon = obj.indexOf(':', k + key.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && obj.charAt(i) == ' ') i++;
        int j = i;
        while (j < obj.length() && obj.charAt(j) != ','
                && obj.charAt(j) != '}') j++;
        return obj.substring(i, j);
    }

    /** Splits a JSON array body on top-level commas. */
    private static List<String> splitTopLevel(String inner) {
        List<String> out = new ArrayList<String>();
        boolean inStr = false, esc = false;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') inStr = !inStr;
            else if (c == ',' && !inStr) {
                out.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        out.add(inner.substring(start));
        return out;
    }

    /** Unquotes a JSON string literal, or null when not quoted. */
    private static String unquote(String s) {
        if (s == null || s.length() < 2) return null;
        if (!s.startsWith("\"") || !s.endsWith("\"")) return null;
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (esc) { sb.append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Android wiring (SharedPreferences-backed Storage)
    // ------------------------------------------------------------------

    private static Storage store(final Context ctx) {
        return new Storage() {
            @Override public String load(String key) {
                SharedPreferences p = ctx.getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE);
                return p.getString(key, null);
            }
            @Override public void save(String key, String value) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(key, value).apply();
            }
        };
    }

    /** Records a fetched bill (Android). Never throws. */
    public static void record(Context ctx, String disco, String mode,
            String ref, BillInfo b) {
        try {
            Snapshot s = fromBill(b, disco, mode, ref,
                    System.currentTimeMillis());
            record(store(ctx), GuardStore.meterKey(disco, mode, ref), s);
        } catch (Exception ignored) {
            // Snapshot recording must never break the bill screen.
        }
    }

    /** Snapshots for one meter key (Android), newest first. */
    public static List<Snapshot> list(Context ctx, String key) {
        try {
            return list(store(ctx), key);
        } catch (Exception e) {
            return new ArrayList<Snapshot>();
        }
    }

    /** Newest snapshot across ALL saved meters (Android), or null. */
    public static Latest latestAny(Context ctx) {
        try {
            return latestAny(store(ctx));
        } catch (Exception e) {
            return null;
        }
    }
}
