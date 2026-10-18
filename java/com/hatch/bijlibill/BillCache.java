package com.hatch.bijlibill;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v2.0.5 — 24-hour on-device cache for the RAW BILL HTML, keyed by
 * "mode:typedref". The WebView renders the cached HTML directly, and
 * the slim summary strip re-parses the small fields from it on a
 * cache hit — so a cached bill never needs the network.
 *
 * (v2.0.4 and earlier cached parsed BillInfo fields under different
 * key names; those entries are simply never read anymore, so the
 * first v2.0.5 lookup refetches once.)
 */
public final class BillCache {

    private static final String PREFS = "bijli_bill_cache_v2";
    /** 24 hours in milliseconds. */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    private BillCache() {}

    /** One cached bill page. */
    public static final class CachedPage {
        /** Raw HTML of the bill page (for the WebView). */
        public String html;
        /** "direct", "direct+suffix" or "form-post" — picks the parser. */
        public String fetchPath;
    }

    /** Stores the raw bill HTML for key (mode:typedref). */
    public static void putHtml(Context ctx, String key, String html,
            String fetchPath) {
        if (html == null || html.isEmpty()) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("hts_" + key, System.currentTimeMillis())
                .putString("hhtml_" + key, html)
                .putString("hpath_" + key,
                        fetchPath == null ? "" : fetchPath)
                .apply();
    }

    /** Returns the cached page, or null if absent/expired/empty. */
    public static CachedPage getHtml(Context ctx, String key) {
        SharedPreferences p =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long ts = p.getLong("hts_" + key, 0);
        if (ts == 0 || System.currentTimeMillis() - ts > TTL_MS) {
            return null;
        }
        String html = p.getString("hhtml_" + key, null);
        if (html == null || html.isEmpty()) return null;
        CachedPage c = new CachedPage();
        c.html = html;
        c.fetchPath = p.getString("hpath_" + key, "");
        return c;
    }
}
