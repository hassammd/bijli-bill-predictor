package com.hatch.bijlibill;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.3.0 — "Andaza vs Asal Bill" accuracy tracker.
 *
 * Flow: MeterCheckActivity.showResult saves the displayed estimate as
 * the meter's PENDING estimate (latest only — a newer estimate
 * supersedes an older one). When the next bill is fetched,
 * BillActivity consumes the pending estimate, compares it against
 * the fresh bill, shows a comparison card, and files the result
 * into a per-meter history (last 6).
 *
 * The pure math (pct, pushHistory) and the storage layer (Store)
 * are Android-free so they are headless-unit-testable; the thin
 * Context overloads just wire SharedPreferences.
 */
public final class AccuracyTracker {

    private AccuracyTracker() {}

    public static final String PREFS = "accuracy_tracker";
    /** History keeps the last N comparisons per meter. */
    public static final int HISTORY_CAP = 6;

    /** One pending (not-yet-compared) estimate. */
    public static final class Pending {
        public long ts;
        public double estUnits;
        public double estTotal;
    }

    /** One finished comparison. */
    public static final class Comparison {
        public String billMonth = "";
        public double estUnits;
        public double estTotal;
        public int billUnits;
        public double billTotal;
        /** 0..100, 1 decimal. */
        public double pct;
    }

    /**
     * Storage abstraction so the logic is testable without Android.
     * Production uses SharedPreferences; tests use MemStore.
     */
    public interface Store {
        String get(String k);
        void put(String k, String v);
        void remove(String k);
    }

    /** In-memory Store for headless tests. */
    public static final class MemStore implements Store {
        private final Map<String, String> m =
                new HashMap<String, String>();
        @Override public String get(String k) { return m.get(k); }
        @Override public void put(String k, String v) { m.put(k, v); }
        @Override public void remove(String k) { m.remove(k); }
    }

    private static final class PrefsStore implements Store {
        private final SharedPreferences sp;
        PrefsStore(Context ctx) {
            sp = ctx.getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE);
        }
        @Override public String get(String k) {
            try {
                return sp.getString(k, null);
            } catch (Exception e) {
                return null;
            }
        }
        @Override public void put(String k, String v) {
            try {
                sp.edit().putString(k, v).apply();
            } catch (Exception ignored) {
            }
        }
        @Override public void remove(String k) {
            try {
                sp.edit().remove(k).apply();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Pure math
    // ------------------------------------------------------------------

    /**
     * Accuracy percentage: (1 - |est-bill|/bill) * 100, clamped to
     * 0..100 and rounded to 1 decimal. 0.0 when the bill total is
     * missing/non-positive (nothing sane to compare against).
     */
    public static double pct(double estTotal, double billTotal) {
        if (!(billTotal > 0) || !(estTotal >= 0)) return 0.0;
        double p =
                (1.0 - Math.abs(estTotal - billTotal) / billTotal)
                        * 100.0;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        return Math.round(p * 10.0) / 10.0;
    }

    /**
     * Appends a comparison to the history (newest last), dropping
     * the oldest beyond HISTORY_CAP. Pure — the Android wrapper
     * persists the returned list.
     */
    public static List<Comparison> pushHistory(
            List<Comparison> cur, Comparison c) {
        List<Comparison> out =
                new ArrayList<Comparison>(cur == null
                        ? new ArrayList<Comparison>() : cur);
        if (c != null) out.add(c);
        while (out.size() > HISTORY_CAP) out.remove(0);
        return out;
    }

    // ------------------------------------------------------------------
    // Serialization (fields never contain '|' or ';')
    // ------------------------------------------------------------------

    static String serPending(Pending p) {
        return p.ts + "|" + p.estUnits + "|" + p.estTotal;
    }

    static Pending dePending(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split("\\|", -1);
        if (parts.length != 3) return null;
        try {
            Pending p = new Pending();
            p.ts = Long.parseLong(parts[0]);
            p.estUnits = Double.parseDouble(parts[1]);
            p.estTotal = Double.parseDouble(parts[2]);
            if (!(p.estTotal >= 0)) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    static String serComparison(Comparison c) {
        return c.billMonth + "|" + c.estUnits + "|" + c.estTotal
                + "|" + c.billUnits + "|" + c.billTotal + "|" + c.pct;
    }

    static Comparison deComparison(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split("\\|", -1);
        if (parts.length != 6) return null;
        try {
            Comparison c = new Comparison();
            c.billMonth = parts[0];
            c.estUnits = Double.parseDouble(parts[1]);
            c.estTotal = Double.parseDouble(parts[2]);
            c.billUnits = Integer.parseInt(parts[3]);
            c.billTotal = Double.parseDouble(parts[4]);
            c.pct = Double.parseDouble(parts[5]);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Store-level API (testable)
    // ------------------------------------------------------------------

    /** Saves/overwrites the meter's pending estimate (latest only). */
    public static void savePending(Store st, String meterKey,
            double estUnits, double estTotal) {
        if (st == null || meterKey == null) return;
        Pending p = new Pending();
        p.ts = System.currentTimeMillis();
        p.estUnits = estUnits;
        p.estTotal = estTotal;
        st.put("pending_" + meterKey, serPending(p));
    }

    /**
     * Returns the pending estimate and CLEARS it (a pending estimate
     * is consumed exactly once — by the next fetched bill). Null
     * when the meter has no pending estimate.
     */
    public static Pending takePending(Store st, String meterKey) {
        if (st == null || meterKey == null) return null;
        String raw = st.get("pending_" + meterKey);
        st.remove("pending_" + meterKey);
        return dePending(raw);
    }

    /** Files a comparison into the meter's capped history. */
    public static void addComparison(Store st, String meterKey,
            Comparison c) {
        if (st == null || meterKey == null || c == null) return;
        List<Comparison> h = pushHistory(history(st, meterKey), c);
        StringBuilder sb = new StringBuilder();
        for (Comparison x : h) {
            if (sb.length() > 0) sb.append(';');
            sb.append(serComparison(x));
        }
        st.put("hist_" + meterKey, sb.toString());
    }

    /** The meter's comparison history, oldest first. */
    public static List<Comparison> history(Store st, String meterKey) {
        List<Comparison> out = new ArrayList<Comparison>();
        if (st == null || meterKey == null) return out;
        String raw = st.get("hist_" + meterKey);
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            Comparison c = deComparison(part);
            if (c != null) out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Android API (thin SharedPreferences wiring)
    // ------------------------------------------------------------------

    public static void savePending(Context ctx, String meterKey,
            double estUnits, double estTotal) {
        try {
            savePending(new PrefsStore(ctx), meterKey, estUnits,
                    estTotal);
        } catch (Exception ignored) {
        }
    }

    public static Pending takePending(Context ctx, String meterKey) {
        try {
            return takePending(new PrefsStore(ctx), meterKey);
        } catch (Exception e) {
            return null;
        }
    }

    public static void addComparison(Context ctx, String meterKey,
            Comparison c) {
        try {
            addComparison(new PrefsStore(ctx), meterKey, c);
        } catch (Exception ignored) {
        }
    }

    public static List<Comparison> history(Context ctx,
            String meterKey) {
        try {
            return history(new PrefsStore(ctx), meterKey);
        } catch (Exception e) {
            return new ArrayList<Comparison>();
        }
    }
}
