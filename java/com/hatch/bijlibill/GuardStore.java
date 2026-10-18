package com.hatch.bijlibill;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.2.0 — 200-Unit Guard persistence. One anchor
 * {startReading, startDateIso} plus a reading-entry list per meter,
 * keyed by the same "disco:mode:ref" key the bill cache uses, plus
 * the last-opened guard meter (for the daily alarm) and the
 * per-meter last-notified date (one notification per day max).
 */
public final class GuardStore {

    private static final String PREFS = "bijli_bill_guard";
    /** Entries serialized as "yyyy-MM-dd:reading;...". */
    private static final String ENTRY_SEP = ";";

    private GuardStore() {}

    /** One saved meter reading. */
    public static final class Entry {
        public String dateIso;
        public double reading;
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Cache-style meter key shared with BillCache. */
    public static String meterKey(String discoCode, String mode,
            String ref) {
        return discoCode + ":" + mode + ":" + ref;
    }

    // ---- anchor ----

    public static void setAnchor(Context ctx, String key,
            double reading, String dateIso) {
        sp(ctx).edit()
                .putString("anchor_reading_" + key,
                        Double.toString(reading))
                .putString("anchor_date_" + key,
                        dateIso == null ? "" : dateIso)
                .apply();
    }

    public static double anchorReading(Context ctx, String key,
            double fallback) {
        try {
            return Double.parseDouble(sp(ctx).getString(
                    "anchor_reading_" + key, ""));
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String anchorDate(Context ctx, String key) {
        String s = sp(ctx).getString("anchor_date_" + key, "");
        return s == null ? "" : s;
    }

    public static boolean hasAnchor(Context ctx, String key) {
        return sp(ctx).contains("anchor_reading_" + key);
    }

    // ---- entries ----

    public static List<Entry> entries(Context ctx, String key) {
        List<Entry> out = new ArrayList<Entry>();
        String raw = sp(ctx).getString("entries_" + key, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(ENTRY_SEP)) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            try {
                Entry e = new Entry();
                e.dateIso = part.substring(0, c);
                e.reading = Double.parseDouble(
                        part.substring(c + 1));
                if (GuardReminder.parseIso(e.dateIso) == null) continue;
                out.add(e);
            } catch (Exception ignored) {
                // Skip one corrupt entry, keep the rest.
            }
        }
        // ISO dates sort lexicographically == chronologically, so the
        // last element is always the latest reading no matter what
        // order the user entered them in.
        java.util.Collections.sort(out,
                new java.util.Comparator<Entry>() {
                    @Override public int compare(Entry a, Entry b) {
                        return a.dateIso.compareTo(b.dateIso);
                    }
                });
        return out;
    }

    public static void addEntry(Context ctx, String key, String dateIso,
            double reading) {
        List<Entry> list = entries(ctx, key);
        // Replace an entry for the same date; keep chronological order.
        List<Entry> kept = new ArrayList<Entry>();
        for (Entry e : list) {
            if (!e.dateIso.equals(dateIso)) kept.add(e);
        }
        Entry n = new Entry();
        n.dateIso = dateIso;
        n.reading = reading;
        kept.add(n);
        StringBuilder sb = new StringBuilder();
        for (Entry e : kept) {
            if (sb.length() > 0) sb.append(ENTRY_SEP);
            sb.append(e.dateIso).append(':').append(e.reading);
        }
        sp(ctx).edit().putString("entries_" + key, sb.toString())
                .apply();
    }

    public static void clear(Context ctx, String key) {
        sp(ctx).edit()
                .remove("anchor_reading_" + key)
                .remove("anchor_date_" + key)
                .remove("entries_" + key)
                .remove("last_notified_" + key)
                .remove("streak_last_" + key)
                .remove("streak_count_" + key)
                .apply();
    }

    // ---- v3.4.0: daily check-in streak (per meter) ----

    /** yyyy-MM-dd of the last streak check-in ("" when none). */
    public static String streakLast(Context ctx, String key) {
        String s = sp(ctx).getString("streak_last_" + key, "");
        return s == null ? "" : s;
    }

    public static int streakCount(Context ctx, String key) {
        try {
            return sp(ctx).getInt("streak_count_" + key, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void saveStreak(Context ctx, String key,
            String lastIso, int count) {
        sp(ctx).edit()
                .putString("streak_last_" + key,
                        lastIso == null ? "" : lastIso)
                .putInt("streak_count_" + key,
                        Math.max(0, count))
                .apply();
    }

    // ---- last meter / last notified ----

    public static void setLastMeter(Context ctx, String key) {
        sp(ctx).edit().putString("last_meter", key).apply();
    }

    public static String lastMeter(Context ctx) {
        return sp(ctx).getString("last_meter", null);
    }

    public static String lastNotified(Context ctx, String key) {
        return sp(ctx).getString("last_notified_" + key, "");
    }

    public static void setLastNotified(Context ctx, String key,
            String dateIso) {
        sp(ctx).edit().putString("last_notified_" + key, dateIso)
                .apply();
    }

    /**
     * Replaces the whole entry list for a meter (used by the
     * activity's delete flow — entries are append-only otherwise).
     */
    public static void rewriteEntries(Context ctx, String meterKey,
            java.util.List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.dateIso).append(':').append(e.reading);
        }
        sp(ctx).edit().putString("entries_" + meterKey,
                sb.toString()).apply();
    }
}
