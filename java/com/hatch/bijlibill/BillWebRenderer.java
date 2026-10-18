package com.hatch.bijlibill;

/**
 * v2.0.5 — renders the fetched PITC bill HTML in the app's WebView so
 * the bill looks EXACTLY like the website. Display never depends on
 * parsing: even if the label parser recognizes nothing, the full page
 * still shows.
 *
 * Pure Java (no Android APIs): the real WebView is adapted to
 * {@link WebViewLike} by the activity, and headless tests assert the
 * exact arguments.
 */
public final class BillWebRenderer {

    private BillWebRenderer() {}

    /**
     * Base URL for loadDataWithBaseURL. The /general bill page uses
     * root-relative resources ("/styles/...", "/images/..."), so the
     * base MUST be the portal origin with a trailing slash — and the
     * historyUrl MUST be null (a non-null history URL would break
     * relative resolution on some WebView versions).
     */
    public static final String BASE_URL = "https://bill.pitc.com.pk/";

    /** Minimal WebView surface this renderer needs (test seam). */
    public interface WebViewLike {
        void loadDataWithBaseURL(String baseUrl, String data,
                String mimeType, String encoding, String historyUrl);
    }

    /**
     * Renders fetched bill HTML exactly like the website.
     *
     * @param wv   the WebView adapter (activity wires the real WebView)
     * @param html raw HTML from the fetch; null/empty renders blank
     */
    public static void render(WebViewLike wv, String html) {
        if (wv == null) {
            throw new IllegalArgumentException("webview missing");
        }
        wv.loadDataWithBaseURL(BASE_URL, html == null ? "" : html,
                "text/html", "UTF-8", null);
    }
}
