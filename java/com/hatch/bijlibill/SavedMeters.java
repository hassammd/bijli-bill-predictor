package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.5.0 "Mere Meter": after every successful bill fetch the app
 * auto-saves {disco, idType, idValue, holderName, savedAt}. Home shows
 * the list (holder name big, masked id + disco chip); tap re-fetches,
 * long-press deletes.
 *
 * Pure logic + a tiny Storage interface so unit tests run headless
 * with an in-memory map; the Android side wires SharedPreferences.
 * Stored as a JSON array; dedupe key is (disco,idValue); cap 20 with
 * the oldest entries evicted. Newest-first order.
 */
public final class SavedMeters {

    /** Maximum saved meters (matches the old saved-refs cap policy). */
    public static final int MAX = 20;

    private SavedMeters() {}

    /** Key-value backing store (SharedPreferences on Android). */
    public interface Storage {
        String load();
        void save(String json);
    }

    /** One saved meter. */
    public static final class Meter {
        public String disco;      // Disco.code, e.g. "lesco"
        public String idType;     // "ref" or "cid"
        public String idValue;    // as typed (14 digits + U/R, or 7 digits)
        public String holderName; // consumer name from the bill
        public long savedAt;      // epoch ms

        public Meter() {}

        public Meter(String disco, String idType, String idValue,
                String holderName, long savedAt) {
            this.disco = disco;
            this.idType = idType;
            this.idValue = idValue;
            this.holderName = holderName;
            this.savedAt = savedAt;
        }
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    /** All saved meters, newest first. Never null. */
    public static List<Meter> load(Storage s) {
        List<Meter> out = new ArrayList<Meter>();
        if (s == null) return out;
        String json;
        try {
            json = s.load();
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

    /**
     * Adds (or refreshes) a meter: dedupe by (disco,idValue), newest
     * first, cap {@link #MAX} (oldest evicted). Blank ids are ignored.
     */
    public static void add(Storage s, String disco, String idType,
            String idValue, String holderName, long savedAt) {
        if (s == null || idValue == null || idValue.trim().isEmpty()) return;
        String d = disco == null ? "lesco" : disco;
        String t = "cid".equals(idType) ? "cid" : "ref";
        String v = idValue.trim();
        List<Meter> cur = load(s);
        List<Meter> out = new ArrayList<Meter>();
        for (Meter m : cur) {
            if (m != null && !(d.equals(m.disco) && v.equals(m.idValue))) {
                out.add(m);
            }
        }
        String name = holderName == null || holderName.trim().isEmpty()
                ? v : holderName.trim();
        out.add(0, new Meter(d, t, v, name, savedAt));
        while (out.size() > MAX) {
            out.remove(out.size() - 1);
        }
        try {
            s.save(encode(out));
        } catch (Exception ignored) {
        }
    }

    /** Removes the meter with (disco,idValue). */
    public static void remove(Storage s, String disco, String idValue) {
        if (s == null) return;
        List<Meter> cur = load(s);
        List<Meter> out = new ArrayList<Meter>();
        for (Meter m : cur) {
            if (m != null && !(eq(disco, m.disco)
                    && eq(idValue, m.idValue))) {
                out.add(m);
            }
        }
        try {
            s.save(encode(out));
        } catch (Exception ignored) {
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * One-time migration of the legacy manual "saved_refs" entries
     * ([name, mode, value], no disco) into this store as lesco meters.
     * Runs once, guarded by a flag the caller keeps.
     */
    public static void migrateLegacy(Storage s,
            List<String[]> legacyItems) {
        if (s == null || legacyItems == null) return;
        for (String[] it : legacyItems) {
            if (it == null || it.length < 3) continue;
            add(s, "lesco", it[1], it[2], it[0], 0L);
        }
    }

    // ------------------------------------------------------------------
    // Display helpers
    // ------------------------------------------------------------------

    /**
     * Masked id for the list row, e.g. "0211********04" for a 14-digit
     * reference or "123****" style for short ids. Pure function.
     */
    public static String maskId(String idValue) {
        if (idValue == null) return "";
        String v = idValue.trim();
        int n = v.length();
        if (n <= 6) return "****";
        StringBuilder sb = new StringBuilder();
        sb.append(v.substring(0, 4));
        for (int i = 4; i < n - 2; i++) sb.append('*');
        sb.append(v.substring(n - 2));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Minimal JSON (no org.json on the headless unit-test classpath)
    // ------------------------------------------------------------------

    static String encode(List<Meter> meters) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (Meter m : meters) {
            if (m == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"d\":").append(q(m.disco))
              .append(",\"t\":").append(q(m.idType))
              .append(",\"v\":").append(q(m.idValue))
              .append(",\"n\":").append(q(m.holderName))
              .append(",\"s\":").append(m.savedAt).append('}');
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
    static void decodeInto(String json, List<Meter> out) {
        int i = 0, n = json.length();
        if (n < 2 || json.charAt(0) != '[') return;
        i = 1;
        while (i < n) {
            char c = json.charAt(i);
            if (c == ']') return;
            if (c == '{') {
                int end = findObjectEnd(json, i);
                if (end < 0) return;
                Meter m = parseObject(json.substring(i, end + 1));
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

    private static Meter parseObject(String obj) {
        Meter m = new Meter();
        m.disco = field(obj, "\"d\"");
        m.idType = field(obj, "\"t\"");
        m.idValue = field(obj, "\"v\"");
        m.holderName = field(obj, "\"n\"");
        String s = fieldRaw(obj, "\"s\"");
        try {
            m.savedAt = s == null ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            m.savedAt = 0L;
        }
        if (m.idValue == null || m.idValue.isEmpty()) return null;
        if (m.disco == null || m.disco.isEmpty()) m.disco = "lesco";
        if (!"cid".equals(m.idType)) m.idType = "ref";
        return m;
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
}
