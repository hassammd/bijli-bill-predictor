package com.hatch.bijlibill;

/**
 * v2.5.0: distribution companies served through the shared PITC portal
 * template: https://bill.pitc.com.pk/&lt;pitcPath&gt;/general?refno=...
 * (and the same form-POST search flow for customer IDs).
 *
 * PITC paths per the portal's own URL scheme:
 *   lescobill, iescobill, gepcobill, fescobill, mepcobill,
 *   pescobill, hescobill, sepcobill, qescobill.
 * Non-LESCO discos are NOT live-verified from outside Pakistan
 * (geo-blocked) — the parser degrades gracefully (WebView + whatever
 * summary parsed, never a hard error). K-Electric runs on a different
 * system entirely (no PITC endpoint) and is shown as "coming soon".
 *
 * Plain Java, unit-tested headlessly.
 */
public enum Disco {

    LESCO("lesco", "lescobill", "LESCO", false),
    IESCO("iesco", "iescobill", "IESCO", false),
    GEPCO("gepco", "gepcobill", "GEPCO", false),
    FESCO("fesco", "fescobill", "FESCO", false),
    MEPCO("mepco", "mepcobill", "MEPCO", false),
    PESCO("pesco", "pescobill", "PESCO", false),
    HESCO("hesco", "hescobill", "HESCO", false),
    SEPCO("sepco", "sepcobill", "SEPCO", false),
    QESCO("qesco", "qescobill", "QESCO", false),
    /** Different billing system (no PITC endpoint) — disabled chip. */
    KELECTRIC("ke", null, "K-Electric", true);

    /** Stable code persisted in SharedPreferences. */
    public final String code;
    /** PITC URL path segment; null when {@link #comingSoon}. */
    public final String pitcPath;
    /** Short display name for chips. */
    public final String displayName;
    /** True when there is no PITC endpoint (shown disabled). */
    public final boolean comingSoon;

    Disco(String code, String pitcPath, String displayName,
            boolean comingSoon) {
        this.code = code;
        this.pitcPath = pitcPath;
        this.displayName = displayName;
        this.comingSoon = comingSoon;
    }

    /** https://bill.pitc.com.pk/&lt;pitcPath&gt; — never call when comingSoon. */
    public String baseUrl() {
        return "https://bill.pitc.com.pk/" + pitcPath;
    }

    /** Plain-HTTP twin (single fallback attempt, same policy as LESCO). */
    public String baseUrlHttp() {
        return "http://bill.pitc.com.pk/" + pitcPath;
    }

    /** Full-bill page URL for a 14-digit reference (+ optional suffix). */
    public String generalUrl(String digits, String suffix) {
        return baseUrl() + "/general?refno=" + digits
                + (suffix == null ? "" : suffix);
    }

    /** Lookup by persisted code; unknown codes fall back to LESCO. */
    public static Disco fromCode(String code) {
        if (code != null) {
            for (Disco d : values()) {
                if (d.code.equals(code)) return d;
            }
        }
        return LESCO;
    }

    /** Discos the user can actually fetch from (K-Electric excluded). */
    public static Disco[] fetchable() {
        java.util.List<Disco> out = new java.util.ArrayList<Disco>();
        for (Disco d : values()) {
            if (!d.comingSoon) out.add(d);
        }
        return out.toArray(new Disco[out.size()]);
    }
}
