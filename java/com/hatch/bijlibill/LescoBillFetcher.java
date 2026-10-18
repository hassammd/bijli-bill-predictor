package com.hatch.bijlibill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.0.5 fetcher for LESCO bills via the official PITC portal
 * (https://bill.pitc.com.pk/lescobill).
 *
 * v2.0.5 PRIMARY strategy (user-verified 2026-09-20): after the search,
 * PITC serves the FULL bill page directly at
 *   https://bill.pitc.com.pk/lescobill/general?refno=<14digits>
 * (no U/R suffix in the query), so the app now fetches that page FIRST:
 *   (a) GET the portal landing page to harvest cookies into the v2.0.4
 *       cookie jar;
 *   (b) GET .../general?refno=<14digits> with the jar, manual redirects
 *       (max 5, jar re-attached per hop);
 *   (c) if (b) yields no bill fields, retry once with
 *       .../general?refno=<14digits><U/R-as-typed>;
 *   (d) FALLBACK: the unchanged v2.0.4 form-POST flow.
 * A direct page counts as a bill only when it contains the 14-digit
 * reference AND at least two of {payable, units, due date}.
 * The /general HTML is parsed by a GENERIC label->value extractor (no
 * hardcoded positions): table-row next-cell, then same-line
 * "label: value" scan, then generic table-row harvest; the old div#ua
 * parser stays as an additional fallback. v2.0.6: the live site
 * redesigned the bill page (div-based, no tables — title "LESCO ONLINE
 * BILL") — isNewBillPage() detects it and parseNewBillHtml() parses it;
 * both old parsers keep working as fallback. Customer-ID mode keeps the
 * form-POST flow only (best-effort).
 *
 * Real form (from the LESCO Web Bill screenshot, PITC-powered, and from a
 * working third-party PITC scraper whose behavior this class mirrors
 * field-for-field):
 *   - Radio: "Reference No" (14-digit textbox) OR "Customer ID" (7 digits).
 *   - Next to the reference textbox: a separate U/R dropdown
 *     (U = Urban, R = Rural).
 *
 * App UX (v2.0.1+): ONE input field — the user types the number exactly as
 * printed on the bill, e.g. 02118240618604U (trailing letter optional).
 * This class splits it internally: 14 digits go to the reference textbox
 * field, the letter (or default 'U') decides the U/R dropdown form field.
 * No separate visible U/R selector is needed.
 *
 * How the fetch works (ASP.NET WebForms, no CAPTCHA observed as of Sep 2026):
 *   1. GET the bill page with a browser User-Agent; capture the hidden form
 *      tokens: __VIEWSTATE, __VIEWSTATEGENERATOR, __EVENTVALIDATION
 *      (and __RequestVerificationToken when present). If any REQUIRED
 *      token is missing/empty, throw FetchException(TOKEN_ERROR) — NEVER
 *      post with null tokens (that produced blank screens in v2.0.1).
 *      v2.0.4: EVERY Set-Cookie from the GET goes into a small in-memory
 *      jar — the portal's MVC anti-forgery check REJECTS the POST when
 *      the __RequestVerificationToken COOKIE is missing ("The required
 *      anti-forgery cookie ... is not present", observed on a real
 *      user-captured response on 2026-09-19). The form field alone is
 *      not enough.
 *   2. POST back to the same URL (application/x-www-form-urlencoded) with
 *      the tokens plus:
 *        rbSearchByList=refno            (reference mode)
 *        searchTextBox=<14-digit number>  (digits only — never the U/R letter)
 *        ruCodeTextBox="" | "R"           (the separate U/R dropdown field;
 *                                         EMPTY means Urban — the working
 *                                         reference sends "" for U)
 *        btnSearch=Search
 *      Headers: browser User-Agent, Referer=<page url>, Cookie=<the jar>.
 *      Redirects are followed MANUALLY (max 5 hops): the jar is
 *      re-attached on every hop and new Set-Cookie headers are absorbed;
 *      301/302/303 after POST become GET (PRG pattern), 307/308 repeat
 *      the POST with body + cookies.
 *   3. The response is HTML. A plain-text anti-forgery rejection (found
 *      even BEFORE the <!DOCTYPE) throws FetchException(ANTIFORGERY_ERROR)
 *      — never silent. If <div id="ua"> contains text, it is an error
 *      message; the whole response is ALSO scanned for error phrases
 *      ("does not belong", "record not found", ...). Otherwise the bill
 *      is found by GENERIC label matching over EVERY <table><tr>: rows
 *      with >= 2 cells map label -> value by keyword ("Consumer Name",
 *      "Amount Payable", "Due Date", "Units Consumed", ...). If ZERO
 *      fields are recognized, throw FetchException(PARSE_EMPTY) with a
 *      debug line (HTTP status, byte length, div#ua y/n) — the UI never
 *      shows a blank bill screen anymore.
 *
 * Customer-ID mode: same flow with rbSearchByList=custid and the 7-digit id
 * in searchTextBox. The exact radio value "custid" is best-effort and NOT
 * live-verified because bill.pitc.com.pk is unreachable from outside
 * Pakistan (Sep 2026).
 *
 * Plain Java only (no Android APIs) so it is unit-testable on the JVM.
 */
public final class LescoBillFetcher {

    private LescoBillFetcher() {}

    /** PITC portal page for LESCO duplicate-bill lookup. */
    public static final String LESCO_BILL_URL = "https://bill.pitc.com.pk/lescobill";

    /** Plain-HTTP twin of {@link #LESCO_BILL_URL}: tried ONCE when the
     *  HTTPS attempt dies with a TLS/connect failure (user guide,
     *  2026-09-20). The lookup carries no secrets — it is a public
     *  bill query — so one cleartext attempt is acceptable. */
    public static final String LESCO_BILL_URL_HTTP =
            "http://bill.pitc.com.pk/lescobill";

    /** Browser-like User-Agent sent on EVERY request (user guide,
     *  2026-09-20): the portal sees a mobile browser, not a bot. */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    /** Sent alongside the User-Agent on every request (user guide). */
    public static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";

    /** v2.0.5 guide timeouts: connect 15s, read 20s (was 30s/30s). */
    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 20000;

    /** LESCO reference number: exactly 14 digits + optional trailing
     *  U/R letter (U = Urban, R = Rural, case-insensitive) — typed in ONE
     *  field exactly as printed on the bill. */
    private static final Pattern REF_PATTERN =
            Pattern.compile("^\\d{14}[UuRr]?$");

    /** LESCO customer ID: exactly 7 digits. */
    private static final Pattern CUSTID_PATTERN = Pattern.compile("^\\d{7}$");

    /** Best-effort radio value for customer-ID search mode (NOT
     *  live-verified — adjust after first live test on a Pakistani
     *  network if the portal uses a different value). */
    private static final String SEARCH_MODE_CUSTOMER_ID = "custid";

    /** Simple value object for a fetched bill. All fields are raw strings as
     *  printed on the bill; numeric helpers are provided for the key ones.
     *  Fields the portal does not print stay null — the UI skips null rows. */
    public static final class BillInfo {
        public String referenceNumber;
        public String consumerName;
        public String address;
        public String customerId;
        public String category;
        public String tariff;
        public String meterNo;
        public String issueDate;      // billing month / bill date as printed
        public String dueDate;
        public String readingDate;
        public String prevReading;    // previous meter reading as printed
        public String currReading;    // current meter reading as printed
        public String units;          // units consumed as printed
        public String amount;         // amount payable within due date
        public String amountAfterDueDate;
        public String lpSurcharge;    // late payment surcharge
        public String electricityCharges;
        public String subsidy;
        public String netCharges;
        public String gst;
        public String taxes;
        public String fpa;
        public String arrears;
        public String currentBill;
        public String grandTotal;
        // ---- v2.0.5: extra fields printed on the full /general bill page ----
        public String billMonth;      // BILL MONTH, e.g. "SEP 26"
        public String feeder;         // FEEDER, e.g. "(036209) Pandoriyan"
        public String subDivision;    // SUB DIVISION
        public String sanLoad;        // SAN LOAD (sanctioned load)
        public String mf;             // MF (multiplying factor)
        public String tariffCategory; // TARIFF CATEGORY, e.g. "Domestic"
        public String installment;
        public String adjustments;
        public String weCredit;       // W.E Credit
        public String lockOpenCredit; // Lock Open Credit
        /** LP surcharge split: "till 25-EP" part of lpSurcharge. */
        public String surchargeTill25;
        /** LP surcharge split: "after 25-EP" part of lpSurcharge. */
        public String surchargeAfter25;
        /** Payable split: "Till 25-EP" part of amountAfterDueDate. */
        public String payableTill25;
        /** Payable split: "After 25-EP" part of amountAfterDueDate. */
        public String payableAfter25;
        /** Arbitrary extra [label, value] pairs the generic table harvester
         *  found but no known field consumed, in page order. */
        public List<String[]> extraLines = new ArrayList<String[]>();
        /** Extra tables after the main bill table (e.g. bill history),
         *  each a list of [cell0, cell1, ...] rows. */
        public List<List<String[]>> history = new ArrayList<List<String[]>>();

        /** First decimal number found in {@link #units}, or -1. */
        public int unitsAsInt() { return parseInt(units); }
        public int prevReadingAsInt() { return parseInt(prevReading); }
        public int currReadingAsInt() { return parseInt(currReading); }
        /** First decimal number found in {@link #amount}, or -1. */
        public double amountAsDouble() { return parseDouble(amount); }

        private static int parseInt(String s) {
            if (s == null) return -1;
            Matcher m = Pattern.compile("[\\d,]+").matcher(s);
            if (!m.find()) return -1;
            try { return Integer.parseInt(m.group().replace(",", "")); }
            catch (NumberFormatException e) { return -1; }
        }

        private static double parseDouble(String s) {
            if (s == null) return -1;
            Matcher m = Pattern.compile("[\\d,]+(\\.\\d+)?").matcher(s);
            if (!m.find()) return -1;
            try { return Double.parseDouble(m.group().replace(",", "")); }
            catch (NumberFormatException e) { return -1; }
        }

        @Override public String toString() {
            return "BillInfo{ref=" + referenceNumber
                    + ", units=" + units
                    + ", prev=" + prevReading + ", curr=" + currReading
                    + ", amount=" + amount + ", due=" + dueDate
                    + ", month=" + issueDate + "}";
        }
    }

    /** Machine-readable failure kind for fetch-time errors. The UI maps
     *  each kind to a friendly EN/UR message. */
    public enum FetchKind {
        /** Server rejected the number as malformed/unknown. */
        INVALID_REF,
        /** Server says no bill exists for this number. */
        NOT_FOUND,
        /** Transport failed (timeout, DNS, refused, no route...). */
        NETWORK_DOWN,
        /** Portal form tokens could not be extracted from the GET page. */
        TOKEN_ERROR,
        /** Portal rejected the POST because the anti-forgery cookie was
         *  missing/rejected ("The required anti-forgery cookie ... is not
         *  present"). TOKEN_ERROR family: the request was not processed. */
        ANTIFORGERY_ERROR,
        /** Portal answered but no bill fields were recognized in the HTML. */
        PARSE_EMPTY,
        /** No internet (connectivity pre-check failed before any socket
         *  was opened). The UI shows its own offline message. */
        OFFLINE,
        /** The portal answered with an HTTP error status (>= 400). The
         *  exception message is the real status ("HTTP 403") so the UI
         *  can show it honestly. */
        HTTP_ERROR
    }

    /**
     * Fetch-time failure with a machine-readable kind plus a raw debug
     * line shown in the UI's small grey row (never shown to the user as
     * the main message).
     */
    public static final class FetchException extends IOException {
        public final FetchKind kind;
        /** Raw technical detail (HTTP status, lengths, missing tokens...). */
        public final String debug;
        public FetchException(FetchKind kind, String message, String debug) {
            super(message);
            this.kind = kind;
            this.debug = debug == null ? "" : debug;
        }
    }

    /** Thrown when the portal returns a bill-level error (div#ua, or an
     *  error phrase found anywhere in the response) or the reference
     *  number is rejected. */
    public static final class BillErrorException extends IOException {
        /** INVALID_REF or NOT_FOUND — decided by looksLikeNotFound(). */
        public final FetchKind kind;
        public BillErrorException(FetchKind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    // ------------------------------------------------------------------
    // Cookie jar (v2.0.4)
    // ------------------------------------------------------------------

    /**
     * Tiny in-memory cookie jar for ONE fetch session (GET tokens + POST
     * search). The PITC portal is part MVC: its anti-forgery check rejects
     * the search POST with "The required anti-forgery cookie
     * "__RequestVerificationToken" is not present." unless the cookie set
     * by the GET comes back on the POST. The form field alone is NOT
     * enough — proven by a real user-captured response (2026-09-19).
     *
     * Only name=value pairs are kept; attributes (Path, Domain, Expires,
     * Max-Age, HttpOnly, Secure, SameSite) are stripped. Later values
     * overwrite earlier ones for the same name.
     */
    public static final class CookieJar {
        private final java.util.LinkedHashMap<String, String> cookies =
                new java.util.LinkedHashMap<String, String>();

        /** Absorbs raw Set-Cookie response header values. */
        public void absorb(List<String> setCookieHeaders) {
            if (setCookieHeaders == null) return;
            for (String h : setCookieHeaders) {
                if (h == null) continue;
                // Only the part before the FIRST ';' is name=value; the
                // Expires attribute may itself contain a comma, so we
                // never split attributes on ','.
                int semi = h.indexOf(';');
                String pair = (semi < 0 ? h : h.substring(0, semi)).trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) continue; // no name, or "=value" — skip
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                // Strip optional surrounding quotes: name="quoted value".
                if (value.length() >= 2 && value.startsWith("\"")
                        && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!name.isEmpty()) cookies.put(name, value);
            }
        }

        /** Value for the Cookie request header, or "" when empty. */
        public String headerValue() {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        /** Cookie names currently held (for the debug report — values
         *  stay out of the report). */
        public List<String> names() {
            return new ArrayList<String>(cookies.keySet());
        }

        public boolean isEmpty() { return cookies.isEmpty(); }
        public int size() { return cookies.size(); }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Reference numbers are exactly 14 digits with an optional trailing
     *  U/R letter, typed in one field exactly as printed on the bill. */
    public static boolean isValidReference(String ref) {
        return ref != null && REF_PATTERN.matcher(ref.trim()).matches();
    }

    /**
     * Splits a typed reference into [14-digit number, U/R flag].
     * The trailing letter decides the flag (U/u -&gt; "U", R/r -&gt; "R");
     * with no letter the flag defaults to "U". The number never contains
     * the letter — it goes to the reference textbox form field while the
     * flag goes to the separate U/R dropdown field.
     *
     * @return String[2] = { digits14, "U" or "R" }
     * @throws IllegalArgumentException if the reference is malformed
     */
    public static String[] splitReference(String ref) {
        String r = ref == null ? "" : ref.trim();
        if (!isValidReference(r)) {
            throw new IllegalArgumentException(
                    "Reference number must be 14 digits + optional U/R");
        }
        String flag = "U";
        String digits = r;
        char last = r.charAt(r.length() - 1);
        if (last == 'R' || last == 'r') {
            flag = "R";
            digits = r.substring(0, r.length() - 1);
        } else if (last == 'U' || last == 'u') {
            digits = r.substring(0, r.length() - 1);
        }
        return new String[]{digits, flag};
    }

    /** Customer IDs are exactly 7 digits. */
    public static boolean isValidCustomerId(String id) {
        return id != null && CUSTID_PATTERN.matcher(id.trim()).matches();
    }

    /**
     * Heuristic: does a portal error message mean "no bill exists for this
     * number" (as opposed to the number itself being malformed/rejected)?
     * Used to classify server rejections. The portal messages are
     * English; matching is case-insensitive and forgiving.
     */
    public static boolean looksLikeNotFound(String serverMessage) {
        if (serverMessage == null) return false;
        String m = serverMessage.toLowerCase();
        return m.contains("not found") || m.contains("no record")
                || m.contains("does not exist") || m.contains("doesn't exist")
                || m.contains("does not belong") || m.contains("doesn't belong")
                || m.contains("no bill") || m.contains("not exist");
    }

    /**
     * Form value for the U/R dropdown field (ruCodeTextBox). Mirrors the
     * working reference implementation: it sends an EMPTY string for
     * Urban and "R" for Rural — empty defaults to U server-side. So the
     * app NEVER sends an explicit "U".
     *
     * @param flag "U" or "R" (as returned by splitReference)
     */
    public static String ruCodeFormValue(String flag) {
        return "R".equals(flag) ? "R" : "";
    }

    /**
     * Diagnostic snapshot of the LAST fetch round-trip (GET tokens + POST
     * form + response). Populated by doFetch() on every attempt, success
     * or failure. The PARSE_EMPTY screen reads this to build the debug
     * report the user can share.
     */
    public static final class FetchDebug {
        public String timestamp;     // "yyyy-MM-dd HH:mm:ss"
        public String typedRef;      // as the user typed it
        public String mode;          // "ref" or "cid"
        /** v2.0.5: which fetch path produced this response —
         *  "direct", "direct+suffix" or "form-post". */
        public String fetchPath;
        public int httpStatus;
        public String finalUrl;      // after redirects
        public int responseBytes;
        public String title;         // response <title>
        public boolean divUaPresent;
        public String postSummary;   // POST field names + value lengths +
                                     // first/last 20 chars of each token
        public String cookiesSent;   // cookie NAMES sent on the POST
        public int redirectHops;      // hops followed on the POST leg
        /** v2.0.5: "https" or "http" — which scheme produced this
         *  response (HTTPS->HTTP fallback tracking). */
        public String httpUsed;
        /** v2.0.5: free-form note, e.g. why the https attempt failed
         *  before the http fallback. */
        public String note;
        public String rawHtml;       // full last response HTML
    }

    private static volatile FetchDebug lastDebug;

    /** The diagnostic snapshot of the most recent fetch attempt, or null
     *  if no fetch has run yet. */
    public static FetchDebug lastDebug() { return lastDebug; }

    private static String timestampNow() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date());
    }

    /**
     * Builds the exact application/x-www-form-urlencoded POST body for
     * the PITC search form, byte-for-byte. Encoding rules (audited
     * against the working reference implementation):
     *   - UTF-8, each value encoded exactly ONCE (no double-encoding);
     *   - spaces become '+', literal '+' becomes "%2B", '/' -> "%2F",
     *     '=' -> "%3D" (Java URLEncoder matches the browser
     *     form-encoding for these);
     *   - __RequestVerificationToken quirk: the working JS code builds
     *     the body with `new URLSearchParams({__RequestVerificationToken:
     *     requestVerificationToken, ...})`. When the token-harvest GET
     *     page does NOT contain the token, the JS variable is
     *     `undefined` — and URLSearchParams stringifies `undefined` to
     *     the literal string "undefined", so the field IS sent with
     *     value "undefined". We mirror that exactly: tokens[3] == null
     *     -> send the literal string "undefined", NOT an omitted field
     *     and NOT an empty value. When present, the real value is sent.
     *
     * @param tokens {__VIEWSTATE, __VIEWSTATEGENERATOR, __EVENTVALIDATION,
     *               __RequestVerificationToken-or-null} from requireTokens
     */
    public static String buildPostBody(String[] tokens, String searchMode,
            String searchText, String urbanRural) {
        StringBuilder body = new StringBuilder();
        addParam(body, "__VIEWSTATE", tokens[0]);
        addParam(body, "__VIEWSTATEGENERATOR", tokens[1]);
        addParam(body, "__EVENTVALIDATION", tokens[2]);
        // NB the "undefined" quirk documented above — do NOT "fix" this
        // to omit/empty the field; the working reference sends the
        // literal string and ASP.NET may treat a missing field
        // differently from a present-but-unknown one.
        addParam(body, "__RequestVerificationToken",
                tokens[3] == null ? "undefined" : tokens[3]);
        addParam(body, "rbSearchByList", searchMode);
        addParam(body, "searchTextBox", searchText);
        addParam(body, "ruCodeTextBox", ruCodeFormValue(urbanRural));
        addParam(body, "btnSearch", "Search");
        return body.toString();
    }

    /** One-line summary of a POST field for the debug report: name,
     *  value length, first 20 chars, last 20 chars (tokens are long). */
    static String fieldInfo(String name, String value) {
        String v = value == null ? "" : value;
        String first = v.length() <= 20 ? v : v.substring(0, 20);
        String last = v.length() <= 20 ? v : v.substring(v.length() - 20);
        return name + "[len=" + v.length()
                + ", first=\"" + first + "\", last=\"" + last + "\"]";
    }

    /**
     * Summarizes the exact POST that was sent (field names, value
     * lengths, first/last 20 chars of each token) for the debug report.
     * Values are the SENT values, so __RequestVerificationToken shows
     * the literal "undefined" when the GET page lacked it.
     */
    public static String postFieldSummary(String[] tokens, String searchMode,
            String searchText, String urbanRural) {
        return fieldInfo("__VIEWSTATE", tokens[0]) + "; "
                + fieldInfo("__VIEWSTATEGENERATOR", tokens[1]) + "; "
                + fieldInfo("__EVENTVALIDATION", tokens[2]) + "; "
                + fieldInfo("__RequestVerificationToken",
                        tokens[3] == null ? "undefined" : tokens[3]) + "; "
                + fieldInfo("rbSearchByList", searchMode) + "; "
                + fieldInfo("searchTextBox", searchText) + "; "
                + fieldInfo("ruCodeTextBox", ruCodeFormValue(urbanRural)) + "; "
                + fieldInfo("btnSearch", "Search");
    }

    /** Response page <title> (cleaned), or "" when absent. Shown in the
     *  PARSE_EMPTY debug line — the title instantly tells a re-rendered
     *  form apart from a challenge page apart from a bill page. */
    public static String pageTitle(String html) {
        if (html == null) return "";
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>")
                .matcher(withoutComments(html));
        return m.find() ? cleanText(m.group(1)) : "";
    }

    /**
     * True when the raw response carries a plain-text MVC anti-forgery
     * rejection — e.g. `The required anti-forgery cookie
     * "__RequestVerificationToken" is not present.` The portal prints
     * this BEFORE the <!DOCTYPE, so the check must run on the raw HTML,
     * not on parsed tables. Case-insensitive.
     */
    public static boolean looksLikeAntiForgery(String html) {
        if (html == null) return false;
        String t = html.toLowerCase();
        return t.contains("anti-forgery")
                || (t.contains("__requestverificationtoken")
                        && t.contains("not present"));
    }

    /**
     * True when the failure is a network problem (timeout, unknown host,
     * connection refused/reset, no route) rather than a server-side
     * rejection of the reference number. Used by the UI to pick a
     * friendly, honest error message.
     */
    public static boolean isNetworkDown(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches the latest LESCO bill for a reference number typed exactly as
     * printed on the bill (14 digits + optional trailing U/R letter).
     * The letter is split off internally and sent in the U/R dropdown
     * form field; digits go to the reference textbox field.
     * Performs live HTTP against the PITC portal.
     *
     * v2.0.5: tries the direct /general full-bill page FIRST (no suffix,
     * then once with the U/R suffix as typed), and falls back to the
     * v2.0.4 form-POST flow only when the direct pages yield no bill.
     *
     * @param typedReference e.g. "02118240618604U" or "02118240618604"
     * @throws IllegalArgumentException if the reference number is malformed
     * @throws BillErrorException if the portal rejects the number / no bill
     * @throws IOException on transport failures
     */
    public static BillInfo fetchBill(String typedReference)
            throws IOException {
        return fetchBillWith(newTransport(), typedReference);
    }

    /** Production HTTP transport. Public so activities can run fetches
     *  through the same cookie/redirect machinery the tests use. */
    public static HttpTransport newTransport() {
        return new UrlConnectionTransport();
    }

    /**
     * v2.0.5: the fetched bill page — parsed fields PLUS the raw HTML.
     * The UI renders {@link #html} in a WebView (exactly like the
     * website) and uses {@link #info} only for the slim summary strip,
     * so display never depends on parsing. {@link #parseOk} is false
     * when the page carried zero recognizable fields (the old
     * PARSE_EMPTY case) — the UI still shows the page and hides the
     * strip.
     */
    public static final class BillPage {
        /** Parsed fields; sparse (referenceNumber only) when
         *  !parseOk. Never null. */
        public BillInfo info;
        /** Raw HTML of the bill page, for the WebView. Never null. */
        public String html;
        /** True when at least one bill field was recognized. */
        public boolean parseOk;
        /** "direct", "direct+suffix" or "form-post". */
        public String fetchPath;
    }

    /**
     * v2.0.5 fetch chain against any {@link HttpTransport} (testable).
     * Returns a {@link BillPage} (parsed bill + raw HTML for the
     * WebView); zero recognized fields becomes parseOk=false, never a
     * PARSE_EMPTY throw.
     *
     * Chain per scheme:
     *   (a) GET the portal landing page -> cookies into the jar;
     *   (b) GET .../general?refno=&lt;14digits&gt; (jar attached, manual
     *       redirects, max 5);
     *   (c) if (b) yields no bill fields, ONE retry with
     *       ?refno=&lt;14digits&gt;&lt;U/R-as-typed&gt;;
     *   (d) FALLBACK: the unchanged v2.0.4 form-POST flow
     *       ({@link #doFetchWith}).
     * A direct page counts as a bill only via {@link #directBillHit}:
     * the response must contain the 14-digit reference AND at least two
     * of {payable, units, due date}. FetchException (anti-forgery,
     * token, HTTP status) and transport IOExceptions propagate
     * immediately; a server-side error box on a direct leg just moves
     * the chain to the next path. lastDebug() carries fetchPath =
     * "direct", "direct+suffix" or "form-post" and httpUsed =
     * "https"/"http".
     *
     * HTTPS-&gt;HTTP fallback (user guide, 2026-09-20): the whole chain
     * first runs on https://; when it dies with a TLS/connect failure
     * ({@link #shouldFallbackToHttp}), the chain runs ONCE more on
     * http://. The http attempt gets a fresh cookie jar and its own
     * FetchDebug (httpUsed="http", note records the https failure).
     */
    /**
     * v2.5.0: same chain for any disco (PITC shared template). Unknown
     * disco codes fall back to LESCO via {@link Disco#fromCode}.
     */
    public static BillPage fetchBillPageWith(HttpTransport t,
            String typedReference) throws IOException {
        return fetchBillPageWith(t, typedReference, Disco.LESCO);
    }

    /**
     * v2.5.0: the fetch chain against an explicit disco. Per-disco
     * graceful degradation: {@link #directBillHit} and the generic
     * parsers are template-agnostic; when key fields don't parse the
     * page still returns parseOk=false (WebView shows the real bill)
     * instead of throwing.
     */
    public static BillPage fetchBillPageWith(HttpTransport t,
            String typedReference, Disco disco) throws IOException {
        if (disco == null || disco.comingSoon) disco = Disco.LESCO;
        String[] parts = splitReference(typedReference); // validates
        String typed = typedReference.trim();

        FetchDebug dbg = newFetchDebug(typed, "ref");
        dbg.httpUsed = "https";
        try {
            return runChain(t, disco.baseUrl(), typed, parts, dbg);
        } catch (IOException e) {
            if (shouldFallbackToHttp(e)) {
                FetchDebug dbg2 = newFetchDebug(typed, "ref");
                dbg2.httpUsed = "http";
                dbg2.note = "https failed: "
                        + e.getClass().getSimpleName()
                        + (e.getMessage() == null
                                ? "" : ": " + e.getMessage());
                return runChain(t, disco.baseUrlHttp(), typed, parts,
                        dbg2);
            }
            throw e;
        }
    }

    /**
     * One full (a)-(d) chain against a single base URL (scheme). Fills
     * dbg and assigns lastDebug on every terminal path.
     */
    private static BillPage runChain(HttpTransport t, String baseUrl,
            String typed, String[] parts, FetchDebug dbg)
            throws IOException {
        String digits = parts[0];
        String flag = parts[1];
        CookieJar jar = new CookieJar();

        // (a) Seed: harvest cookies from the portal landing page.
        HttpResult seed = requestWith(t, baseUrl, "GET", null, jar);
        checkHttpOk(seed, "seed GET");

        // (b) Direct full-bill page, no suffix.
        BillInfo b = tryDirectLeg(t, jar, baseUrl, digits, "", typed, dbg);
        if (b != null) {
            dbg.fetchPath = "direct";
            lastDebug = dbg;
            return fillPage(b, true, "direct", dbg);
        }
        // (c) One retry with the U/R suffix as typed (default "U").
        BillInfo b2 =
                tryDirectLeg(t, jar, baseUrl, digits, flag, typed, dbg);
        if (b2 != null) {
            dbg.fetchPath = "direct+suffix";
            lastDebug = dbg;
            return fillPage(b2, true, "direct+suffix", dbg);
        }
        // (d) Fallback: the v2.0.4 form-POST flow. Zero recognized
        // fields surface as FetchException(PARSE_EMPTY) here — the page
        // API converts that to parseOk=false instead of throwing.
        try {
            BillInfo b3 =
                    doFetchWith(t, baseUrl, "refno", digits, flag, typed,
                            dbg);
            dbg.fetchPath = "form-post";
            lastDebug = dbg;
            return fillPage(b3, true, "form-post", dbg);
        } catch (FetchException e) {
            if (e.kind == FetchKind.PARSE_EMPTY) {
                dbg.fetchPath = "form-post";
                lastDebug = dbg;
                BillInfo sparse = new BillInfo();
                sparse.referenceNumber = typed;
                return fillPage(sparse, false, "form-post", dbg);
            }
            throw e;
        }
    }

    /** Assembles a BillPage from one chain leg's outcome. */
    private static BillPage fillPage(BillInfo info, boolean parseOk,
            String fetchPath, FetchDebug dbg) {
        BillPage p = new BillPage();
        p.info = info == null ? new BillInfo() : info;
        p.parseOk = parseOk;
        p.fetchPath = fetchPath;
        p.html = dbg.rawHtml == null ? "" : dbg.rawHtml;
        return p;
    }

    /**
     * Compatibility wrapper: the pre-v2.0.5 BillInfo-only API. Zero
     * recognized fields still throws FetchException(PARSE_EMPTY) here
     * (the page API returns parseOk=false instead). All existing
     * behavior is preserved.
     */
    public static BillInfo fetchBillWith(HttpTransport t,
            String typedReference) throws IOException {
        return fetchBillWith(t, typedReference, Disco.LESCO);
    }

    /** v2.5.0: BillInfo-only API for an explicit disco. */
    public static BillInfo fetchBillWith(HttpTransport t,
            String typedReference, Disco disco) throws IOException {
        BillPage p = fetchBillPageWith(t, typedReference, disco);
        if (!p.parseOk) {
            FetchDebug d = lastDebug();
            String meta = d == null ? "no-debug"
                    : "HTTP " + d.httpStatus + ", " + d.responseBytes
                    + " bytes, div#ua=" + (d.divUaPresent ? "y" : "n")
                    + ", title=\"" + (d.title == null ? "" : d.title)
                    + "\"";
            throw new FetchException(FetchKind.PARSE_EMPTY,
                    "no bill fields recognized", meta);
        }
        return p.info;
    }

    /**
     * v2.0.5: rebuilds a BillPage from cached raw HTML (cache-hit path).
     * The parser is chosen by fetchPath ("direct*" -> the /general
     * full-bill parser, anything else -> the result-page parser). Only
     * PARSE_EMPTY becomes parseOk=false; other failures propagate so
     * the caller can treat the cache as stale and refetch.
     */
    public static BillPage pageFromHtml(String html, String refForInfo,
            String fetchPath) throws BillErrorException, FetchException {
        BillPage p = new BillPage();
        p.html = html == null ? "" : html;
        p.fetchPath = fetchPath == null ? "" : fetchPath;
        try {
            BillInfo info = p.fetchPath.startsWith("direct")
                    ? parseFullBillHtml(html, refForInfo)
                    : parseBillHtml(html, refForInfo);
            p.info = info;
            p.parseOk = true;
        } catch (FetchException e) {
            if (e.kind == FetchKind.PARSE_EMPTY) {
                BillInfo sparse = new BillInfo();
                sparse.referenceNumber = refForInfo;
                p.info = sparse;
                p.parseOk = false;
            } else {
                throw e;
            }
        }
        return p;
    }

    /**
     * v2.0.5: URL the user can open in their own browser as a fallback
     * when the app cannot fetch (the error screen's "BROWSER MEIN
     * KHOLO" button). Reference mode -> the /general full-bill page;
     * customer-ID mode -> the portal landing page (the /general URL
     * needs a reference number).
     */
    public static String browserFallbackUrl(String typedRef, String mode) {
        return browserFallbackUrl(typedRef, mode, Disco.LESCO);
    }

    /** v2.5.0: browser fallback for an explicit disco. */
    public static String browserFallbackUrl(String typedRef, String mode,
            Disco disco) {
        if (disco == null || disco.comingSoon) disco = Disco.LESCO;
        if ("cid".equals(mode)) return disco.baseUrl();
        String[] parts = splitReference(typedRef);
        return generalBillUrl(disco.baseUrl(), parts[0], "");
    }

    /**
     * True when the failure is a TLS problem (handshake, cert, protocol
     * — javax.net.ssl.SSLException family), as opposed to a completed
     * exchange. Used with {@link #isNetworkDown} to decide the single
     * HTTPS-&gt;HTTP fallback attempt.
     */
    public static boolean isTlsFailure(IOException e) {
        for (Throwable th = e; th != null; th = th.getCause()) {
            if (th instanceof javax.net.ssl.SSLException) return true;
        }
        return false;
    }

    /**
     * v2.0.5: should the whole chain be retried ONCE over plain HTTP?
     * Yes for transport-level failures (TLS or connect-level: the
     * server might serve HTTP fine), never for completed exchanges
     * (HTTP errors, token problems, anti-forgery rejections, portal
     * error boxes) — those would fail identically over HTTP.
     */
    public static boolean shouldFallbackToHttp(IOException e) {
        return isNetworkDown(e) || isTlsFailure(e);
    }

    /**
     * v2.0.5: fail fast on HTTP error statuses. The portal answers
     * lookups with HTTP 200 + HTML; a 4xx/5xx is a real failure and
     * must surface as the honest "HTTP 403"-style message, never flow
     * into the parsers as if it were a page.
     */
    private static void checkHttpOk(HttpResult r, String leg)
            throws FetchException {
        if (r.status >= 400) {
            throw new FetchException(FetchKind.HTTP_ERROR,
                    "HTTP " + r.status,
                    "HTTP " + r.status + " on " + leg
                            + " (" + r.finalUrl + ")");
        }
    }

    /**
     * v2.0.5: the REPORT BHEJO HTML-comment header. Pure function of
     * the FetchDebug snapshot so tests can assert its fields. Never
     * includes cookie VALUES (names only) or token values.
     */
    public static String debugReportHeader(FetchDebug d, String appVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!--\n");
        sb.append("BILL DEBUG REPORT (auto-generated, Bijli Bill v")
                .append(appVersion == null ? "?" : appVersion).append(")\n");
        sb.append("timestamp: ").append(nz(d.timestamp)).append('\n');
        sb.append("typed reference: ").append(nz(d.typedRef)).append('\n');
        sb.append("mode: ").append(nz(d.mode)).append('\n');
        sb.append("fetch path: ").append(nz(d.fetchPath)).append('\n');
        sb.append("http/https used: ").append(nz(d.httpUsed)).append('\n');
        if (d.note != null && !d.note.isEmpty()) {
            sb.append("note: ").append(d.note).append('\n');
        }
        sb.append("HTTP status: ").append(d.httpStatus).append('\n');
        sb.append("final URL: ").append(nz(d.finalUrl)).append('\n');
        sb.append("response bytes: ").append(d.responseBytes).append('\n');
        sb.append("page title: ").append(nz(d.title)).append('\n');
        sb.append("div#ua present: ").append(d.divUaPresent).append('\n');
        if (d.postSummary != null && !d.postSummary.isEmpty()) {
            sb.append("POST fields: ").append(d.postSummary).append('\n');
        }
        sb.append("cookies sent: ").append(nz(d.cookiesSent)).append('\n');
        sb.append("redirect hops: ").append(d.redirectHops).append('\n');
        sb.append("-->\n");
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Offline-first fetch entry point (user guide: check internet
     * before the request). Throws FetchException(OFFLINE) BEFORE any
     * socket is opened when the checker reports no connectivity.
     * Customer-ID mode uses the form-POST flow only (best-effort);
     * reference mode uses {@link #fetchBillPageWith}.
     */
    public static final class FetchOrchestrator {
        private FetchOrchestrator() {}

        /** Implemented by the activity with ConnectivityManager. */
        public interface OnlineChecker {
            boolean isOnline();
        }

        public static BillPage fetchOrThrow(HttpTransport t,
                OnlineChecker online, String typedRef, String mode)
                throws IOException {
            return fetchOrThrow(t, online, typedRef, mode, Disco.LESCO);
        }

        /** v2.5.0: orchestrator for an explicit disco. */
        public static BillPage fetchOrThrow(HttpTransport t,
                OnlineChecker online, String typedRef, String mode,
                Disco disco) throws IOException {
            if (online != null && !online.isOnline()) {
                throw new FetchException(FetchKind.OFFLINE, "offline",
                        "connectivity pre-check: no internet, "
                                + "no request sent");
            }
            if ("cid".equals(mode)) {
                BillInfo info = fetchByCustomerIdWith(t, typedRef, disco);
                BillPage p = new BillPage();
                p.info = info;
                p.parseOk = true;
                p.fetchPath = "form-post";
                FetchDebug d = lastDebug();
                p.html = d == null || d.rawHtml == null ? "" : d.rawHtml;
                return p;
            }
            return fetchBillPageWith(t, typedRef, disco);
        }
    }

    /** Fresh FetchDebug with timestamp + typed ref + mode pre-filled. */
    static FetchDebug newFetchDebug(String typedRef, String mode) {
        FetchDebug dbg = new FetchDebug();
        dbg.timestamp = timestampNow();
        dbg.typedRef = typedRef;
        dbg.mode = mode;
        return dbg;
    }

    /**
     * v2.0.5: URL of the PITC full-bill page for a 14-digit reference.
     * User-verified 2026-09-20: after search, PITC serves the full bill
     * at .../lescobill/general?refno=&lt;14digits&gt; (no U/R suffix).
     */
    public static String generalBillUrl(String digits) {
        return generalBillUrl(digits, "");
    }

    /**
     * v2.0.5: same with an explicit suffix appended to the refno query
     * value (the "as typed" retry leg uses "U" or "R"; "" = no suffix).
     */
    public static String generalBillUrl(String digits, String suffix) {
        return generalBillUrl(LESCO_BILL_URL, digits, suffix);
    }

    /**
     * v2.0.5: full-bill URL against an explicit base (https or http —
     * the http-fallback leg needs the http base).
     */
    public static String generalBillUrl(String baseUrl, String digits,
            String suffix) {
        return baseUrl + "/general?refno=" + digits
                + (suffix == null ? "" : suffix);
    }

    /**
     * v2.0.5: does a direct /general response look like a real bill?
     * Success = the response contains the 14-digit reference AND at
     * least two of {payable, units, due date} (case-insensitive).
     */
    public static boolean directBillHit(String html, String digits) {
        if (html == null || digits == null || digits.isEmpty()) return false;
        if (!html.contains(digits)) return false;
        String t = html.toLowerCase();
        int hits = 0;
        if (t.contains("payable")) hits++;
        if (t.contains("units")) hits++;
        if (t.contains("due date")) hits++;
        return hits >= 2;
    }

    /**
     * v2.0.5: ONE direct /general leg (no-suffix or suffix retry).
     * Returns the parsed BillInfo when {@link #directBillHit} passes
     * and the page parses; returns null when the page is not a bill
     * (chain moves on). Throws FetchException (anti-forgery /
     * parse-empty) and IOException — those are real problems, not
     * "try the next path" signals. A portal error box
     * (BillErrorException) on THIS leg just means "not a bill here".
     * dbg is filled (and lastDebug assigned) on every leg so the REPORT
     * flow can capture the /general response too.
     */
    static BillInfo tryDirectLeg(HttpTransport t, CookieJar jar,
            String baseUrl, String digits, String suffix, String refForInfo,
            FetchDebug dbg) throws IOException {
        String url = generalBillUrl(baseUrl, digits, suffix);
        HttpResult r = requestWith(t, url, "GET", null, jar);
        checkHttpOk(r, "direct GET");
        String html = r.body;
        dbg.httpStatus = r.status;
        dbg.finalUrl = r.finalUrl;
        dbg.responseBytes =
                html.getBytes(StandardCharsets.UTF_8).length;
        dbg.title = pageTitle(html);
        dbg.divUaPresent = errorDivText(html) != null;
        dbg.cookiesSent = jar.names().toString();
        dbg.redirectHops = r.hops;
        dbg.rawHtml = html;
        lastDebug = dbg;
        if (!directBillHit(html, digits)) return null;
        try {
            return parseFullBillHtml(html, refForInfo);
        } catch (BillErrorException e) {
            // Server-side rejection on this path — the chain tries the
            // next path (suffix retry, then the form-POST fallback).
            return null;
        }
    }

    /**
     * Fetches the latest LESCO bill for a 7-digit customer ID.
     * The radio value "custid" is best-effort and NOT live-verified —
     * see the class javadoc.
     *
     * @throws IllegalArgumentException if the customer ID is malformed
     * @throws BillErrorException if the portal rejects the ID / no bill
     * @throws IOException on transport failures
     */
    public static BillInfo fetchByCustomerId(String customerId)
            throws IOException {
        return fetchByCustomerIdWith(newTransport(), customerId);
    }

    /**
     * v2.0.5: customer-ID lookup against any {@link HttpTransport},
     * with the same single HTTPS-&gt;HTTP fallback as reference mode.
     *
     * v2.5.0: the portal's customer-ID search is a form-POST with the
     * best-effort radio value {@link #SEARCH_MODE_CUSTOMER_ID}; it is
     * NOT live-verified (geo-blocked) — see README. The toggle stays
     * in the UI; on failure the REAL portal error is shown, never a
     * faked success.
     */
    public static BillInfo fetchByCustomerIdWith(HttpTransport t,
            String customerId) throws IOException {
        return fetchByCustomerIdWith(t, customerId, Disco.LESCO);
    }

    /** v2.5.0: customer-ID lookup against an explicit disco. */
    public static BillInfo fetchByCustomerIdWith(HttpTransport t,
            String customerId, Disco disco) throws IOException {
        if (disco == null || disco.comingSoon) disco = Disco.LESCO;
        String id = customerId == null ? "" : customerId.trim();
        if (!isValidCustomerId(id)) {
            throw new IllegalArgumentException(
                    "Customer ID must be exactly 7 digits");
        }
        try {
            return doFetchWith(t, disco.baseUrl(), SEARCH_MODE_CUSTOMER_ID,
                    id, "U", id);
        } catch (IOException e) {
            if (shouldFallbackToHttp(e)) {
                return doFetchWith(t, disco.baseUrlHttp(),
                        SEARCH_MODE_CUSTOMER_ID, id, "U", id);
            }
            throw e;
        }
    }

    /**
     * Extracts the WebForms postback tokens from the form page. Throws
     * FetchException(TOKEN_ERROR) when any REQUIRED token is
     * missing/empty — the caller must NEVER post with null tokens (that
     * produced blank screens in v2.0.1).
     *
     * @return {__VIEWSTATE, __VIEWSTATEGENERATOR, __EVENTVALIDATION,
     *          __RequestVerificationToken-or-null}
     */
    public static String[] requireTokens(String html) throws FetchException {
        String viewState = hiddenValue(html, "__VIEWSTATE");
        String viewStateGen = hiddenValue(html, "__VIEWSTATEGENERATOR");
        String eventValidation = hiddenValue(html, "__EVENTVALIDATION");
        String requestToken = hiddenValue(html, "__RequestVerificationToken");
        StringBuilder missing = new StringBuilder();
        if (viewState == null || viewState.isEmpty()) missing.append("__VIEWSTATE ");
        if (viewStateGen == null || viewStateGen.isEmpty()) missing.append("__VIEWSTATEGENERATOR ");
        if (eventValidation == null || eventValidation.isEmpty()) missing.append("__EVENTVALIDATION ");
        if (missing.length() > 0) {
            throw new FetchException(FetchKind.TOKEN_ERROR,
                    "portal form tokens missing",
                    "missing tokens: " + missing.toString().trim());
        }
        return new String[]{viewState, viewStateGen, eventValidation, requestToken};
    }

    /**
     * One GET (tokens) + one POST (search form) round-trip.
     *
     * @param searchMode  radio value: "refno" or SEARCH_MODE_CUSTOMER_ID
     * @param searchText  digits typed into the search box
     * @param urbanRural  "U" or "R" for the U/R dropdown field
     * @param refForInfo  value stored on BillInfo.referenceNumber
     */
    private static BillInfo doFetch(String searchMode, String searchText,
            String urbanRural, String refForInfo) throws IOException {
        return doFetchAtUrl(LESCO_BILL_URL, searchMode, searchText,
                urbanRural, refForInfo);
    }

    /**
     * Same round-trip against an arbitrary page URL. Public so headless
     * tests can run the full GET->POST flow (incl. cookies + redirects)
     * against a local test server; the app always calls it with
     * {@link #LESCO_BILL_URL}.
     */
    public static BillInfo doFetchAtUrl(String pageUrl, String searchMode,
            String searchText, String urbanRural, String refForInfo)
            throws IOException {
        return doFetchWith(new UrlConnectionTransport(), pageUrl,
                searchMode, searchText, urbanRural, refForInfo);
    }

    /**
     * Full GET-&gt;POST round-trip against any {@link HttpTransport}.
     * Public so tests can script the whole flow (token harvest, cookie
     * jar sharing, redirects, anti-forgery detection) with canned
     * responses and zero TCP.
     */
    public static BillInfo doFetchWith(HttpTransport t, String pageUrl,
            String searchMode, String searchText, String urbanRural,
            String refForInfo) throws IOException {
        return doFetchWith(t, pageUrl, searchMode, searchText, urbanRural,
                refForInfo, newFetchDebug(refForInfo,
                        "refno".equals(searchMode) ? "ref" : "cid"));
    }

    /**
     * Same round-trip, filling a caller-supplied FetchDebug (used by
     * the v2.0.5 chain so one snapshot covers the whole attempt).
     * v2.0.5: HTTP error statuses fail fast via {@link #checkHttpOk}.
     */
    public static BillInfo doFetchWith(HttpTransport t, String pageUrl,
            String searchMode, String searchText, String urbanRural,
            String refForInfo, FetchDebug dbg) throws IOException {
        CookieJar jar = new CookieJar();

        // Step 1: GET the form page -> tokens. Every Set-Cookie goes
        // into the jar (the portal's anti-forgery check needs the
        // __RequestVerificationToken COOKIE on the POST, not just the
        // form field — proven by the 2026-09-19 capture).
        HttpResult get = requestWith(t, pageUrl, "GET", null, jar);
        checkHttpOk(get, "form GET");
        String[] tokens = requireTokens(get.body);

        // Step 2: POST the search form back. searchTextBox carries digits
        // only; the U/R choice is its own dropdown field sent as "" (U)
        // or "R" — never an explicit "U" (mirrors the working reference
        // implementation). The body is built by buildPostBody()
        // (byte-exact, incl. the __RequestVerificationToken "undefined"
        // quirk — see its javadoc).
        String bodyStr = buildPostBody(tokens, searchMode, searchText,
                urbanRural);
        HttpResult post = requestWith(t, pageUrl, "POST", bodyStr, jar);
        checkHttpOk(post, "search POST");

        String resultHtml = post.body;
        int byteLen = resultHtml.getBytes(StandardCharsets.UTF_8).length;
        String debugMeta = "HTTP " + post.status + ", " + byteLen + " bytes";

        // Snapshot for the PARSE_EMPTY debug report (and any later
        // diagnosis) — populated on EVERY attempt before parsing. The
        // caller-supplied dbg already carries timestamp/typedRef/mode;
        // httpUsed reflects the scheme actually used here.
        dbg.fetchPath = "form-post";
        dbg.httpUsed = pageUrl.startsWith("http://") ? "http" : "https";
        dbg.httpStatus = post.status;
        dbg.finalUrl = post.finalUrl;
        dbg.responseBytes = byteLen;
        dbg.title = pageTitle(resultHtml);
        dbg.divUaPresent = errorDivText(resultHtml) != null;
        dbg.postSummary = postFieldSummary(tokens, searchMode, searchText,
                urbanRural);
        dbg.cookiesSent = jar.names().toString();
        dbg.redirectHops = post.hops;
        dbg.rawHtml = resultHtml;
        lastDebug = dbg;

        return parseBillHtml(resultHtml, refForInfo, debugMeta);
    }

    /**
     * Parses a PITC bill-result page (pure function, no network).
     * Throws BillErrorException when the page carries an error
     * (div#ua, or an error phrase found anywhere in the response).
     * Throws FetchException(PARSE_EMPTY) when the page parses but ZERO
     * bill fields were recognized — the UI must never show a blank bill.
     */
    public static BillInfo parseBillHtml(String html, String refNo)
            throws BillErrorException, FetchException {
        return parseBillHtml(html, refNo, "no-meta");
    }

    /**
     * Same as {@link #parseBillHtml(String, String)} with a debug string
     * (HTTP status, byte length) included in the PARSE_EMPTY debug line.
     */
    public static BillInfo parseBillHtml(String html, String refNo, String debugMeta)
            throws BillErrorException, FetchException {
        boolean uaPresent = errorDivText(html) != null;

        // 0. Anti-forgery rejection FIRST: the portal prints this as
        //    PLAIN TEXT before the <!DOCTYPE (so table parsing would
        //    never see it). Never silent — distinct error kind.
        if (looksLikeAntiForgery(html)) {
            throw new FetchException(FetchKind.ANTIFORGERY_ERROR,
                    "anti-forgery cookie rejected by portal",
                    debugMeta + ", anti-forgery cookie missing");
        }

        // v2.0.6: NEW LESCO bill design — checked BEFORE the old
        // div#ua/table logic (kept as the fallback below). The new
        // design prints benign "Not Found" UI text ("Tax Certificate
        // Not Found!") on every real bill, which the broad
        // error-phrase scan in step 2 would mistake for a portal
        // error — so the detector must run first.
        if (isNewBillPage(html, refNo)) {
            return parseNewBillHtml(html, refNo);
        }

        // 1. Portal error box.
        String error = errorDivText(html);
        if (error != null && !error.isEmpty()) {
            throw new BillErrorException(
                    looksLikeNotFound(error) ? FetchKind.NOT_FOUND
                            : FetchKind.INVALID_REF,
                    error);
        }

        // 2. Broadened scan: error phrases anywhere in the response.
        String snippet = scanForErrorText(html);
        if (snippet != null) {
            throw new BillErrorException(FetchKind.NOT_FOUND, snippet);
        }

        BillInfo b = new BillInfo();
        b.referenceNumber = refNo;

        // 3. Generic label matching over EVERY table's rows (like the
        //    working reference's parseBillDetails): rows with >= 2 cells,
        //    label = cells[0] lowercased/trimmed (&nbsp; stripped,
        //    whitespace collapsed), value = cells[1] trimmed. Order
        //    matters for overlapping labels (e.g. "after due date"
        //    before "due date"; "current reading" before generic
        //    "reading" matches; "amount payable" before plain "payable").
        List<List<String[]>> tbls = tables(html);
        for (List<String[]> tbl : tbls) {
            for (String[] row : tbl) {
                if (row.length < 2) continue;
                String label = row[0].toLowerCase().trim();
                String value = row[1].trim();
                if (label.isEmpty() || value.isEmpty()) continue;
                mapRow(b, label, value);
            }
        }

        // 4. Fallback: first element whose id/class contains "amount".
        if (b.amount == null) {
            String disp = displayAmountFallback(html);
            if (disp != null) b.amount = disp;
        }

        // 5. Later tables: keep ones that look like bill history.
        for (int i = 1; i < tbls.size(); i++) {
            List<String[]> t = tbls.get(i);
            if (t.size() >= 2 && looksLikeHistory(t.get(0))) {
                b.history.add(t);
            }
        }

        // 6. Never hand the UI a blank bill: zero recognized fields means
        //    the page layout changed — say so honestly with diagnostics.
        //    The page <title> is included: it instantly tells a
        //    re-rendered form apart from a challenge/bill page.
        if (recognizedFieldCount(b) == 0) {
            throw new FetchException(FetchKind.PARSE_EMPTY,
                    "no bill fields recognized",
                    debugMeta + ", div#ua=" + (uaPresent ? "y" : "n")
                            + ", title=\"" + pageTitle(html) + "\"");
        }
        return b;
    }

    // ------------------------------------------------------------------
    // v2.0.5 generic full-bill parser (PITC /general page)
    // ------------------------------------------------------------------

    /**
     * v2.0.5: parses a PITC /general full-bill page with a GENERIC
     * label->value extractor — NO hardcoded positions:
     *   Phase 1: every <table> row with >= 2 cells: label = cell[0]
     *            (normalized, case-insensitive), value = the NEAREST
     *            cell (cell[1]).
     *   Phase 2: label/value text OUTSIDE tables: the value is the rest
     *            of the same line after the label/colon.
     *   Phase 3: bill-history tables harvested generically (any table
     *            whose header row is exactly [MONTH, ...] with >= 3
     *            columns), any table index.
     *   Phase 4: surcharge / payable "till 25-EP / after 25-EP" splits.
     *   Phase 5: if ZERO fields were recognized, the old div#ua/table
     *            parser runs as an additional fallback source (it throws
     *            PARSE_EMPTY when it also finds nothing).
     * Rows whose label matches no known field are kept in
     * {@link BillInfo#extraLines} (label + value, page order) so the UI
     * can still show every charge line the site printed — but they do
     * NOT count as recognized fields (an unrecognized harvest must
     * never mask a PARSE_EMPTY).
     * Same error contract as {@link #parseBillHtml}: anti-forgery first,
     * then div#ua, then broad error-phrase scan.
     */
    public static BillInfo parseFullBillHtml(String html, String refNo)
            throws BillErrorException, FetchException {
        if (looksLikeAntiForgery(html)) {
            throw new FetchException(FetchKind.ANTIFORGERY_ERROR,
                    "anti-forgery cookie rejected by portal",
                    "direct /general leg, anti-forgery cookie missing");
        }
        // v2.0.6: new bill design first (same reason as in
        // parseBillHtml); the generic phases below stay as fallback.
        if (isNewBillPage(html, refNo)) {
            return parseNewBillHtml(html, refNo);
        }
        String error = errorDivText(html);
        if (error != null && !error.isEmpty()) {
            throw new BillErrorException(
                    looksLikeNotFound(error) ? FetchKind.NOT_FOUND
                            : FetchKind.INVALID_REF,
                    error);
        }
        String snippet = scanForErrorText(html);
        if (snippet != null) {
            throw new BillErrorException(FetchKind.NOT_FOUND, snippet);
        }

        BillInfo b = new BillInfo();
        b.referenceNumber = refNo;

        // Phase 1: table rows, value = nearest next cell. History
        // tables are skipped here — they are harvested generically in
        // phase 3 (their rows are not label/value pairs).
        List<List<String[]>> tbls = tables(html);
        for (List<String[]> tbl : tbls) {
            if (!tbl.isEmpty() && looksLikeHistoryTable(tbl.get(0))) continue;
            for (String[] row : tbl) {
                if (row.length < 2) continue;
                String label = normLabel(row[0]);
                String value = row[1].trim();
                if (label.isEmpty() || value.isEmpty()) continue;
                if (!applyFullLabel(b, label, value)) {
                    addExtraLine(b, row[0].trim(), value);
                }
            }
        }

        // Phase 2: same-line "label: value" text outside tables.
        mapLineLabels(b, html);

        // Phase 3: generic bill-history harvest (any table index).
        for (List<String[]> tbl : tbls) {
            if (tbl.size() >= 2 && looksLikeHistoryTable(tbl.get(0))
                    && !b.history.contains(tbl)) {
                b.history.add(tbl);
            }
        }

        // Phase 4: till/after 25-EP splits.
        splitAmountVariants(b);

        // Phase 5: old parser as an additional fallback source.
        if (recognizedFieldCount(b) == 0) {
            return parseBillHtml(html, refNo, "direct /general leg");
        }
        return b;
    }

    // ------------------------------------------------------------------
    // v2.0.6: new-design bill detector + parser (PITC /general, Sep 2026)
    // ------------------------------------------------------------------

    /**
     * v2.0.6 bill detector for the NEW LESCO bill design (div-based
     * page, no tables): true iff the page contains the typed reference
     * digits AND at least one of:
     *   - the page title contains "LESCO ONLINE BILL"
     *     (case-insensitive), or
     *   - the page has &gt;= 1 slip-matrix-value div, or
     *   - the page has &gt;= 5 en-lbl labels.
     * Checked BEFORE the old div#ua/table logic (kept as the fallback):
     * the new design prints benign "Not Found" UI text ("Tax
     * Certificate Not Found!") on every real bill, which the old broad
     * error-phrase scan would mistake for a portal error, and its
     * fields live in divs the old table parser never sees.
     */
    public static boolean isNewBillPage(String html, String typedRef) {
        if (html == null || html.isEmpty()) return false;
        String digits = typedRef == null ? ""
                : typedRef.replaceAll("\\D", "");
        if (digits.isEmpty() || !html.contains(digits)) return false;
        String nc = withoutComments(html);
        String title = pageTitle(nc);
        if (title != null
                && title.toLowerCase().contains("lesco online bill")) {
            return true;
        }
        if (countOccurrences(nc, "slip-matrix-value") >= 1) return true;
        return countOccurrences(nc, "class=\"en-lbl") >= 5;
    }

    /** Non-overlapping occurrences of needle in h. */
    private static int countOccurrences(String h, String needle) {
        int n = 0;
        int i = 0;
        while ((i = h.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** Digits only ("2,152" -&gt; "2152"). */
    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /**
     * Balanced-div extractor: returns {innerStart, innerEnd,
     * afterCloseTag} for the &lt;div&gt; opened at openTagStart, or null
     * when unbalanced. Only div open/close tags affect depth; every
     * other tag is skipped. A depth-counting scan (not a flat regex) is
     * required because values nest divs/spans whose closing tags a
     * non-greedy pattern would stop at too early.
     */
    static int[] extractDiv(String html, int openTagStart) {
        int gt = html.indexOf('>', openTagStart);
        if (gt < 0) return null;
        if (gt > openTagStart && html.charAt(gt - 1) == '/') {
            // Self-closing <div .../> — no inner content.
            return new int[]{gt + 1, gt + 1, gt + 1};
        }
        int depth = 1;
        int i = gt + 1;
        int n = html.length();
        while (depth > 0 && i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) return null;
            if (html.startsWith("</div", lt) && isTagEnd(html, lt + 5)) {
                int ge = html.indexOf('>', lt);
                if (ge < 0) return null;
                depth--;
                i = ge + 1;
                if (depth == 0) return new int[]{gt + 1, lt, i};
            } else if (html.startsWith("<div", lt)
                    && isTagEnd(html, lt + 4)) {
                int ge = html.indexOf('>', lt);
                if (ge < 0) return null;
                if (html.charAt(ge - 1) != '/') depth++;
                i = ge + 1;
            } else {
                i = lt + 1;
            }
        }
        return null;
    }

    /** True when the char at pos ends a tag name (or pos is past end). */
    private static boolean isTagEnd(String html, int pos) {
        if (pos >= html.length()) return true;
        char c = html.charAt(pos);
        return c == '>' || c == '/' || Character.isWhitespace(c);
    }

    /**
     * v2.0.6: parses the NEW LESCO bill design:
     *  1. Header fields: each &lt;div class="label-row"&gt; carries
     *     &lt;span class="en-lbl"&gt;LABEL&lt;/span&gt;&lt;span
     *     class="ur-lbl"&gt;...&lt;/span&gt;; the value is any remaining
     *     text inside the same div, else the next &lt;div
     *     class="val-space"&gt; sibling (before the next label-row).
     *     Generic label-&gt;value map, no hardcoded positions.
     *  2. Payment slip: slip-matrix-value divs — the slip-main-amount
     *     (or first bare value) is the payable amount, "SEP 26" the
     *     bill month, "22 SEP 26" the due date.
     *  3. Late-payment tiers: lp-surcharge-period ("Till ..."/"After
     *     ...") + the following lp-surcharge-bottom-val (commas
     *     stripped); fallback: slip-late-box "Till 25--EP&lt;br/&gt;2152"
     *     divs.
     *  4. Charges: interleaved charges-bd-en label / charges-bd-val
     *     amount span pairs (class-prefix match — some labels carry a
     *     charges-bd-en--strong modifier).
     *  5. History: history-row / history-cell divs (best-effort, never
     *     blocks the bill).
     * Same honesty contract as the other parsers: PARSE_EMPTY when zero
     * fields were recognized. NB: the broad error-phrase scan is
     * deliberately NOT run here — the new design prints "Tax
     * Certificate Not Found!" on every real bill.
     */
    public static BillInfo parseNewBillHtml(String html, String refNo)
            throws BillErrorException, FetchException {
        if (looksLikeAntiForgery(html)) {
            throw new FetchException(FetchKind.ANTIFORGERY_ERROR,
                    "anti-forgery cookie rejected by portal",
                    "new-design bill page, anti-forgery cookie missing");
        }
        String error = errorDivText(html);
        if (error != null && !error.isEmpty()) {
            throw new BillErrorException(
                    looksLikeNotFound(error) ? FetchKind.NOT_FOUND
                            : FetchKind.INVALID_REF,
                    error);
        }

        String nc = withoutComments(html);
        BillInfo b = new BillInfo();
        b.referenceNumber = refNo;
        parseNewLabelRows(b, nc);
        parseNewSlip(b, nc);
        parseNewLpSurcharge(b, nc);
        parseNewCharges(b, nc);
        try {
            parseNewHistory(b, nc);
        } catch (RuntimeException e) {
            // Best-effort: a broken history section must never block
            // the bill itself.
        }
        if (recognizedFieldCount(b) == 0) {
            throw new FetchException(FetchKind.PARSE_EMPTY,
                    "no bill fields recognized",
                    "new-design bill page, title=\""
                            + pageTitle(nc) + "\"");
        }
        return b;
    }

    /**
     * v2.0.6 step 1: generic label-row -&gt; value harvest. For each
     * label-row div: strip the en-lbl/ur-lbl spans from its inner HTML;
     * leftover text is the value, otherwise the next val-space sibling
     * (searched only up to the next label-row so footer labels without
     * values never steal a distant one). Rows with an empty en-lbl are
     * skipped. Known labels feed BillInfo via applyFullLabel;
     * anything else lands in extraLines.
     */
    static void parseNewLabelRows(BillInfo b, String html) {
        Pattern rowP =
                Pattern.compile("<div\\s+class=\"label-row(\\s[^\"]*)?\"[^>]*>");
        Pattern enP = Pattern.compile(
                "<span\\s+class=\"en-lbl(\\s[^\"]*)?\"[^>]*>(.*?)</span>",
                Pattern.DOTALL);
        Pattern lblSpanP = Pattern.compile(
                "(?s)<span\\s+class=\"(?:en|ur)-lbl(\\s[^\"]*)?\"[^>]*>"
                        + ".*?</span>");
        Pattern vsP =
                Pattern.compile("<div\\s+class=\"val-space(\\s[^\"]*)?\"[^>]*>");
        List<int[]> rows = new ArrayList<int[]>();
        Matcher rm = rowP.matcher(html);
        while (rm.find()) {
            int[] d = extractDiv(html, rm.start());
            // {rowStart, innerStart, innerEnd, afterClose}
            if (d != null) rows.add(
                    new int[]{rm.start(), d[0], d[1], d[2]});
        }
        for (int ri = 0; ri < rows.size(); ri++) {
            int[] r = rows.get(ri);
            String inner = html.substring(r[1], r[2]);
            Matcher em = enP.matcher(inner);
            if (!em.find()) continue;
            String label = cleanText(em.group(2));
            if (label.isEmpty()) continue;
            String rest = lblSpanP.matcher(inner).replaceAll(" ");
            String value = cleanText(rest);
            if (value.isEmpty()) {
                int limit = (ri + 1 < rows.size())
                        ? rows.get(ri + 1)[0] : html.length();
                Matcher vm = vsP.matcher(html);
                if (vm.find(r[3]) && vm.start() < limit) {
                    int[] vd = extractDiv(html, vm.start());
                    if (vd != null) {
                        value = cleanText(
                                html.substring(vd[0], vd[1]));
                    }
                }
            }
            if (value.isEmpty()) continue;
            if (!applyFullLabel(b, normLabel(label), value)) {
                addExtraLine(b, label, value);
            }
        }
    }

    /**
     * v2.0.6 step 2: payment slip. slip-matrix-value divs hold the
     * payable amount (slip-main-amount, else the first bare numeric
     * value), the bill month ("SEP 26") and the due date ("22 SEP
     * 26"). slip-late-box divs are skipped here (step 3 owns them).
     */
    static void parseNewSlip(BillInfo b, String html) {
        Pattern p = Pattern.compile(
                "<div\\s+class=\"([^\"]*slip-matrix-value[^\"]*)\"[^>]*>");
        Matcher m = p.matcher(html);
        String payable = null;
        String month = null;
        String due = null;
        while (m.find()) {
            int[] d = extractDiv(html, m.start());
            if (d == null) continue;
            String cls = m.group(1);
            if (cls.contains("slip-late-box")) continue;
            String val = cleanText(html.substring(d[0], d[1]));
            if (val.isEmpty()) continue;
            if (cls.contains("slip-main-amount")) {
                if (payable == null) payable = val;
            } else if (val.matches(
                    "(?i)^\\d{1,2}\\s+[a-z]{3}\\s+\\d{2,4}$")) {
                if (due == null) due = val;
            } else if (val.matches("(?i)^[a-z]{3}\\s+\\d{2,4}$")) {
                if (month == null) month = val;
            } else if (payable == null && val.matches("[\\d,]+")) {
                payable = val;
            }
        }
        if (payable != null && b.amount == null) b.amount = payable;
        if (due != null && b.dueDate == null) b.dueDate = due;
        if (month != null && b.billMonth == null) b.billMonth = month;
    }

    /** Cleaned text of the first &lt;div&gt; whose class contains cls. */
    private static String divTextByClass(String html, String cls) {
        Matcher m = Pattern.compile(
                "<div\\s+class=\"[^\"]*" + cls + "[^\"]*\"[^>]*>")
                .matcher(html);
        if (!m.find()) return "";
        int[] d = extractDiv(html, m.start());
        return d == null ? "" : cleanText(html.substring(d[0], d[1]));
    }

    /**
     * v2.0.6 step 3: late-payment tiers. Primary: each
     * lp-surcharge-data-col gives top-val (the surcharge) + period
     * ("Till 25--EP"/"After 25--EP") + bottom-val (the payable amount,
     * commas stripped). Fallback: slip-late-box divs ("Till
     * 25--EP&lt;br/&gt;2152"). Period text containing "Till" feeds the
     * till-25 fields, "After" the after-25 fields.
     */
    static void parseNewLpSurcharge(BillInfo b, String html) {
        List<String[]> pairs = new ArrayList<String[]>();
        // [period, bottomVal, topVal]
        Matcher cm = Pattern.compile(
                "<div\\s+class=\"[^\"]*lp-surcharge-data-col[^\"]*\"[^>]*>")
                .matcher(html);
        while (cm.find()) {
            int[] d = extractDiv(html, cm.start());
            if (d == null) continue;
            String seg = html.substring(d[0], d[1]);
            String top = divTextByClass(seg, "lp-surcharge-top-val");
            String period = divTextByClass(seg, "lp-surcharge-period");
            String bottom = divTextByClass(seg, "lp-surcharge-bottom-val");
            if (!period.isEmpty() && !bottom.isEmpty()) {
                pairs.add(new String[]{period, bottom, top});
            }
        }
        if (pairs.isEmpty()) {
            Matcher lm = Pattern.compile(
                    "<div\\s+class=\"[^\"]*slip-late-box[^\"]*\"[^>]*>")
                    .matcher(html);
            while (lm.find()) {
                int[] d = extractDiv(html, lm.start());
                if (d == null) continue;
                String[] parts =
                        html.substring(d[0], d[1])
                                .split("(?i)<br\\s*/?>");
                if (parts.length == 2) {
                    String period = cleanText(parts[0]);
                    String val = cleanText(parts[1]);
                    if (!period.isEmpty() && !val.isEmpty()) {
                        pairs.add(new String[]{period, val, ""});
                    }
                }
            }
        }
        String tillPeriod = null;
        String tillVal = null;
        String tillS = null;
        String afterPeriod = null;
        String afterVal = null;
        String afterS = null;
        for (String[] pr : pairs) {
            String low = pr[0].toLowerCase();
            if (low.contains("till")) {
                tillPeriod = pr[0];
                tillVal = pr[1];
                tillS = pr[2];
            } else if (low.contains("after")) {
                afterPeriod = pr[0];
                afterVal = pr[1];
                afterS = pr[2];
            }
        }
        if (tillVal != null) {
            b.payableTill25 = digitsOnly(tillVal);
            if (b.surchargeTill25 == null && !tillS.isEmpty()) {
                b.surchargeTill25 = digitsOnly(tillS);
            }
        }
        if (afterVal != null) {
            b.payableAfter25 = digitsOnly(afterVal);
            if (b.surchargeAfter25 == null && !afterS.isEmpty()) {
                b.surchargeAfter25 = digitsOnly(afterS);
            }
        }
        if (tillPeriod != null && afterPeriod != null
                && b.amountAfterDueDate == null) {
            // NB: keep the page's own "2,152" formatting here (the
            // digits-only variants live in payableTill25/After25).
            b.amountAfterDueDate = cleanText(tillPeriod) + " "
                    + cleanText(tillVal) + " / " + cleanText(afterPeriod)
                    + " " + cleanText(afterVal);
        }
        if (tillS != null && afterS != null && !tillS.isEmpty()
                && !afterS.isEmpty() && b.lpSurcharge == null) {
            b.lpSurcharge = digitsOnly(tillS) + " / "
                    + digitsOnly(afterS);
        }
    }

    /**
     * v2.0.6 step 4: charge lines. Each charges-bd-en span (class
     * prefix match — some labels carry a charges-bd-en--strong
     * modifier) pairs with the NEXT charges-bd-val span in document
     * order. Generic: every pair goes through applyFullLabel, the rest
     * to extraLines.
     */
    static void parseNewCharges(BillInfo b, String html) {
        Pattern enP = Pattern.compile(
                "<span\\s+class=\"charges-bd-en(\\s[^\"]*)?\"[^>]*>(.*?)</span>",
                Pattern.DOTALL);
        Pattern vP = Pattern.compile(
                "<span\\s+class=\"charges-bd-val(\\s[^\"]*)?\"[^>]*>(.*?)</span>",
                Pattern.DOTALL);
        Matcher em = enP.matcher(html);
        while (em.find()) {
            String label = cleanText(em.group(2));
            if (label.isEmpty()) continue;
            Matcher vm = vP.matcher(html);
            if (!vm.find(em.end())) continue;
            String value = cleanText(vm.group(2));
            if (value.isEmpty()) continue;
            if (!applyFullLabel(b, normLabel(label), value)) {
                addExtraLine(b, label, value);
            }
        }
    }

    /**
     * v2.0.6 step 5: bill history. Each history-row div contributes
     * one [month, status, units, bill, payment] row from its
     * history-cell divs. Best-effort: the caller wraps this so a
     * broken history section can never block the bill.
     */
    static void parseNewHistory(BillInfo b, String html) {
        List<String[]> table = new ArrayList<String[]>();
        Matcher rm = Pattern.compile(
                "<div\\s+class=\"history-row\"[^>]*>").matcher(html);
        while (rm.find()) {
            int[] d = extractDiv(html, rm.start());
            if (d == null) continue;
            String seg = html.substring(d[0], d[1]);
            List<String> cells = new ArrayList<String>();
            Matcher cm = Pattern.compile(
                    "<div\\s+class=\"history-cell\"[^>]*>").matcher(seg);
            while (cm.find()) {
                int[] cd = extractDiv(seg, cm.start());
                if (cd == null) continue;
                cells.add(cleanText(seg.substring(cd[0], cd[1])));
            }
            if (!cells.isEmpty()) {
                table.add(cells.toArray(new String[0]));
            }
        }
        if (!table.isEmpty()) b.history.add(table);
    }

    /**
     * Maps one normalized (lowercase, whitespace-collapsed) label to a
     * BillInfo field. First match per field wins. Returns true when the
     * label was consumed (even when the value only confirmed an
     * already-set field, e.g. REFERENCE NO); false -> the caller keeps
     * the row in extraLines.
     * Ordering: most specific labels first (e.g. "tariff category"
     * before "tariff", "after due date" before "due date"/"payable",
     * "consumer id" before "consumer", "net"+"charges" before
     * "electricity charges").
     */
    static boolean applyFullLabel(BillInfo b, String label, String value) {
        if (label.contains("reference no") || label.contains("reference number")) {
            // Already set from the typed ref; consume so it never lands
            // in extraLines.
            return true;
        } else if (label.contains("consumer id")
                || (label.contains("customer") && label.contains("id"))) {
            if (b.customerId == null) b.customerId = value;
        } else if (label.contains("consumer type")) {
            if (b.category == null) b.category = value;
        } else if (label.contains("name & address")
                || (label.contains("name") && label.contains("address"))) {
            // The /general page prints name+address under ONE label.
            if (b.consumerName == null) b.consumerName = value;
        } else if (label.contains("consumer") || label.contains("name")) {
            if (b.consumerName == null) b.consumerName = value;
        } else if (label.contains("address")) {
            if (b.address == null) b.address = value;
        } else if (label.contains("feeder")) {
            if (b.feeder == null) b.feeder = value;
        } else if (label.contains("sub division") || label.contains("subdivision")) {
            if (b.subDivision == null) b.subDivision = value;
        } else if (label.contains("tariff category")) {
            if (b.tariffCategory == null) b.tariffCategory = value;
        } else if (label.contains("tariff")) {
            if (b.tariff == null) b.tariff = value;
        } else if (label.contains("san load") || label.contains("sanctioned load")) {
            if (b.sanLoad == null) b.sanLoad = value;
        } else if (label.contains("category") || label.contains("connection type")) {
            if (b.category == null) b.category = value;
        } else if (label.contains("meter no") || label.contains("meter number")) {
            if (b.meterNo == null) b.meterNo = value;
        } else if (wholeWord(label, "mf")) {
            if (b.mf == null) b.mf = value;
        } else if (label.contains("previous reading")) {
            if (b.prevReading == null) b.prevReading = value;
        } else if (label.contains("present reading") || label.contains("current reading")) {
            if (b.currReading == null) b.currReading = value;
        } else if (label.contains("reading date")) {
            if (b.readingDate == null) b.readingDate = value;
        } else if (label.contains("units") || label.contains("consumption")) {
            if (b.units == null) b.units = value;
        } else if (label.contains("bill month")) {
            if (b.billMonth == null) b.billMonth = value;
        } else if (label.contains("issue date")) {
            if (b.issueDate == null) b.issueDate = value;
        } else if (label.contains("after due date")) {
            if (b.amountAfterDueDate == null) b.amountAfterDueDate = value;
        } else if (label.contains("payable within due date")
                || label.contains("amount payable")
                || label.contains("total amount")
                || label.contains("payable")) {
            if (b.amount == null) b.amount = value;
        } else if (label.contains("surcharge") || label.contains("late payment")) {
            if (b.lpSurcharge == null) b.lpSurcharge = value;
        } else if (label.contains("due date")) {
            if (b.dueDate == null) b.dueDate = value;
        } else if (label.contains("net") && label.contains("charges")) {
            // NB: before "electricity charges" — the bill prints
            // "Net Electricity Charges 83.97 %".
            if (b.netCharges == null) b.netCharges = value;
        } else if (label.contains("total electricity charges")
                || label.contains("electricity charges")) {
            if (b.electricityCharges == null) b.electricityCharges = value;
        } else if (label.contains("subsid")) {
            if (b.subsidy == null) b.subsidy = value;
        } else if (label.contains("gst") || label.contains("sales tax")) {
            if (b.gst == null) b.gst = value;
        } else if (label.contains("taxes") || label.contains("tax")) {
            if (b.taxes == null) b.taxes = value;
        } else if (label.contains("fpa") || label.contains("fuel price")) {
            if (b.fpa == null) b.fpa = value;
        } else if (label.contains("arrear")) {
            if (b.arrears == null) b.arrears = value;
        } else if (label.contains("current bill")) {
            if (b.currentBill == null) b.currentBill = value;
        } else if (label.contains("installment")) {
            if (b.installment == null) b.installment = value;
        } else if (label.contains("adjustment")) {
            if (b.adjustments == null) b.adjustments = value;
        } else if (label.contains("w.e") || label.contains("we credit")) {
            if (b.weCredit == null) b.weCredit = value;
        } else if (label.contains("lock open credit")) {
            if (b.lockOpenCredit == null) b.lockOpenCredit = value;
        } else if (label.contains("grand total")) {
            if (b.grandTotal == null) b.grandTotal = value;
        } else {
            return false;
        }
        return true;
    }

    /** Lowercase + whitespace-collapsed label normalization. */
    static String normLabel(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /** True when word appears as a whole word (e.g. "mf", not inside
     *  another token). */
    static boolean wholeWord(String label, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b")
                .matcher(label).find();
    }

    /**
     * Keeps an unrecognized [label, value] row for the UI's generic
     * charge-line list. De-duplicated by label (case-insensitive).
     */
    static void addExtraLine(BillInfo b, String label, String value) {
        String ln = normLabel(label);
        for (String[] e : b.extraLines) {
            if (normLabel(e[0]).equals(ln)) return;
        }
        b.extraLines.add(new String[]{label, value});
    }

    /**
     * Phase 2 fragments, most specific first — the FIRST fragment found
     * in a line decides the mapping, so ordering mirrors
     * applyFullLabel's chain (e.g. "tariff category" before "tariff",
     * "after due date" before "due date", "name & address" before
     * "name").
     */
    private static final String[] LINE_FRAGMENTS = {
        "reference no", "reference number",
        "consumer id", "customer id", "consumer type",
        "name & address", "name", "address",
        "feeder", "sub division", "subdivision",
        "tariff category", "tariff",
        "san load", "sanctioned load", "category", "connection type",
        "meter no", "meter number", "mf",
        "previous reading", "present reading", "current reading",
        "reading date", "units", "consumption",
        "bill month", "issue date",
        "after due date", "payable within due date", "amount payable",
        "total amount", "payable",
        "surcharge", "late payment", "due date",
        "net", "total electricity charges", "electricity charges",
        "subsid", "sales tax", "gst", "taxes", "tax",
        "fuel price", "fpa", "arrear", "current bill",
        "installment", "adjustment", "w.e", "we credit",
        "lock open credit", "grand total"
    };

    /**
     * Phase 2: scans text OUTSIDE <table> elements for "label: value"
     * lines — the value is the rest of the same line after the label.
     * Tables are stripped first so phase-1 (next-cell) results keep
     * priority via first-wins.
     */
    static void mapLineLabels(BillInfo b, String html) {
        if (html == null) return;
        // Comments are not content: strip them (and tables) before the
        // line scan so label words inside <!-- ... --> notes never map.
        String noTables = withoutComments(html)
                .replaceAll("(?is)<table.*?</table>", "\n");
        String t = noTables.replaceAll(
                "(?i)</?(p|div|br|tr|td|th|li|h[1-6]|span|b|strong|em|i)[^>]*>",
                "\n");
        // NB: split into lines BEFORE cleanText — cleanText collapses
        // newlines, so cleaning must happen per line.
        for (String rawLine : t.split("\n")) {
            String line = cleanText(rawLine);
            if (line.isEmpty()) continue;
            String low = line.toLowerCase();
            for (String frag : LINE_FRAGMENTS) {
                int idx = "mf".equals(frag)
                        ? wholeWordIndex(low, frag)
                        : low.indexOf(frag);
                if (idx >= 0) {
                    String after = line.substring(idx + frag.length());
                    after = after.replaceAll("^[\\s:;\\-\\u2013\\u2014]+", "")
                            .trim();
                    if (!after.isEmpty()) {
                        applyFullLabel(b, frag, after);
                    }
                    break; // first fragment per line wins
                }
            }
        }
    }

    /** Index of a whole-word occurrence, or -1. */
    private static int wholeWordIndex(String low, String word) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(word) + "\\b")
                .matcher(low);
        return m.find() ? m.start() : -1;
    }

    /**
     * Stricter history-table test for the /general page: header row
     * whose FIRST cell is exactly "month" and which has >= 3 columns —
     * so 2-column bill-detail rows like ["BILL MONTH", "SEP 26"] are
     * never misclassified as history.
     */
    static boolean looksLikeHistoryTable(String[] headerRow) {
        if (headerRow == null || headerRow.length < 3) return false;
        return "month".equals(normLabel(headerRow[0]));
    }

    /**
     * Phase 4: splits the bill's combined surcharge/payable values into
     * their "till 25-EP" / "after 25-EP" variants.
     *   LP SURCHARGE "87 / 173" -> surchargeTill25="87",
     *     surchargeAfter25="173".
     *   PAYABLE AFTER DUE DATE "Till 25-EP 2,152 / After 25-EP 2,238" ->
     *     payableTill25="2,152", payableAfter25="2,238".
     */
    static void splitAmountVariants(BillInfo b) {
        if (b.lpSurcharge != null && b.surchargeTill25 == null) {
            String[] parts = b.lpSurcharge.split("/");
            if (parts.length == 2
                    && parts[0].matches("(?s).*\\d.*")
                    && parts[1].matches("(?s).*\\d.*")) {
                b.surchargeTill25 = parts[0].trim();
                b.surchargeAfter25 = parts[1].trim();
            }
        }
        if (b.amountAfterDueDate != null && b.payableTill25 == null) {
            String low = b.amountAfterDueDate.toLowerCase();
            int ai = low.indexOf("after");
            if (ai >= 0) {
                b.payableTill25 =
                        lastNumber(b.amountAfterDueDate.substring(0, ai));
                b.payableAfter25 = lastNumber(
                        b.amountAfterDueDate.substring(ai + 5));
            } else {
                int ti = low.indexOf("till");
                if (ti >= 0) {
                    b.payableTill25 = lastNumber(
                            b.amountAfterDueDate.substring(ti + 4));
                }
            }
        }
    }

    /** Last [\d,]+ run in s, or null. */
    static String lastNumber(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("[\\d,]+").matcher(s);
        String last = null;
        while (m.find()) last = m.group();
        return (last != null && last.matches(".*\\d.*")) ? last : null;
    }

    /**
     * Maps one normalized label/value pair into BillInfo. First match
     * per field wins (labels repeat across page sections).
     */
    private static void mapRow(BillInfo b, String label, String value) {
        if (label.contains("consumer type")) {
            if (b.category == null) b.category = value;
        } else if (label.contains("consumer") || label.contains("name")) {
            if (b.consumerName == null) b.consumerName = value;
        } else if (label.contains("address")) {
            if (b.address == null) b.address = value;
        } else if (label.contains("customer") && label.contains("id")) {
            if (b.customerId == null) b.customerId = value;
        } else if (label.contains("meter no") || label.contains("meter number")) {
            if (b.meterNo == null) b.meterNo = value;
        } else if (label.contains("category") || label.contains("connection type")) {
            if (b.category == null) b.category = value;
        } else if (label.contains("tariff")) {
            if (b.tariff == null) b.tariff = value;
        } else if (label.contains("grand total")) {
            if (b.grandTotal == null) b.grandTotal = value;
        } else if (label.contains("after due date")) {
            // NB: before "due date" and before "payable" — "Amount Payable
            // (After Due Date)" contains all three substrings.
            if (b.amountAfterDueDate == null) b.amountAfterDueDate = value;
        } else if (label.contains("amount payable") || label.contains("total amount")
                || label.contains("payable")) {
            // NB: before "due date" — "Amount Payable (Within Due Date)"
            // contains that substring too.
            if (b.amount == null) b.amount = value;
        } else if (label.contains("surcharge") || label.contains("late payment")) {
            if (b.lpSurcharge == null) b.lpSurcharge = value;
        } else if (label.contains("due date")) {
            if (b.dueDate == null) b.dueDate = value;
        } else if (label.contains("issue date") || label.contains("bill date")
                || label.contains("billing month") || label.contains("bill month")) {
            if (b.issueDate == null) b.issueDate = value;
        } else if (label.contains("current reading")) {
            // NB: before the generic "reading" checks below.
            if (b.currReading == null) b.currReading = value;
        } else if (label.contains("previous reading")) {
            if (b.prevReading == null) b.prevReading = value;
        } else if (label.contains("reading date")) {
            if (b.readingDate == null) b.readingDate = value;
        } else if (label.contains("units") || label.contains("consumption")) {
            if (b.units == null) b.units = value;
        } else if (label.contains("net charges")) {
            if (b.netCharges == null) b.netCharges = value;
        } else if (label.contains("electricity charges")) {
            if (b.electricityCharges == null) b.electricityCharges = value;
        } else if (label.contains("subsidy")) {
            if (b.subsidy == null) b.subsidy = value;
        } else if (label.contains("gst") || label.contains("sales tax")) {
            if (b.gst == null) b.gst = value;
        } else if (label.contains("tax")) {
            if (b.taxes == null) b.taxes = value;
        } else if (label.contains("fpa") || label.contains("fuel price")) {
            if (b.fpa == null) b.fpa = value;
        } else if (label.contains("arrear")) {
            if (b.arrears == null) b.arrears = value;
        } else if (label.contains("current bill")) {
            if (b.currentBill == null) b.currentBill = value;
        }
    }

    /** Counts parsed (non-null) fields; referenceNumber is excluded since
     *  the app sets it, not the portal. */
    private static int recognizedFieldCount(BillInfo b) {
        int n = 0;
        if (b.consumerName != null) n++;
        if (b.address != null) n++;
        if (b.customerId != null) n++;
        if (b.category != null) n++;
        if (b.tariff != null) n++;
        if (b.meterNo != null) n++;
        if (b.issueDate != null) n++;
        if (b.dueDate != null) n++;
        if (b.readingDate != null) n++;
        if (b.prevReading != null) n++;
        if (b.currReading != null) n++;
        if (b.units != null) n++;
        if (b.amount != null) n++;
        if (b.amountAfterDueDate != null) n++;
        if (b.lpSurcharge != null) n++;
        if (b.electricityCharges != null) n++;
        if (b.subsidy != null) n++;
        if (b.netCharges != null) n++;
        if (b.gst != null) n++;
        if (b.taxes != null) n++;
        if (b.fpa != null) n++;
        if (b.arrears != null) n++;
        if (b.currentBill != null) n++;
        if (b.grandTotal != null) n++;
        // v2.0.5 full-bill fields (extraLines deliberately excluded: an
        // unrecognized-label harvest must never mask a PARSE_EMPTY).
        if (b.billMonth != null) n++;
        if (b.feeder != null) n++;
        if (b.subDivision != null) n++;
        if (b.sanLoad != null) n++;
        if (b.mf != null) n++;
        if (b.tariffCategory != null) n++;
        if (b.installment != null) n++;
        if (b.adjustments != null) n++;
        if (b.weCredit != null) n++;
        if (b.lockOpenCredit != null) n++;
        if (b.surchargeTill25 != null) n++;
        if (b.surchargeAfter25 != null) n++;
        if (b.payableTill25 != null) n++;
        if (b.payableAfter25 != null) n++;
        return n;
    }

    /**
     * Case-insensitive scan of the whole response for portal error
     * phrases. Returns the matched snippet (trimmed, max ~120 chars) or
     * null when nothing matched.
     */
    public static String scanForErrorText(String html) {
        if (html == null) return null;
        String[] phrases = {
            "does not belong", "doesn't belong", "record not found",
            "not found", "invalid reference", "invalid consumer",
            "no such record"
        };
        String text = cleanText(html).toLowerCase();
        for (String p : phrases) {
            int i = text.indexOf(p);
            if (i >= 0) {
                int start = Math.max(0, i - 40);
                int end = Math.min(text.length(), i + p.length() + 40);
                String snippet = text.substring(start, end)
                        .replaceAll("\\s+", " ").trim();
                if (snippet.length() > 120) snippet = snippet.substring(0, 120);
                return snippet;
            }
        }
        return null;
    }

    /**
     * Fallback: first element whose id or class attribute contains
     * "amount" — returns its cleaned text, or null. Used only when the
     * table parser found no payable amount.
     */
    public static String displayAmountFallback(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile(
                "(?is)<(div|span|td|p|b|strong)[^>]*\\b(?:id|class)\\s*=\\s*[\"'][^\"']*amount[^\"']*[\"'][^>]*>(.*?)</\\1>")
                .matcher(withoutComments(html));
        while (m.find()) {
            String t = cleanText(m.group(2));
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    /** A history table's header row mentions "month". */
    private static boolean looksLikeHistory(String[] headerRow) {
        for (String c : headerRow) {
            if (c != null && c.toLowerCase().contains("month")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // HTML helpers (regex-based; the app has no HTML parser dependency)
    // ------------------------------------------------------------------

    /** Value of <input name="..."> in a form page, or null. */
    public static String hiddenValue(String html, String fieldName) {
        Matcher tag = Pattern.compile("(?i)<input\\b[^>]*>").matcher(html);
        while (tag.find()) {
            String t = tag.group();
            Matcher name = Pattern.compile(
                    "(?i)\\bname\\s*=\\s*\"([^\"]*)\"").matcher(t);
            if (name.find() && fieldName.equals(name.group(1))) {
                Matcher val = Pattern.compile(
                        "(?i)\\bvalue\\s*=\\s*\"([^\"]*)\"").matcher(t);
                return val.find() ? val.group(1) : "";
            }
        }
        return null;
    }

    /** Text inside <div id="ua"> (portal error box), or null if absent. */
    public static String errorDivText(String html) {
        Matcher m = Pattern.compile(
                "(?is)<div[^>]*\\bid\\s*=\\s*[\"']ua[\"'][^>]*>(.*?)</div>")
                .matcher(withoutComments(html));
        if (!m.find()) return null;
        return cleanText(m.group(1));
    }

    /** All tables on the page, each a list of [cell0, cell1, ...] rows. */
    public static List<List<String[]>> tables(String html) {
        List<List<String[]>> out = new ArrayList<List<String[]>>();
        Matcher tm = Pattern.compile("(?is)<table[^>]*>(.*?)</table>")
                .matcher(withoutComments(html));
        while (tm.find()) {
            List<String[]> rows = new ArrayList<String[]>();
            Matcher tr = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>")
                    .matcher(tm.group(1));
            while (tr.find()) {
                List<String> cells = new ArrayList<String>();
                Matcher td = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>")
                        .matcher(tr.group(1));
                while (td.find()) cells.add(cleanText(td.group(1)));
                if (!cells.isEmpty()) rows.add(cells.toArray(new String[0]));
            }
            if (!rows.isEmpty()) out.add(rows);
        }
        return out;
    }

    /** All <tr> rows as [cell0, cell1, ...] with tags stripped (all tables). */
    public static List<String[]> tableRows(String html) {
        List<String[]> rows = new ArrayList<String[]>();
        for (List<String[]> t : tables(html)) rows.addAll(t);
        return rows;
    }

    /** Removes <!-- ... --> comments so commented-out markup is never parsed. */
    private static String withoutComments(String html) {
        if (html == null) return "";
        return html.replaceAll("(?s)<!--.*?-->", " ");
    }

    private static String cleanText(String s) {        if (s == null) return "";
        String t = s.replaceAll("(?s)<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&#160;", " ")
             .replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"")
             .replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // HTTP helpers (v2.0.4: cookie jar + manual redirects)
    // ------------------------------------------------------------------

    /** Max redirect hops followed per request() call. */
    private static final int MAX_REDIRECTS = 5;

    /**
     * Minimal transport abstraction. The redirect/cookie logic in
     * {@link #requestWith} runs against this interface so headless tests
     * can script canned responses (the sandbox blocks even loopback
     * TCP, so no local test server is possible); production uses
     * {@link UrlConnectionTransport}.
     */
    public interface HttpTransport {
        RawResponse exchange(String url, String method, String body,
                List<String[]> reqHeaders) throws IOException;
    }

    /** One raw exchange: status + headers + body + final URL. */
    public static final class RawResponse {
        public int status;
        public java.util.Map<String, List<String>> headers;
        public String body;
        public String finalUrl;
    }

    /** Result of one request() call (after following redirects). */
    public static final class HttpResult {
        public int status;
        public String finalUrl;
        public String body;
        public int hops;
    }

    /** True for the redirect statuses we follow manually. */
    public static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    /**
     * Method to use on the next hop after a redirect. Mirrors browser /
     * HttpURLConnection semantics: 301/302/303 turn a POST into a GET
     * (the PRG pattern — no body is re-sent); 307/308 repeat the
     * original method with its body.
     */
    public static String redirectFollowMethod(int status, String origMethod) {
        boolean wasPost = "POST".equalsIgnoreCase(origMethod);
        if (wasPost && (status == 301 || status == 302 || status == 303)) {
            return "GET";
        }
        return origMethod;
    }

    /**
     * One HTTP request with manual redirect handling, via the production
     * transport. Redirects are NOT followed by HttpURLConnection
     * (setInstanceFollowRedirects(false)) because we must (a) re-attach
     * the cookie jar on every hop and (b) absorb any new Set-Cookie
     * headers each hop sends.
     *
     * @param bodyStr POST body, or null for GET
     * @param jar     session cookie jar (mutated: absorbs Set-Cookie)
     */
    private static HttpResult request(String url, String method,
            String bodyStr, CookieJar jar) throws IOException {
        return requestWith(new UrlConnectionTransport(), url, method,
                bodyStr, jar);
    }

    /**
     * Testable core of {@link #request}: same manual-redirect + cookie
     * logic against any {@link HttpTransport}.
     */
    public static HttpResult requestWith(HttpTransport t, String url,
            String method, String bodyStr, CookieJar jar) throws IOException {
        String currentUrl = url;
        String currentMethod = method;
        String currentBody = bodyStr;
        int hops = 0;

        while (true) {
            List<String[]> reqHeaders = new ArrayList<String[]>();
            reqHeaders.add(new String[]{"User-Agent", USER_AGENT});
            reqHeaders.add(new String[]{"Accept-Language",
                    ACCEPT_LANGUAGE});
            if (!jar.isEmpty()) {
                reqHeaders.add(new String[]{"Cookie", jar.headerValue()});
            }
            if ("POST".equalsIgnoreCase(currentMethod)
                    && currentBody != null) {
                reqHeaders.add(new String[]{"Content-Type",
                        "application/x-www-form-urlencoded"});
                reqHeaders.add(new String[]{"Referer", url});
                reqHeaders.add(new String[]{"Content-Length",
                        String.valueOf(currentBody.getBytes(
                                StandardCharsets.UTF_8).length)});
            }

            RawResponse resp =
                    t.exchange(currentUrl, currentMethod, currentBody,
                            reqHeaders);
            jar.absorb(setCookieHeaders(resp.headers));

            if (isRedirectStatus(resp.status)) {
                if (hops >= MAX_REDIRECTS) {
                    throw new FetchException(FetchKind.NETWORK_DOWN,
                            "too many redirects",
                            "redirect loop at " + currentUrl);
                }
                String location = firstHeader(resp.headers, "Location");
                if (location == null || location.isEmpty()) {
                    throw new FetchException(FetchKind.NETWORK_DOWN,
                            "redirect without Location",
                            "HTTP " + resp.status + " at " + currentUrl);
                }
                // Resolve relative Location against the current URL.
                currentUrl = new URL(new URL(currentUrl), location)
                        .toString();
                String nextMethod =
                        redirectFollowMethod(resp.status, currentMethod);
                if (!nextMethod.equalsIgnoreCase(currentMethod)) {
                    currentBody = null; // POST -> GET: drop the body
                }
                currentMethod = nextMethod;
                hops++;
                continue;
            }

            HttpResult r = new HttpResult();
            r.status = resp.status;
            r.finalUrl = resp.finalUrl;
            r.body = resp.body;
            r.hops = hops;
            return r;
        }
    }

    /** All Set-Cookie response header values (header name matched
     *  case-insensitively — servers vary the casing). */
    public static List<String> setCookieHeaders(
            java.util.Map<String, List<String>> fields) {
        List<String> out = new ArrayList<String>();
        if (fields == null) return out;
        for (java.util.Map.Entry<String, List<String>> e
                : fields.entrySet()) {
            if (e.getKey() != null
                    && e.getKey().equalsIgnoreCase("Set-Cookie")
                    && e.getValue() != null) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }

    /** First value of a response header (case-insensitive name), or null. */
    private static String firstHeader(
            java.util.Map<String, List<String>> fields, String name) {
        if (fields == null) return null;
        for (java.util.Map.Entry<String, List<String>> e
                : fields.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /** Production transport over HttpURLConnection. */
    private static final class UrlConnectionTransport
            implements HttpTransport {
        public RawResponse exchange(String url, String method, String body,
                List<String[]> reqHeaders) throws IOException {
            HttpURLConnection c = open(url, method);
            for (String[] h : reqHeaders) {
                c.setRequestProperty(h[0], h[1]);
            }
            if ("POST".equalsIgnoreCase(method) && body != null) {
                c.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
            RawResponse r = new RawResponse();
            r.status = c.getResponseCode();
            r.headers = c.getHeaderFields();
            r.finalUrl = c.getURL().toString();
            r.body = readBody(c, r.status);
            c.disconnect();
            return r;
        }
    }

    private static HttpURLConnection open(String url, String method)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        // Manual redirect handling in request(): the jar must be
        // re-attached on every hop, which auto-follow would skip.
        c.setInstanceFollowRedirects(false);
        return c;
    }

    /** Reads the response (or error) body of an open connection.
     *  A null stream (no body at all) yields "" — the v2.0.5
     *  {@link #checkHttpOk} step turns error statuses into honest
     *  HTTP_ERROR failures instead of this throwing first. */
    private static String readBody(HttpURLConnection c, int status)
            throws IOException {
        InputStream in =
                status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private static void addParam(StringBuilder body, String name, String value) {
        try {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(name, "UTF-8"));
            body.append('=');
            body.append(URLEncoder.encode(value == null ? "" : value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e); // UTF-8 always present
        }
    }
}
