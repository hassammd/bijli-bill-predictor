import com.hatch.bijlibill.LescoBillFetcher;
import com.hatch.bijlibill.LescoBillFetcher.BillInfo;
import com.hatch.bijlibill.LescoBillFetcher.FetchException;
import com.hatch.bijlibill.LescoBillFetcher.FetchKind;
import com.hatch.bijlibill.KwhAnchorPicker;
import com.hatch.bijlibill.FramePrep;
import com.hatch.bijlibill.MeterCheckCalc;
import com.hatch.bijlibill.BillMirrorCalc;
import com.hatch.bijlibill.Tariff;
import com.hatch.bijlibill.BillReminder;
import com.hatch.bijlibill.BillPdf;
import com.hatch.bijlibill.BillSummary;
import com.hatch.bijlibill.Disco;
import com.hatch.bijlibill.SavedMeters;
import com.hatch.bijlibill.LangPolicy;
import com.hatch.bijlibill.ArrearsStatus;
import com.hatch.bijlibill.DailyReminder;
import com.hatch.bijlibill.AccuracyTracker;
import com.hatch.bijlibill.GuardCalc;
import com.hatch.bijlibill.GuardReminder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * v2.0 standalone JVM test for LescoBillFetcher parsing (pure Java).
 * Fixtures are SYNTHETIC — invented values mirroring the documented PITC
 * page structure. No live HTTP is attempted (sandbox blocks raw egress).
 */
public class TestBillFetch {
    static int failures = 0;

    static void check(String name, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null
                : expected.equals(actual);
        if (!ok) {
            System.out.println("FAIL " + name + ": expected <" + expected
                    + "> got <" + actual + ">");
            failures++;
        } else {
            System.out.println("ok   " + name + " = " + actual);
        }
    }

    static String read(String name) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get("testdata/" + name));
        return new String(b, StandardCharsets.UTF_8);
    }

    // ---- v2.0.4 fake-transport harness (no TCP at all) ----
    // Records every exchange so tests can assert methods, cookies,
    // bodies and URLs per hop; responses come from a script queue.

    static final class FakeTransport
            implements LescoBillFetcher.HttpTransport {
        static final class Scripted {
            int status;
            java.util.Map<String, List<String>> headers;
            String body;
            String finalUrl;
        }
        final java.util.Queue<Scripted> script =
                new java.util.ArrayDeque<Scripted>();
        final java.util.List<String> seenMethods =
                new java.util.ArrayList<String>();
        final java.util.List<String> seenCookies =
                new java.util.ArrayList<String>();
        final java.util.List<String> seenBodies =
                new java.util.ArrayList<String>();
        final java.util.List<String> seenUrls =
                new java.util.ArrayList<String>();
        /** v2.0.5: raw request headers per exchange (UA/assert checks). */
        final java.util.List<List<String[]>> seenReqHeaders =
                new java.util.ArrayList<List<String[]>>();
        /** v2.0.5: number of exchanges (offline gate must do zero). */
        int exchanges = 0;
        /**
         * v2.0.5: when true, every https:// exchange throws a TLS
         * failure (wrapped, since the impl does not declare throws) so
         * the HTTPS->HTTP fallback path can be scripted; the http://
         * side still consumes the script queue.
         */
        boolean failHttpsWithTls = false;

        void add(int status, String body, String finalUrl,
                String... kvHeaders) {
            Scripted s = new Scripted();
            s.status = status;
            s.body = body;
            s.finalUrl = finalUrl;
            s.headers = fakeHeaders(kvHeaders);
            script.add(s);
        }

        public LescoBillFetcher.RawResponse exchange(String url,
                String method, String body, List<String[]> reqHeaders)
                throws java.io.IOException {
            seenUrls.add(url);
            seenMethods.add(method);
            seenBodies.add(body);
            String ck = null;
            for (String[] h : reqHeaders) {
                if ("Cookie".equalsIgnoreCase(h[0])) ck = h[1];
            }
            seenCookies.add(ck);
            seenReqHeaders.add(reqHeaders);
            exchanges++;
            if (failHttpsWithTls && url.startsWith("https://")) {
                throw new javax.net.ssl.SSLException("certificate boom");
            }
            Scripted s = script.poll();
            if (s == null) throw new RuntimeException(
                    "fake transport script exhausted at " + method
                    + " " + url);
            LescoBillFetcher.RawResponse r =
                    new LescoBillFetcher.RawResponse();
            r.status = s.status;
            r.headers = s.headers;
            r.body = s.body;
            r.finalUrl = s.finalUrl;
            return r;
        }
    }

    static java.util.Map<String, List<String>> fakeHeaders(
            String... kv) {
        java.util.Map<String, List<String>> m =
                new java.util.LinkedHashMap<String, List<String>>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            List<String> l = m.get(kv[i]);
            if (l == null) {
                l = new java.util.ArrayList<String>();
                m.put(kv[i], l);
            }
            l.add(kv[i + 1]);
        }
        return m;
    }

    static String fakeFormHtml(String rvt) {
        return "<html><head><title>LESCO Web Bill</title></head><body>"
                + "<form>"
                + "<input type=\"hidden\" name=\"__VIEWSTATE\" value=\"vs123\" />"
                + "<input type=\"hidden\" name=\"__VIEWSTATEGENERATOR\" value=\"vsg\" />"
                + "<input type=\"hidden\" name=\"__EVENTVALIDATION\" value=\"ev\" />"
                + (rvt == null ? "" : "<input type=\"hidden\""
                    + " name=\"__RequestVerificationToken\" value=\"" + rvt
                    + "\" />")
                + "</form></body></html>";
    }

    static String fakeBillHtml() {
        return "<html><head><title>LESCO Duplicate Bill</title></head>"
                + "<body><table>"
                + "<tr><td>Consumer Name</td><td>FAKE CUSTOMER</td></tr>"
                + "<tr><td>Amount Payable</td><td>1234.56</td></tr>"
                + "<tr><td>Due Date</td><td>22-SEP-26</td></tr>"
                + "</table></body></html>";
    }

    static final String FAKE_COOKIE =
            "__RequestVerificationToken=SRV123; Path=/; HttpOnly";

    static void runFakeTransportTests() throws Exception {
        String base = "https://bill.pitc.com.pk/lescobill";

        // A. Cookie jar shared between GET and POST in doFetch.
        FakeTransport t = new FakeTransport();
        t.add(200, fakeFormHtml("FORMTOKEN"), base,
                "Set-Cookie", FAKE_COOKIE,
                "Set-Cookie", "ASP.NET_SessionId=xyz; Path=/; HttpOnly");
        t.add(200, fakeBillHtml(), base);
        BillInfo b = LescoBillFetcher.doFetchWith(t, base,
                "refno", "02118240618604", "U", "02118240618604U");
        check("fake cookie roundtrip name", b.consumerName,
                "FAKE CUSTOMER");
        check("fake cookie roundtrip amount", b.amount, "1234.56");
        check("fake methods", t.seenMethods.toString(), "[GET, POST]");
        check("fake POST got cookie", t.seenCookies.get(1) != null
                && t.seenCookies.get(1).contains(
                        "__RequestVerificationToken=SRV123"), true);
        check("fake POST got session cookie", t.seenCookies.get(1) != null
                && t.seenCookies.get(1).contains("ASP.NET_SessionId=xyz"),
                true);
        check("fake GET sent no cookie", t.seenCookies.get(0), null);
        LescoBillFetcher.FetchDebug dbg = LescoBillFetcher.lastDebug();
        check("fake debug cookies names", dbg.cookiesSent != null
                && dbg.cookiesSent.contains(
                        "__RequestVerificationToken"), true);
        check("fake debug hops 0", dbg.redirectHops, 0);

        // B. 302: POST -> GET, cookies preserved across the hop.
        FakeTransport t2 = new FakeTransport();
        t2.add(200, fakeFormHtml("FORMTOKEN"), base,
                "Set-Cookie", FAKE_COOKIE);
        t2.add(302, "", base, "Location", "/landed");
        t2.add(200, fakeBillHtml(), base + "/landed");
        BillInfo b2 = LescoBillFetcher.doFetchWith(t2, base,
                "refno", "02118240618604", "U", "02118240618604U");
        check("fake 302 parsed", b2.amount, "1234.56");
        check("fake 302 methods", t2.seenMethods.toString(),
                "[GET, POST, GET]");
        check("fake 302 became GET with cookies",
                t2.seenCookies.get(2) != null
                && t2.seenCookies.get(2).contains(
                        "__RequestVerificationToken=SRV123"), true);
        check("fake 302 hops", LescoBillFetcher.lastDebug().redirectHops,
                1);

        // C. 307: POST repeated with body + cookies.
        FakeTransport t3 = new FakeTransport();
        t3.add(200, fakeFormHtml("FORMTOKEN"), base,
                "Set-Cookie", FAKE_COOKIE);
        t3.add(307, "", base, "Location", "/landed307");
        t3.add(200, fakeBillHtml(), base + "/landed307");
        BillInfo b3 = LescoBillFetcher.doFetchWith(t3, base,
                "refno", "02118240618604", "U", "02118240618604U");
        check("fake 307 parsed", b3.amount, "1234.56");
        check("fake 307 methods", t3.seenMethods.toString(),
                "[GET, POST, POST]");
        check("fake 307 body resent", t3.seenBodies.get(2) != null
                && t3.seenBodies.get(2).contains(
                        "searchTextBox=02118240618604")
                && t3.seenBodies.get(2).contains("__VIEWSTATE=vs123"),
                true);
        check("fake 307 cookies kept", t3.seenCookies.get(2) != null
                && t3.seenCookies.get(2).contains(
                        "__RequestVerificationToken=SRV123"), true);
        check("fake 307 hops", LescoBillFetcher.lastDebug().redirectHops,
                1);

        // D. Redirect loop stops after max hops: NETWORK_DOWN, no hang.
        FakeTransport t4 = new FakeTransport();
        for (int i = 0; i < 7; i++) {
            t4.add(302, "", base, "Location", "/loop");
        }
        FetchKind loopKind = null;
        try {
            LescoBillFetcher.doFetchWith(t4, base,
                    "refno", "02118240618604", "U", "02118240618604U");
            check("fake loop throws", false, true);
        } catch (FetchException e) {
            loopKind = e.kind;
        }
        check("fake loop kind NETWORK_DOWN", loopKind,
                FetchKind.NETWORK_DOWN);
        check("fake loop hop count", t4.seenUrls.size(), 6);

        // E. Anti-forgery plain text -> ANTIFORGERY_ERROR via doFetch.
        FakeTransport t5 = new FakeTransport();
        t5.add(200, fakeFormHtml("FORMTOKEN"), base);
        t5.add(200, "The required anti-forgery cookie"
                + " \"__RequestVerificationToken\" is not present.\n\n"
                + "<!DOCTYPE html><html><head><title>Error</title></head>"
                + "<body></body></html>", base);
        FetchKind afKind2 = null;
        try {
            LescoBillFetcher.doFetchWith(t5, base,
                    "refno", "02118240618604", "U", "02118240618604U");
            check("fake af throws", false, true);
        } catch (FetchException e) {
            afKind2 = e.kind;
        }
        check("fake af kind", afKind2, FetchKind.ANTIFORGERY_ERROR);

        // F. requestWith directly: GET with an empty jar sends no
        //    Cookie header; a hop that sets a cookie is re-attached.
        FakeTransport t6 = new FakeTransport();
        t6.add(302, "", base, "Location", "/s2",
                "Set-Cookie", "hop=1; Path=/");
        t6.add(200, "ok-body", base + "/s2");
        LescoBillFetcher.CookieJar j6 = new LescoBillFetcher.CookieJar();
        LescoBillFetcher.HttpResult r6 = LescoBillFetcher.requestWith(
                t6, base, "GET", null, j6);
        check("fake req hops", r6.hops, 1);
        check("fake req finalUrl", r6.finalUrl, base + "/s2");
        check("fake req body", r6.body, "ok-body");
        check("fake req first hop no cookie", t6.seenCookies.get(0), null);
        check("fake req second hop cookie", "hop=1",
                t6.seenCookies.get(1));
    }

    // ---------------------------------------------------------------
    // v2.0.5 user-guide tests
    // ---------------------------------------------------------------

    static void runGuideTests() throws Exception {
        String base = "https://bill.pitc.com.pk/lescobill";
        String httpBase = "http://bill.pitc.com.pk/lescobill";
        String digits = "06113530462901";
        String fullPage = read("lesco-bill-fullpage-SYNTHETIC.html");

        // 1. Guide-mandated request constants.
        check("ua guide value", LescoBillFetcher.USER_AGENT,
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        check("accept-language", LescoBillFetcher.ACCEPT_LANGUAGE,
                "en-US,en;q=0.9");
        check("connect timeout 15s", LescoBillFetcher.CONNECT_TIMEOUT_MS,
                15000);
        check("read timeout 20s", LescoBillFetcher.READ_TIMEOUT_MS, 20000);

        // 2. UA + Accept-Language actually sent on the wire.
        FakeTransport t = new FakeTransport();
        t.add(200, fakeFormHtml("T"), base, "Set-Cookie", FAKE_COOKIE);
        t.add(200, fakeBillHtml(), base);
        LescoBillFetcher.doFetchWith(t, base, "refno", "02118240618604",
                "U", "02118240618604U");
        String ua = null, al = null;
        for (List<String[]> hdrs : t.seenReqHeaders) {
            for (String[] h : hdrs) {
                if ("User-Agent".equalsIgnoreCase(h[0])) ua = h[1];
                if ("Accept-Language".equalsIgnoreCase(h[0])) al = h[1];
            }
        }
        check("ua sent on requests", ua, LescoBillFetcher.USER_AGENT);
        check("accept-language sent", al, "en-US,en;q=0.9");

        // 3. Offline pre-check short-circuits BEFORE any socket.
        FakeTransport toff = new FakeTransport();
        FetchKind offKind = null;
        boolean offThrown = false;
        try {
            LescoBillFetcher.FetchOrchestrator.fetchOrThrow(toff,
                    new LescoBillFetcher.FetchOrchestrator.OnlineChecker() {
                        @Override public boolean isOnline() { return false; }
                    }, "02118240618604U", "ref");
        } catch (FetchException e) {
            offThrown = true;
            offKind = e.kind;
        }
        check("offline throws", offThrown, true);
        check("offline kind OFFLINE", offKind, FetchKind.OFFLINE);
        check("offline opened no socket", toff.exchanges, 0);

        // 4. Online checker passing lets the fetch through.
        FakeTransport ton = new FakeTransport();
        ton.add(200, fakeFormHtml("T"), base, "Set-Cookie", FAKE_COOKIE);
        ton.add(200, fullPage, base + "/general?refno=" + digits);
        LescoBillFetcher.BillPage pon =
                LescoBillFetcher.FetchOrchestrator.fetchOrThrow(ton,
                    new LescoBillFetcher.FetchOrchestrator.OnlineChecker() {
                        @Override public boolean isOnline() { return true; }
                    }, digits + "U", "ref");
        check("online fetch parseOk", pon.parseOk, true);
        check("online fetch amount", pon.info.amount, "2,065");
        check("online fetch html kept", pon.html, fullPage);
        check("online fetch https used",
                LescoBillFetcher.lastDebug().httpUsed, "https");

        // 5. HTTPS dies with a TLS failure -> ONE http attempt.
        FakeTransport ttls = new FakeTransport();
        ttls.failHttpsWithTls = true;
        ttls.add(200, fakeFormHtml("T"), httpBase, "Set-Cookie",
                FAKE_COOKIE);
        ttls.add(200, fullPage, httpBase + "/general?refno=" + digits);
        LescoBillFetcher.BillPage ptls =
                LescoBillFetcher.fetchBillPageWith(ttls, digits + "U");
        check("tls fallback parseOk", ptls.parseOk, true);
        check("tls fallback amount", ptls.info.amount, "2,065");
        check("tls fallback http used",
                LescoBillFetcher.lastDebug().httpUsed, "http");
        check("tls fallback note mentions https",
                LescoBillFetcher.lastDebug().note != null
                && LescoBillFetcher.lastDebug().note.contains("https"),
                true);
        check("tls fallback urls", ttls.seenUrls.toString(),
                "[" + base + ", " + httpBase + ", "
                + httpBase + "/general?refno=" + digits + "]");
        check("tls fallback exchanges", ttls.exchanges, 3);

        // 6. Completed-exchange failures do NOT trigger http retry:
        //    direct legs miss, then the form-POST fallback's token
        //    harvest fails with TOKEN_ERROR (no tokens on the form
        //    page) — a server-side failure, not a TLS/connect one.
        FakeTransport ttok = new FakeTransport();
        ttok.add(200, fakeFormHtml("T"), base, "Set-Cookie", FAKE_COOKIE);
        ttok.add(200, "<html><body>no bill here</body></html>",
                base + "/general?refno=" + digits);
        ttok.add(200, "<html><body>no bill here</body></html>",
                base + "/general?refno=" + digits + "U");
        ttok.add(200, "<html><body>no tokens here</body></html>", base);
        FetchKind tokKind = null;
        try {
            LescoBillFetcher.fetchBillPageWith(ttok, digits + "U");
        } catch (FetchException e) {
            tokKind = e.kind;
        }
        check("token-error kind", tokKind, FetchKind.TOKEN_ERROR);
        boolean allHttps = true;
        for (String u : ttok.seenUrls) {
            if (!u.startsWith("https://")) allHttps = false;
        }
        check("token-error never tried http", allHttps, true);
        check("token-error exchanges", ttok.exchanges, 4);

        // 7. HTTP 403 -> HTTP_ERROR with the real status in the message.
        FakeTransport t403 = new FakeTransport();
        t403.add(200, fakeFormHtml("T"), base, "Set-Cookie", FAKE_COOKIE);
        t403.add(403, "forbidden", base + "/general?refno=" + digits);
        FetchKind hKind = null;
        String hMsg = null;
        try {
            LescoBillFetcher.fetchBillPageWith(t403, digits + "U");
        } catch (FetchException e) {
            hKind = e.kind;
            hMsg = e.getMessage();
        }
        check("http 403 kind HTTP_ERROR", hKind, FetchKind.HTTP_ERROR);
        check("http 403 message has 403",
                hMsg != null && hMsg.contains("403"), true);
        check("http 403 no http retry", t403.seenUrls.size(), 2);

        // 8. WebView renderer: exact base URL + null history URL.
        final String[] cap = new String[5];
        com.hatch.bijlibill.BillWebRenderer.render(
                new com.hatch.bijlibill.BillWebRenderer.WebViewLike() {
                    @Override public void loadDataWithBaseURL(String b,
                            String d, String m, String e, String h) {
                        cap[0] = b; cap[1] = d; cap[2] = m; cap[3] = e;
                        cap[4] = h;
                    }
                }, "<html>X</html>");
        check("webview base url", cap[0], "https://bill.pitc.com.pk/");
        check("webview data", cap[1], "<html>X</html>");
        check("webview mime", cap[2], "text/html");
        check("webview encoding", cap[3], "UTF-8");
        check("webview history null", cap[4], null);

        // 9. Print wiring: job name + null guards.
        final String[] job = new String[1];
        com.hatch.bijlibill.BillPrinter.print(
                new com.hatch.bijlibill.BillPrinter.PrintService() {
                    @Override public void print(String jobName,
                            Object adapter) {
                        job[0] = jobName;
                    }
                }, new Object());
        check("print job name", job[0], "Bijli Bill");
        boolean printGuard = false;
        try {
            com.hatch.bijlibill.BillPrinter.print(
                    new com.hatch.bijlibill.BillPrinter.PrintService() {
                        @Override public void print(String jobName,
                                Object adapter) { }
                    }, null);
        } catch (IllegalArgumentException e) {
            printGuard = true;
        }
        check("print null adapter rejected", printGuard, true);

        // 10. Browser fallback URL (suffix stripped, cid -> landing).
        check("browser url ref",
                LescoBillFetcher.browserFallbackUrl("02118240618604U",
                        "ref"),
                base + "/general?refno=02118240618604");
        check("browser url cid",
                LescoBillFetcher.browserFallbackUrl("7654321", "cid"),
                base);

        // 11. Debug report header fields.
        LescoBillFetcher.FetchDebug dd =
                new LescoBillFetcher.FetchDebug();
        dd.timestamp = "2026-09-20 00:00:00";
        dd.typedRef = "02118240618604U";
        dd.mode = "ref";
        dd.fetchPath = "direct";
        dd.httpUsed = "http";
        dd.note = "https failed: SSLException";
        dd.httpStatus = 200;
        dd.finalUrl = httpBase + "/general?refno=02118240618604";
        dd.responseBytes = 10;
        dd.title = "T";
        dd.divUaPresent = false;
        dd.cookiesSent = "[__RequestVerificationToken]";
        dd.redirectHops = 1;
        String hdr = LescoBillFetcher.debugReportHeader(dd, "2.0.5");
        check("report version", hdr.contains("v2.0.5"), true);
        check("report fetch path", hdr.contains("fetch path: direct"),
                true);
        check("report http/https",
                hdr.contains("http/https used: http"), true);
        check("report note", hdr.contains("https failed: SSLException"),
                true);
        check("report cookies", hdr.contains(
                "cookies sent: [__RequestVerificationToken]"), true);
        check("report hops", hdr.contains("redirect hops: 1"), true);

        // 12. pageFromHtml: cache-hit re-parse.
        LescoBillFetcher.BillPage pg =
                LescoBillFetcher.pageFromHtml(fullPage, digits, "direct");
        check("pageFromHtml parseOk", pg.parseOk, true);
        check("pageFromHtml amount", pg.info.amount, "2,065");
        check("pageFromHtml html kept", pg.html, fullPage);
        check("pageFromHtml path", pg.fetchPath, "direct");
        LescoBillFetcher.BillPage pg2 = LescoBillFetcher.pageFromHtml(
                "<html><body><table><tr><td>Foo</td><td>Bar</td></tr>"
                + "</table></body></html>", digits, "form-post");
        check("pageFromHtml unparseable", pg2.parseOk, false);
        check("pageFromHtml sparse ref", pg2.info.referenceNumber, digits);

        // 13. Orchestrator customer-ID mode via form-POST.
        FakeTransport tcid = new FakeTransport();
        tcid.add(200, fakeFormHtml("T"), base, "Set-Cookie", FAKE_COOKIE);
        tcid.add(200, fakeBillHtml(), base);
        LescoBillFetcher.BillPage pcid =
                LescoBillFetcher.FetchOrchestrator.fetchOrThrow(tcid,
                    new LescoBillFetcher.FetchOrchestrator.OnlineChecker() {
                        @Override public boolean isOnline() { return true; }
                    }, "7654321", "cid");
        check("orchestrator cid parseOk", pcid.parseOk, true);
        check("orchestrator cid amount", pcid.info.amount, "1234.56");
        check("orchestrator cid html", pcid.html.contains("FAKE CUSTOMER"),
                true);

        // 14. TLS failure classification.
        check("tls classified",
                LescoBillFetcher.isTlsFailure(
                        new javax.net.ssl.SSLException("x")), true);
        check("tls not plain io",
                LescoBillFetcher.isTlsFailure(
                        new java.io.IOException("x")), false);
        check("fallback on tls",
                LescoBillFetcher.shouldFallbackToHttp(
                        new javax.net.ssl.SSLException("x")), true);
        check("fallback on timeout",
                LescoBillFetcher.shouldFallbackToHttp(
                        new java.net.SocketTimeoutException("x")), true);
        check("no fallback on http-error",
                LescoBillFetcher.shouldFallbackToHttp(
                        new FetchException(FetchKind.HTTP_ERROR,
                                "HTTP 403", "d")), false);
    }

    public static void main(String[] a) throws Exception {
        // ---- reference validation (ONE field: 14 digits + optional
        //      trailing U/R, exactly as printed on the bill) ----
        check("valid 14-digit", LescoBillFetcher.isValidReference("06113530462901"), true);
        check("valid 14+U", LescoBillFetcher.isValidReference("06113530462901U"), true);
        check("valid 14+r lowercase", LescoBillFetcher.isValidReference("06113530462901r"), true);
        check("reject 13 digits", LescoBillFetcher.isValidReference("0611353046290"), false);
        check("reject 15 digits", LescoBillFetcher.isValidReference("061135304629011"), false);
        check("reject bad letter", LescoBillFetcher.isValidReference("06113530462901X"), false);
        check("reject letters-only", LescoBillFetcher.isValidReference("ABCDEFGHIJKLMN"), false);
        check("reject empty", LescoBillFetcher.isValidReference(""), false);
        check("reject null", LescoBillFetcher.isValidReference(null), false);
        check("reject spaces", LescoBillFetcher.isValidReference("0611 3530462901"), false);
        check("trim ok", LescoBillFetcher.isValidReference("  06113530462901  "), true);

        // ---- splitReference: digits -> textbox field, letter -> U/R field ----
        String[] s1 = LescoBillFetcher.splitReference("02118240618604U");
        check("split U digits", s1[0], "02118240618604");
        check("split U flag", s1[1], "U");
        String[] s2 = LescoBillFetcher.splitReference("02118240618604R");
        check("split R digits", s2[0], "02118240618604");
        check("split R flag", s2[1], "R");
        String[] s3 = LescoBillFetcher.splitReference("02118240618604");
        check("split bare digits", s3[0], "02118240618604");
        check("split bare defaults U", s3[1], "U");
        String[] s4 = LescoBillFetcher.splitReference("02118240618604r");
        check("split lowercase r flag", s4[1], "R");
        boolean splitBad = false;
        try {
            LescoBillFetcher.splitReference("0211824061860"); // 13 digits
        } catch (IllegalArgumentException e) {
            splitBad = true;
        }
        check("split rejects 13 digits", splitBad, true);

        // ---- customer-id validation (exactly 7 digits) ----
        check("valid 7-digit cid", LescoBillFetcher.isValidCustomerId("7654321"), true);
        check("reject 6-digit cid", LescoBillFetcher.isValidCustomerId("765432"), false);
        check("reject 8-digit cid", LescoBillFetcher.isValidCustomerId("76543210"), false);
        check("reject cid letters", LescoBillFetcher.isValidCustomerId("765432A"), false);
        check("reject cid null", LescoBillFetcher.isValidCustomerId(null), false);
        check("cid trim ok", LescoBillFetcher.isValidCustomerId(" 7654321 "), true);

        // ---- error taxonomy (synthetic exceptions, no network) ----
        check("network unknown-host", LescoBillFetcher.isNetworkDown(
                new java.net.UnknownHostException("x")), true);
        check("network timeout", LescoBillFetcher.isNetworkDown(
                new java.net.SocketTimeoutException("x")), true);
        check("network connect", LescoBillFetcher.isNetworkDown(
                new java.net.ConnectException("x")), true);
        check("network no-route", LescoBillFetcher.isNetworkDown(
                new java.net.NoRouteToHostException("x")), true);
        check("not-network token-fail", LescoBillFetcher.isNetworkDown(
                new java.io.IOException("Failed to extract required tokens")), false);
        check("not-network server-error", LescoBillFetcher.isNetworkDown(
                new LescoBillFetcher.BillErrorException(
                        LescoBillFetcher.FetchKind.INVALID_REF, "bad")), false);
        check("notfound msg", LescoBillFetcher.looksLikeNotFound(
                "Bill not found for this reference"), true);
        check("notfound no-record", LescoBillFetcher.looksLikeNotFound(
                "No record found"), true);
        check("invalid-ref msg", LescoBillFetcher.looksLikeNotFound(
                "Some unexpected portal text"), false);
        check("notfound does-not-belong", LescoBillFetcher.looksLikeNotFound(
                "The given input does not belongs to LESCO"), true);
        check("notfound record-not-found", LescoBillFetcher.looksLikeNotFound(
                "Record Not Found"), true);
        check("notfound null", LescoBillFetcher.looksLikeNotFound(null), false);

        // ---- full bill fixture (new v2.0 fields + history) ----
        String html = read("lesco-bill-full-SYNTHETIC.html");
        BillInfo b = LescoBillFetcher.parseBillHtml(html, "06113530462901");
        check("ref", b.referenceNumber, "06113530462901");
        check("consumerName", b.consumerName, "SAMPLE CUSTOMER (SYNTHETIC)");
        check("address", b.address, "12 SAMPLE STREET, LAHORE");
        check("customerId", b.customerId, "7654321");
        check("category", b.category, "RESIDENTIAL");
        check("tariff", b.tariff, "A-1 RESIDENTIAL");
        check("meterNo", b.meterNo, "MTR-998877");
        check("issueDate", b.issueDate, "SEP-26");
        check("dueDate", b.dueDate, "26-SEP-26");
        check("readingDate", b.readingDate, "10-SEP-26");
        check("prevReading", b.prevReading, "12340");
        check("currReading", b.currReading, "12590");
        check("units", b.units, "250");
        check("unitsAsInt", b.unitsAsInt(), 250);
        check("electricityCharges", b.electricityCharges, "6790.00");
        check("subsidy", b.subsidy, "0.00");
        check("netCharges", b.netCharges, "6790.00");
        check("gst", b.gst, "1294.20");
        check("taxes", b.taxes, "400.00");
        check("fpa", b.fpa, "625.00");
        check("arrears", b.arrears, "0.00");
        check("currentBill", b.currentBill, "9109.20");
        check("grandTotal", b.grandTotal, "9109.20");
        check("amount", b.amount, "9109.20");
        check("amountAsDouble", b.amountAsDouble(), 9109.20);
        check("lpSurcharge", b.lpSurcharge, "911.00");
        check("amountAfterDueDate", b.amountAfterDueDate, "10020.20");

        // ---- history table detection ----
        check("history tables", b.history.size(), 1);
        List<String[]> h = b.history.get(0);
        check("history rows", h.size(), 3);
        check("history header0", h.get(0)[0], "Month");
        check("history r1 month", h.get(1)[0], "AUG-26");
        check("history r1 amount", h.get(1)[2], "8412.55");
        check("history r2 units", h.get(2)[1], "268");

        // ---- old basic fixture still parses (backwards compat) ----
        String basic = read("lesco-bill-sample-SYNTHETIC.html");
        BillInfo b2 = LescoBillFetcher.parseBillHtml(basic, "06113530462901");
        check("basic units", b2.units, "250");
        check("basic amount", b2.amount, "8484.20");
        check("basic no history", b2.history.size(), 0);
        check("basic new fields null", b2.fpa, null);
        check("basic category null", b2.category, null);

        // ---- error fixture throws (v2.0.2: "does not belong" -> NOT_FOUND) ----
        String errHtml = read("lesco-bill-error-SYNTHETIC.html");
        boolean threw = false;
        FetchKind errKind = null;
        try {
            LescoBillFetcher.parseBillHtml(errHtml, "00000000000000");
        } catch (LescoBillFetcher.BillErrorException e) {
            threw = true;
            errKind = e.kind;
            check("error msg", e.getMessage(),
                    "The given input does not belongs to LESCO");
        }
        check("error div throws", threw, true);
        check("error div kind NOT_FOUND", errKind, FetchKind.NOT_FOUND);

        // ---- "Record Not Found" error box -> NOT_FOUND ----
        String nfHtml = read("lesco-bill-recordnotfound-SYNTHETIC.html");
        boolean nfThrew = false;
        FetchKind nfKind = null;
        try {
            LescoBillFetcher.parseBillHtml(nfHtml, "00000000000000");
        } catch (LescoBillFetcher.BillErrorException e) {
            nfThrew = true;
            nfKind = e.kind;
            check("record-not-found msg", e.getMessage(), "Record Not Found");
        }
        check("record-not-found throws", nfThrew, true);
        check("record-not-found kind", nfKind, FetchKind.NOT_FOUND);

        // ---- error phrase outside div#ua (broadened scan) -> NOT_FOUND ----
        String scanHtml = read("lesco-bill-scanerror-SYNTHETIC.html");
        boolean scanThrew = false;
        FetchKind scanKind = null;
        String scanMsg = null;
        try {
            LescoBillFetcher.parseBillHtml(scanHtml, "00000000000000");
        } catch (LescoBillFetcher.BillErrorException e) {
            scanThrew = true;
            scanKind = e.kind;
            scanMsg = e.getMessage();
        }
        check("scan error throws", scanThrew, true);
        check("scan error kind", scanKind, FetchKind.NOT_FOUND);
        check("scan error snippet", scanMsg != null
                && scanMsg.contains("record not found"), true);

        // ---- generic multi-table fixture (v2.0.2 core fix) ----
        String gen = read("lesco-bill-generic-SYNTHETIC.html");
        BillInfo g = LescoBillFetcher.parseBillHtml(gen, "06113530462901U");
        check("generic ref", g.referenceNumber, "06113530462901U");
        check("generic name", g.consumerName, "SAMPLE CUSTOMER (SYNTHETIC)");
        check("generic address", g.address, "12 SAMPLE STREET, LAHORE");
        check("generic customerId", g.customerId, "7654321");
        check("generic tariff", g.tariff, "A-1 RESIDENTIAL");
        check("generic issueDate", g.issueDate, "SEP-26");
        check("generic currReading", g.currReading, "12590");
        check("generic prevReading", g.prevReading, "12340");
        check("generic units", g.units, "250");
        check("generic readingDate", g.readingDate, "10-SEP-26");
        check("generic amount", g.amount, "9109.20");
        check("generic amountAfterDueDate", g.amountAfterDueDate, "10020.20");
        check("generic dueDate", g.dueDate, "26-SEP-26");
        check("generic electricityCharges", g.electricityCharges, "6790.00");
        check("generic gst", g.gst, "1294.20");

        // ---- empty tables -> PARSE_EMPTY (never a blank bill) ----
        String emptyHtml = read("lesco-bill-empty-SYNTHETIC.html");
        boolean peThrew = false;
        FetchKind peKind = null;
        String peDebug = null;
        try {
            LescoBillFetcher.parseBillHtml(emptyHtml, "06113530462901",
                    "HTTP 200, 1234 bytes");
        } catch (FetchException e) {
            peThrew = true;
            peKind = e.kind;
            peDebug = e.debug;
        }
        check("parse-empty throws", peThrew, true);
        check("parse-empty kind", peKind, FetchKind.PARSE_EMPTY);
        check("parse-empty debug", peDebug != null
                && peDebug.contains("HTTP 200")
                && peDebug.contains("div#ua=n"), true);

        // ---- amount fallback: id/class containing "amount" ----
        String amtHtml = read("lesco-bill-amountdiv-SYNTHETIC.html");
        BillInfo ga = LescoBillFetcher.parseBillHtml(amtHtml, "06113530462901");
        check("amount fallback", ga.amount, "Rs 2,065");

        // ---- ruCode form value: U -> "" (empty), R -> "R" ----
        check("ruCode U empty", LescoBillFetcher.ruCodeFormValue("U"), "");
        check("ruCode R", LescoBillFetcher.ruCodeFormValue("R"), "R");
        check("ruCode null empty", LescoBillFetcher.ruCodeFormValue(null), "");

        // ---- token extraction: good page returns all tokens ----
        String goodForm = "<input type=\"hidden\" name=\"__VIEWSTATE\" value=\"vs\" />"
                + "<input type=\"hidden\" name=\"__VIEWSTATEGENERATOR\" value=\"vsg\" />"
                + "<input type=\"hidden\" name=\"__EVENTVALIDATION\" value=\"ev\" />";
        String[] toks = LescoBillFetcher.requireTokens(goodForm);
        check("tokens vs", toks[0], "vs");
        check("tokens vsg", toks[1], "vsg");
        check("tokens ev", toks[2], "ev");
        check("tokens rvt null", toks[3], null);
        String[] toks2 = LescoBillFetcher.requireTokens(goodForm
                + "<input type=\"hidden\" name=\"__RequestVerificationToken\""
                + " value=\"rt\" />");
        check("tokens rvt", toks2[3], "rt");

        // ---- token extraction: missing token -> TOKEN_ERROR ----
        boolean tokThrew = false;
        FetchKind tokKind = null;
        String tokDebug = null;
        try {
            LescoBillFetcher.requireTokens(
                    "<input type=\"hidden\" name=\"__EVENTVALIDATION\" value=\"ev\" />");
        } catch (FetchException e) {
            tokThrew = true;
            tokKind = e.kind;
            tokDebug = e.debug;
        }
        check("token-missing throws", tokThrew, true);
        check("token-missing kind", tokKind, FetchKind.TOKEN_ERROR);
        check("token-missing debug", tokDebug != null
                && tokDebug.contains("__VIEWSTATE"), true);

        // ---- token extraction: empty value counts as missing ----
        boolean tokEmpty = false;
        try {
            LescoBillFetcher.requireTokens(goodForm.replace(
                    "name=\"__VIEWSTATE\" value=\"vs\"",
                    "name=\"__VIEWSTATE\" value=\"\""));
        } catch (FetchException e) {
            tokEmpty = e.kind == FetchKind.TOKEN_ERROR;
        }
        check("token-empty-value throws TOKEN_ERROR", tokEmpty, true);

        // ---- TOKEN_ERROR is not a network-down failure ----
        check("not-network token-error", LescoBillFetcher.isNetworkDown(
                new FetchException(FetchKind.TOKEN_ERROR, "x", "y")), false);

        // ---- fetch methods reject bad input without network ----
        boolean iae = false;
        try {
            LescoBillFetcher.fetchBill("ABC");
        } catch (IllegalArgumentException e) {
            iae = true;
        }
        check("fetchBill rejects bad ref", iae, true);

        boolean iaeCid = false;
        try {
            LescoBillFetcher.fetchByCustomerId("12");
        } catch (IllegalArgumentException e) {
            iaeCid = true;
        }
        check("fetchByCustomerId rejects bad id", iaeCid, true);

        // ---- v2.0.3: __RequestVerificationToken "undefined" quirk ----
        // When the GET page lacks the token, the working reference
        // (URLSearchParams with an undefined JS value) sends the literal
        // string "undefined" — mirror it exactly.
        String[] noRvt = {"vs", "vsg", "ev", null};
        String bodyNoRvt = LescoBillFetcher.buildPostBody(noRvt, "refno",
                "02118240618604", "U");
        check("rvt-absent sends undefined",
                bodyNoRvt.contains("__RequestVerificationToken=undefined"), true);
        check("rvt-absent full body", bodyNoRvt,
                "__VIEWSTATE=vs&__VIEWSTATEGENERATOR=vsg&__EVENTVALIDATION=ev"
                + "&__RequestVerificationToken=undefined"
                + "&rbSearchByList=refno&searchTextBox=02118240618604"
                + "&ruCodeTextBox=&btnSearch=Search");

        String[] withRvt = {"vs", "vsg", "ev", "tok+en/1="};
        String bodyRvt = LescoBillFetcher.buildPostBody(withRvt, "refno",
                "02118240618604", "R");
        check("rvt-present real value encoded",
                bodyRvt.contains("__RequestVerificationToken=tok%2Ben%2F1%3D"),
                true);
        check("rvt-present R flag",
                bodyRvt.contains("&ruCodeTextBox=R&"), true);

        // ---- v2.0.3: byte-exact form encoding audit ----
        // UTF-8, encoded exactly once: spaces -> '+', '+' -> %2B,
        // '/' -> %2F, '=' -> %3D. No double-encoding.
        String[] tricky = {"a+b/c=d e", "x y+z", "p=q/r s", null};
        String trickyBody = LescoBillFetcher.buildPostBody(tricky, "refno",
                "02118240618604", "U");
        check("encoding byte-exact", trickyBody,
                "__VIEWSTATE=a%2Bb%2Fc%3Dd+e"
                + "&__VIEWSTATEGENERATOR=x+y%2Bz"
                + "&__EVENTVALIDATION=p%3Dq%2Fr+s"
                + "&__RequestVerificationToken=undefined"
                + "&rbSearchByList=refno&searchTextBox=02118240618604"
                + "&ruCodeTextBox=&btnSearch=Search");
        check("no double-encoding",
                trickyBody.contains("%25"), false);

        // ---- v2.0.3: POST field summary (debug report) ----
        String summary = LescoBillFetcher.postFieldSummary(
                new String[]{"abcdefghijklmnopqrstuvwxyz0123456789",
                        "vsg", "ev", null},
                "refno", "02118240618604", "U");
        check("summary vs len", summary.contains("__VIEWSTATE[len=36"), true);
        check("summary vs first20",
                summary.contains("first=\"abcdefghijklmnopqrst\""), true);
        check("summary vs last20",
                summary.contains("last=\"qrstuvwxyz0123456789\""), true);
        check("summary rvt undefined",
                summary.contains("__RequestVerificationToken[len=9"), true);
        check("summary searchTextBox",
                summary.contains("searchTextBox[len=14"), true);

        // ---- v2.0.3: page title extraction ----
        check("title basic", LescoBillFetcher.pageTitle(
                "<html><head><title>LESCO Web Bill</title></head>"
                + "<body>x</body></html>"), "LESCO Web Bill");
        check("title missing", LescoBillFetcher.pageTitle(
                "<html><body>no title here</body></html>"), "");
        check("title null", LescoBillFetcher.pageTitle(null), "");
        check("title trims entities", LescoBillFetcher.pageTitle(
                "<TITLE>  Bill&nbsp;Details  </TITLE>"), "Bill Details");

        // ---- v2.0.3: PARSE_EMPTY debug line now includes the title ----
        boolean pe2 = false;
        String pe2Debug = null;
        try {
            LescoBillFetcher.parseBillHtml(
                    "<html><head><title>LESCO Duplicate Bill</title></head>"
                    + "<body><table><tr><td>x</td></tr></table></body></html>",
                    "06113530462901", "HTTP 200, 11406 bytes");
        } catch (FetchException e) {
            pe2 = e.kind == FetchKind.PARSE_EMPTY;
            pe2Debug = e.debug;
        }
        check("parse-empty still throws", pe2, true);
        check("parse-empty debug has title", pe2Debug != null
                && pe2Debug.contains("title=\"LESCO Duplicate Bill\""), true);
        check("parse-empty debug keeps meta", pe2Debug != null
                && pe2Debug.contains("HTTP 200")
                && pe2Debug.contains("div#ua=n"), true);

        // ---- v2.0.4: CookieJar unit tests ----
        LescoBillFetcher.CookieJar jar = new LescoBillFetcher.CookieJar();
        check("jar empty", jar.isEmpty(), true);
        check("jar header empty", jar.headerValue(), "");
        java.util.List<String> sc = new java.util.ArrayList<String>();
        sc.add("__RequestVerificationToken=SRV123; Path=/; HttpOnly");
        sc.add("ASP.NET_SessionId=abc; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure; HttpOnly");
        sc.add("empty=; Path=/");
        sc.add("eqinval=x=y; Path=/");
        sc.add("noequals; Path=/");
        sc.add("quoted=\"hello world\"; Path=/");
        jar.absorb(sc);
        check("jar size skips flag-only", jar.size(), 5);
        check("jar header join", jar.headerValue(),
                "__RequestVerificationToken=SRV123; ASP.NET_SessionId=abc"
                + "; empty=; eqinval=x=y; quoted=hello world");
        check("jar names", jar.names().toString(),
                "[__RequestVerificationToken, ASP.NET_SessionId, empty,"
                + " eqinval, quoted]");
        check("jar not empty", jar.isEmpty(), false);
        java.util.List<String> sc2 = new java.util.ArrayList<String>();
        sc2.add("__RequestVerificationToken=NEW999");
        sc2.add("extra=1");
        jar.absorb(sc2);
        check("jar later overwrites", jar.headerValue().contains(
                "__RequestVerificationToken=NEW999"), true);
        check("jar size after overwrite", jar.size(), 6);
        jar.absorb(null);
        check("jar absorb null safe", jar.size(), 6);

        // ---- v2.0.4: redirect logic unit tests ----
        check("redirect 301", LescoBillFetcher.isRedirectStatus(301), true);
        check("redirect 302", LescoBillFetcher.isRedirectStatus(302), true);
        check("redirect 303", LescoBillFetcher.isRedirectStatus(303), true);
        check("redirect 307", LescoBillFetcher.isRedirectStatus(307), true);
        check("redirect 308", LescoBillFetcher.isRedirectStatus(308), true);
        check("not-redirect 200", LescoBillFetcher.isRedirectStatus(200), false);
        check("not-redirect 404", LescoBillFetcher.isRedirectStatus(404), false);
        check("not-redirect 500", LescoBillFetcher.isRedirectStatus(500), false);
        check("302 POST->GET", LescoBillFetcher.redirectFollowMethod(302, "POST"), "GET");
        check("301 POST->GET", LescoBillFetcher.redirectFollowMethod(301, "POST"), "GET");
        check("303 POST->GET", LescoBillFetcher.redirectFollowMethod(303, "POST"), "GET");
        check("307 POST stays", LescoBillFetcher.redirectFollowMethod(307, "POST"), "POST");
        check("308 POST stays", LescoBillFetcher.redirectFollowMethod(308, "POST"), "POST");
        check("302 GET stays", LescoBillFetcher.redirectFollowMethod(302, "GET"), "GET");

        // ---- v2.0.4: anti-forgery detection ----
        check("af null", LescoBillFetcher.looksLikeAntiForgery(null), false);
        check("af plain page", LescoBillFetcher.looksLikeAntiForgery(
                "<html><body>bill</body></html>"), false);
        check("af real message", LescoBillFetcher.looksLikeAntiForgery(
                "The required anti-forgery cookie \"__RequestVerificationToken\""
                + " is not present.\n\n<!DOCTYPE html>"), true);
        check("af case-insensitive", LescoBillFetcher.looksLikeAntiForgery(
                "ANTI-FORGERY validation failed"), true);
        check("af token+not-present", LescoBillFetcher.looksLikeAntiForgery(
                "__RequestVerificationToken cookie not present"), true);
        FetchKind afKind = null;
        try {
            LescoBillFetcher.parseBillHtml(
                    "The required anti-forgery cookie"
                    + " \"__RequestVerificationToken\" is not present.\n\n"
                    + "<!DOCTYPE html><html><head><title>LESCO Web Bill"
                    + "</title></head><body><div id=\"ua\"></div><table>"
                    + "<tr><td>Consumer Name</td><td>X</td></tr></table>"
                    + "</body></html>",
                    "06113530462901", "HTTP 200, 11406 bytes");
            check("af parse throws", false, true);
        } catch (FetchException e) {
            afKind = e.kind;
        }
        check("af parse kind ANTIFORGERY_ERROR", afKind,
                FetchKind.ANTIFORGERY_ERROR);

        // ---- v2.0.4: full GET->POST round-trips via a scripted fake
        //      transport (the sandbox blocks even loopback TCP, so no
        //      local HTTP server is possible) ----
        runFakeTransportTests();

        // ---- v2.0.5: direct /general fetch chain, generic full-bill
        //      parser ----
        runV205Tests();

        // ---- v2.0.6: new-design bill detector + parser (real
        //      2026-09-20 capture), scripted form-post -> 302 ->
        //      /general round trip ----
        runV206Tests();

        // ---- v2.0.5: user-guide tests — UA/Accept-Language/timeouts,
        //      offline gate, HTTPS->HTTP fallback, HTTP-error honesty,
        //      WebView exact load args, print wiring, browser fallback,
        //      debug report header, cache re-parse, orchestrator ----
        runGuideTests();

        // ---- v2.1.0: Meter Check — pure calc, OCR candidate picker,
        //      slab table, tax-ratio andaza accuracy, projection,
        //      slab alerts, EN/UR string parity ----
        runV210Tests();

        // ---- v2.2.0: Meter Check hardening — kWh-anchored picker,
        //      decimal truncation, adaptive frame math, gallery+camera
        //      shared pipeline (structural), new-string EN/UR parity ----
        runV220Tests();

        // ---- v2.2.1: Meter Check bugfixes — base = bill's PRESENT
        //      reading; STRONG OCR (sharpness-ranked frames,
        //      multi-variant passes, bounded job plan), failure UX ----
        runV221Tests();

        // ---- v2.3.0: auto frame pick (no manual selection), Kyun
        //      ANDAZA explainer, no REPORT buttons, PDF download,
        //      details card, due-date reminder, history chart ----
        runV230Tests();

        // ---- v2.4.0: slim summary card (details card + history chart
        //      REMOVED), bigger + zoomable bill WebView, DECIMAL manual
        //      readings (double math), "Andaza bill kaise bana?"
        //      breakdown from last month's real tax fields ----
        runV240Tests();

        // ---- v2.5.0: multi-disco fetch, Mere Meter auto-save, default
        //      English, UNIT CHECK rename, splash/UI polish ----
        runV250Tests();

        // ---- v3.1.0: bill-mirror near-accurate estimate, units-first
        //      hero, slimmer estimate screen ----
        runV310Tests();

        // ---- v3.1.1: Unit Check crash fix + manual reading entry ----
        runV311Tests();

        // ---- v3.1.2: energy-basis calibration + reminder UX ----
        runV312Tests();

        // ---- v3.1.3: net energy basis (Protected subsidy) +
        //      Protected/Non-Protected classification fix ----
        runV313Tests();

        // ---- v3.2.0: dual calibration + 200-Unit Guard + What-if ----
        runV320Tests();

        // ---- v3.3.0: accuracy tracker + always-on reminders ----
        runV330Tests();

        // ---- v3.4.0: streak + bill-day nudge + weekly report ----
        runV340Tests();

        // ---- v3.6.0: zero-input Guard + Sunday 10am reminder +
        //      weekly streak ----
        runV360Tests();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " FAILURES");
            System.exit(1);
        }
    }

    // ---- v2.0.5 tests ----

    static void runV205Tests() throws Exception {
        String digits = "06113530462901";
        String base = "https://bill.pitc.com.pk/lescobill";

        // ---- URL builder: suffix / no-suffix variants ----
        check("general url no suffix",
                LescoBillFetcher.generalBillUrl(digits),
                base + "/general?refno=" + digits);
        check("general url U suffix",
                LescoBillFetcher.generalBillUrl(digits, "U"),
                base + "/general?refno=" + digits + "U");
        check("general url R suffix",
                LescoBillFetcher.generalBillUrl(digits, "R"),
                base + "/general?refno=" + digits + "R");
        check("general url null suffix",
                LescoBillFetcher.generalBillUrl(digits, null),
                base + "/general?refno=" + digits);

        // ---- directBillHit: success detection ----
        String fullPage = read("lesco-bill-fullpage-SYNTHETIC.html");
        check("hit positive full page",
                LescoBillFetcher.directBillHit(fullPage, digits), true);
        check("hit missing digits",
                LescoBillFetcher.directBillHit(fullPage, "00000000000000"),
                false);
        check("hit digits + only units",
                LescoBillFetcher.directBillHit(
                        "<html><body>ref " + digits
                        + " units 113, nothing else</body></html>", digits),
                false);
        check("hit digits + payable + due date",
                LescoBillFetcher.directBillHit(
                        "<html><body>ref " + digits
                        + " PAYABLE WITHIN DUE DATE 2065, DUE DATE 22 SEP 26"
                        + "</body></html>", digits),
                true);
        check("hit null html",
                LescoBillFetcher.directBillHit(null, digits), false);
        check("hit null digits",
                LescoBillFetcher.directBillHit(fullPage, null), false);

        // ---- generic full-bill parser: EVERY bill field must extract ----
        BillInfo f = LescoBillFetcher.parseFullBillHtml(fullPage, digits);
        check("fullpage ref", f.referenceNumber, digits);
        check("fullpage name", f.consumerName,
                "SAMPLE CUSTOMER (SYNTHETIC), 12 SAMPLE STREET, LAHORE");
        check("fullpage address (merged into name)", f.address, null);
        check("fullpage customerId", f.customerId, "7654321");
        check("fullpage feeder (line scan)", f.feeder,
                "(012345) Samplefeeder");
        check("fullpage subDivision", f.subDivision, "Sample Abad");
        check("fullpage category", f.category, "Protected");
        check("fullpage tariffCategory (line scan)", f.tariffCategory,
                "Domestic");
        check("fullpage sanLoad", f.sanLoad, "2");
        check("fullpage tariff", f.tariff, "A-1A(01)");
        check("fullpage billMonth", f.billMonth, "OCT 26");
        check("fullpage readingDate", f.readingDate, "10 OCT 26");
        check("fullpage issueDate", f.issueDate, "13 OCT 26");
        check("fullpage dueDate", f.dueDate, "22 OCT 26");
        check("fullpage amount", f.amount, "2,065");
        check("fullpage lpSurcharge", f.lpSurcharge, "87 / 173");
        check("fullpage surchargeTill25", f.surchargeTill25, "87");
        check("fullpage surchargeAfter25", f.surchargeAfter25, "173");
        check("fullpage amountAfterDueDate", f.amountAfterDueDate,
                "Till 25-EP 2,152 / After 25-EP 2,238");
        check("fullpage payableTill25", f.payableTill25, "2,152");
        check("fullpage payableAfter25", f.payableAfter25, "2,238");
        check("fullpage meterNo", f.meterNo, "S-P 7654321");
        check("fullpage mf", f.mf, "1");
        check("fullpage prevReading", f.prevReading, "652");
        check("fullpage currReading", f.currReading, "765");
        check("fullpage units", f.units, "113");
        check("fullpage electricityCharges", f.electricityCharges, "4280");
        check("fullpage subsidy", f.subsidy, "2551");
        check("fullpage netCharges", f.netCharges, "1729");
        check("fullpage taxes", f.taxes, "330");
        check("fullpage currentBill", f.currentBill, "2060");
        check("fullpage arrears", f.arrears, "0");
        check("fullpage installment", f.installment, "0");
        check("fullpage adjustments", f.adjustments, "0");
        check("fullpage weCredit", f.weCredit, "0");
        check("fullpage lockOpenCredit", f.lockOpenCredit, "0");
        check("fullpage fpa", f.fpa, "5");
        check("fullpage grandTotal", f.grandTotal, "2065");
        check("fullpage extraLines count", f.extraLines.size(), 1);
        check("fullpage extraLines label", f.extraLines.get(0)[0],
                "TRANSFORMER");
        check("fullpage extraLines value", f.extraLines.get(0)[1],
                "TRF-007");

        // ---- 12-row BILL HISTORY harvested generically ----
        check("fullpage history tables", f.history.size(), 1);
        List<String[]> fh = f.history.get(0);
        check("fullpage history rows (header+12)", fh.size(), 13);
        check("fullpage history header0", fh.get(0)[0], "MONTH");
        check("fullpage history r1 month", fh.get(1)[0], "SEP25");
        check("fullpage history r1 units", fh.get(1)[2], "98");
        check("fullpage history r12 month", fh.get(12)[0], "AUG26");
        check("fullpage history r12 units", fh.get(12)[2], "81");
        check("fullpage history r12 bill", fh.get(12)[3], "1482");

        // ---- fallback chain ordering: direct -> suffix -> form-post ----
        String miss = "<html><head><title>No bill</title></head><body>"
                + "reference " + digits + " — units unknown, "
                + "please try again</body></html>";

        // A. Direct hit on the no-suffix leg.
        FakeTransport ta = new FakeTransport();
        ta.add(200, fakeFormHtml("T"), base,
                "Set-Cookie", FAKE_COOKIE);
        ta.add(200, fullPage, base + "/general?refno=" + digits);
        BillInfo ba = LescoBillFetcher.fetchBillWith(ta, digits + "U");
        check("chain A name", ba.consumerName.contains("SYNTHETIC"), true);
        check("chain A fetchPath",
                LescoBillFetcher.lastDebug().fetchPath, "direct");
        check("chain A urls", ta.seenUrls.toString(),
                "[" + base + ", " + base + "/general?refno=" + digits + "]");
        check("chain A methods", ta.seenMethods.toString(),
                "[GET, GET]");
        check("chain A cookie on direct GET",
                ta.seenCookies.get(1) != null
                && ta.seenCookies.get(1).contains(
                        "__RequestVerificationToken=SRV123"), true);

        // B. No-suffix misses -> suffix retry hits.
        FakeTransport tb = new FakeTransport();
        tb.add(200, fakeFormHtml("T"), base,
                "Set-Cookie", FAKE_COOKIE);
        tb.add(200, miss, base + "/general?refno=" + digits);
        tb.add(200, fullPage, base + "/general?refno=" + digits + "U");
        BillInfo bb = LescoBillFetcher.fetchBillWith(tb, digits + "U");
        check("chain B amount", bb.amount, "2,065");
        check("chain B fetchPath",
                LescoBillFetcher.lastDebug().fetchPath, "direct+suffix");
        check("chain B third url", tb.seenUrls.get(2),
                base + "/general?refno=" + digits + "U");
        check("chain B methods", tb.seenMethods.toString(),
                "[GET, GET, GET]");

        // C. Both direct legs miss -> form-POST fallback.
        FakeTransport tc = new FakeTransport();
        tc.add(200, fakeFormHtml("T"), base,
                "Set-Cookie", FAKE_COOKIE);
        tc.add(200, miss, base + "/general?refno=" + digits);
        tc.add(200, miss, base + "/general?refno=" + digits + "U");
        tc.add(200, fakeFormHtml("FORMTOKEN"), base,
                "Set-Cookie", FAKE_COOKIE);
        tc.add(200, fakeBillHtml(), base);
        BillInfo bc = LescoBillFetcher.fetchBillWith(tc, digits + "U");
        check("chain C fallback amount", bc.amount, "1234.56");
        check("chain C fetchPath",
                LescoBillFetcher.lastDebug().fetchPath, "form-post");
        check("chain C methods", tc.seenMethods.toString(),
                "[GET, GET, GET, GET, POST]");
        check("chain C POST went to base", tc.seenUrls.get(4), base);

        // D. Anti-forgery text on the direct leg still surfaces.
        FakeTransport td = new FakeTransport();
        td.add(200, fakeFormHtml("T"), base);
        td.add(200, "The required anti-forgery cookie"
                + " \"__RequestVerificationToken\" is not present. "
                + "ref " + digits + " payable 2065 units 113",
                base + "/general?refno=" + digits);
        FetchKind dKind = null;
        try {
            LescoBillFetcher.fetchBillWith(td, digits + "U");
            check("chain D throws", false, true);
        } catch (FetchException e) {
            dKind = e.kind;
        }
        check("chain D kind ANTIFORGERY_ERROR", dKind,
                FetchKind.ANTIFORGERY_ERROR);

        // E. Portal error box on the direct leg -> chain moves on to the
        //    form-POST fallback (no silent failure).
        FakeTransport te = new FakeTransport();
        te.add(200, fakeFormHtml("T"), base,
                "Set-Cookie", FAKE_COOKIE);
        te.add(200, "<html><body><div id=\"ua\">Record Not Found</div>"
                + "ref " + digits + " payable 2065 units 113</body></html>",
                base + "/general?refno=" + digits);
        te.add(200, miss, base + "/general?refno=" + digits + "U");
        te.add(200, fakeFormHtml("FORMTOKEN"), base,
                "Set-Cookie", FAKE_COOKIE);
        te.add(200, fakeBillHtml(), base);
        BillInfo be = LescoBillFetcher.fetchBillWith(te, digits + "U");
        check("chain E falls back to form-post", be.amount, "1234.56");
        check("chain E fetchPath",
                LescoBillFetcher.lastDebug().fetchPath, "form-post");
        check("chain E methods", te.seenMethods.toString(),
                "[GET, GET, GET, GET, POST]");

        // F. Full-bill page with zero recognizable labels still throws
        //    PARSE_EMPTY (never a blank bill).
        FetchKind fKind = null;
        try {
            LescoBillFetcher.parseFullBillHtml(
                    "<html><head><title>X</title></head><body><table>"
                    + "<tr><td>Foo</td><td>Bar</td></tr></table></body></html>",
                    digits);
            check("fullpage empty throws", false, true);
        } catch (FetchException e) {
            fKind = e.kind;
        }
        check("fullpage empty kind", fKind, FetchKind.PARSE_EMPTY);
    }

    // ---- v2.0.6 tests: new-design detector + parser ----

    static String readUserFile(String name) throws Exception {
        String home = System.getProperty("user.home");
        byte[] b = Files.readAllBytes(
                Paths.get(home + "/workspace/user/files/" + name));
        return new String(b, StandardCharsets.UTF_8);
    }

    static void runV206Tests() throws Exception {
        String digits = "02118240618604";
        String typed = digits + "U";
        // Real captures: the 2026-09-20 bill page (new design) and the
        // 2026-09-19 form page (negative detector fixture).
        String bill = readUserFile("bijli-bill-debug-20260920-003440.html");
        String form = readUserFile("bijli-bill-debug-20260919-235401.html");

        // ---- detector ----
        check("v206 detect bill (typed ref)", LescoBillFetcher.isNewBillPage(bill, typed), true);
        check("v206 detect bill (bare digits)", LescoBillFetcher.isNewBillPage(bill, digits), true);
        check("v206 detect form page false", LescoBillFetcher.isNewBillPage(form, typed), false);
        check("v206 detect wrong ref false", LescoBillFetcher.isNewBillPage(bill, "00000000000000"), false);
        check("v206 detect null html false", LescoBillFetcher.isNewBillPage(null, typed), false);
        check("v206 detect empty html false", LescoBillFetcher.isNewBillPage("", typed), false);

        // ---- parse the REAL bill via the form-post parser (the real
        //      capture's path: POST -> 302 -> /general) ----
        BillInfo b = LescoBillFetcher.parseBillHtml(bill, typed);
        check("v206 ref", b.referenceNumber, typed);
        check("v206 name", b.consumerName.contains("Namoos Tahira"), true);
        check("v206 consumerId", b.customerId, "11179932");
        check("v206 feeder", b.feeder, "(036209) Pandoriyan");
        check("v206 subDivision", b.subDivision, "Ahmad Abad");
        check("v206 category", b.category, "Protected");
        check("v206 tariffCategory", b.tariffCategory, "Domestic");
        check("v206 sanLoad", b.sanLoad, "2");
        check("v206 tariff", b.tariff, "A-1A(01)");
        check("v206 meterNo", b.meterNo, "S-P 0127416");
        check("v206 mf", b.mf, "1");
        check("v206 prevReading", b.prevReading, "652");
        check("v206 currReading", b.currReading, "765");
        check("v206 units", b.units, "113");
        check("v206 unitsAsInt", b.unitsAsInt(), 113);
        // payment slip
        check("v206 amount", b.amount, "2065");
        check("v206 amountAsDouble", b.amountAsDouble(), 2065.0);
        check("v206 dueDate", b.dueDate, "22 SEP 26");
        check("v206 billMonth", b.billMonth, "SEP 26");
        // late-payment tiers (comma stripped)
        check("v206 payableTill25", b.payableTill25, "2152");
        check("v206 payableAfter25", b.payableAfter25, "2238");
        check("v206 surchargeTill25", b.surchargeTill25, "87");
        check("v206 surchargeAfter25", b.surchargeAfter25, "173");
        check("v206 lpSurcharge", b.lpSurcharge, "87 / 173");
        // all 12 charge pairs
        check("v206 electricityCharges", b.electricityCharges, "4280");
        check("v206 subsidy", b.subsidy, "2551");
        check("v206 netCharges", b.netCharges, "1729");
        check("v206 taxes", b.taxes, "330");
        check("v206 currentBill", b.currentBill, "2060");
        check("v206 arrears", b.arrears, "0");
        check("v206 installment", b.installment, "0");
        check("v206 adjustments", b.adjustments, "0");
        check("v206 weCredit", b.weCredit, "0");
        check("v206 lockOpenCredit", b.lockOpenCredit, "0");
        check("v206 fpa", b.fpa, "5");
        check("v206 grandTotal", b.grandTotal, "2065");
        // history: 12 data rows
        check("v206 history tables", b.history.size(), 1);
        List<String[]> hist = b.history.get(0);
        check("v206 history rows", hist.size(), 12);
        check("v206 history r1 month", hist.get(0)[0], "SEP25");
        check("v206 history r1 units", hist.get(0)[2], "98");
        check("v206 history r1 bill", hist.get(0)[3], "1066");
        check("v206 history r1 payment", hist.get(0)[4], "1066");
        check("v206 history r12 month", hist.get(11)[0], "AUG26");
        check("v206 history r12 units", hist.get(11)[2], "81");
        check("v206 history r12 bill", hist.get(11)[3], "1482");

        // ---- same page via the direct-leg parser ----
        BillInfo f = LescoBillFetcher.parseFullBillHtml(bill, typed);
        check("v206 direct amount", f.amount, "2065");
        check("v206 direct dueDate", f.dueDate, "22 SEP 26");
        check("v206 direct units", f.units, "113");
        check("v206 direct name", f.consumerName.contains("Namoos Tahira"), true);
        check("v206 direct grandTotal", f.grandTotal, "2065");
        check("v206 direct payableTill25", f.payableTill25, "2152");

        // ---- scripted round trip: form-post -> 302 -> GET /general
        //      serving the real bill HTML ----
        String base = "https://bill.pitc.com.pk/lescobill";
        String miss = "<html><head><title>No bill</title></head><body>"
                + "reference " + digits + " - no data on direct leg"
                + "</body></html>";
        FakeTransport t = new FakeTransport();
        t.add(200, fakeFormHtml("T"), base,
                "Set-Cookie", FAKE_COOKIE);                       // (a) seed
        t.add(200, miss, base + "/general?refno=" + digits);       // (b) direct miss
        t.add(200, miss, base + "/general?refno=" + digits + "U"); // (c) suffix miss
        t.add(200, fakeFormHtml("FORMTOKEN"), base,
                "Set-Cookie", FAKE_COOKIE);                       // (d1) form GET
        t.add(302, "", base, "Location",
                "/lescobill/general?refno=" + digits);            // (d2) POST -> 302
        t.add(200, bill, base + "/general?refno=" + digits);       // (d3) redirect GET
        LescoBillFetcher.BillPage p =
                LescoBillFetcher.fetchBillPageWith(t, typed);
        check("v206 rt fetchPath", p.fetchPath, "form-post");
        check("v206 rt parseOk", p.parseOk, true);
        check("v206 rt name", p.info.consumerName.contains("Namoos Tahira"), true);
        check("v206 rt amount", p.info.amount, "2065");
        check("v206 rt dueDate", p.info.dueDate, "22 SEP 26");
        check("v206 rt units", p.info.units, "113");
        check("v206 rt html equals fixture bytes", p.html.equals(bill), true);
        check("v206 rt methods", t.seenMethods.toString(),
                "[GET, GET, GET, GET, POST, GET]");
        check("v206 rt final url", LescoBillFetcher.lastDebug().finalUrl,
                base + "/general?refno=" + digits);
    }

    // ---- v2.1.0 Meter Check tests ----

    static void checkClose(String name, double actual, double expected,
            double eps) {
        check(name + " (got " + actual + ")", Math.abs(actual - expected) < eps,
                true);
    }

    static void runV210Tests() throws Exception {
        // ---- Tariff slab math (hand-computed from S.R.O. 279(I)/2026) ----
        // 113 Protected: 100x10.54 + 13x13.01 = 1054.00 + 169.13
        checkClose("v210 slab 113 protected",
                Tariff.energyCharges(113, Tariff.PROTECTED), 1223.13, 0.001);
        // 250 Non-Protected: 100x22.44 + 100x28.91 + 50x33.10
        checkClose("v210 slab 250 non-protected",
                Tariff.energyCharges(250, Tariff.NON_PROTECTED), 6790.0,
                0.001);
        checkClose("v210 slab 100 protected",
                Tariff.energyCharges(100, Tariff.PROTECTED), 1054.0, 0.001);
        checkClose("v210 slab 0 units",
                Tariff.energyCharges(0, Tariff.PROTECTED), 0.0, 0.001);
        // Protected cliff: 250 protected units bill at Non-Protected rates
        checkClose("v210 slab 250 protected->nonprot",
                Tariff.energyCharges(250, Tariff.PROTECTED), 6790.0, 0.001);

        // ---- OCR candidate picker (pure) ----
        java.util.List<java.util.List<String>> frames =
                new java.util.ArrayList<java.util.List<String>>();
        frames.add(java.util.Arrays.asList("765", "65"));
        frames.add(java.util.Arrays.asList("765"));
        frames.add(java.util.Arrays.asList("760"));
        frames.add(java.util.Arrays.asList("765"));
        check("v210 pickDigits most-frequent",
                MeterCheckCalc.pickDigits(frames), "765");
        java.util.List<java.util.List<String>> tie =
                new java.util.ArrayList<java.util.List<String>>();
        tie.add(java.util.Arrays.asList("111"));
        tie.add(java.util.Arrays.asList("222"));
        check("v210 pickDigits tie->first",
                MeterCheckCalc.pickDigits(tie), "111");
        check("v210 pickDigits empty", MeterCheckCalc.pickDigits(
                new java.util.ArrayList<java.util.List<String>>()), null);
        check("v210 pickDigits null", MeterCheckCalc.pickDigits(null), null);
        check("v210 digitRuns",
                MeterCheckCalc.digitRuns("kWh 00765 V ~ 220").toString(),
                "[00765, 220]");
        check("v210 digitRuns long-run",
                MeterCheckCalc.digitRuns("id 7890123 ok").toString(),
                "[789012]");
        check("v210 digitRuns null",
                MeterCheckCalc.digitRuns(null).toString(), "[]");

        // ---- validation (v2.2.1: base = bill's PRESENT reading) ----
        // User's live case: bill present 765, new meter reading 800 ->
        // 35 units SINCE the bill (not 800-652=148, which double-counted
        // the 113 already-billed units).
        MeterCheckCalc.Result ok = MeterCheckCalc.compute(
                "765", "800", "Protected", "330", null, "5", "4280", 12);
        check("v210 valid no error", ok.error, null);
        checkClose("v210 valid units", ok.unitsUsed, 35.0, 1e-9);
        checkClose("v210 valid baseReading", ok.baseReading, 765.0, 1e-9);
        MeterCheckCalc.Result bad = MeterCheckCalc.compute(
                "765", "700", "Protected", "330", null, "5", "4280", 12);
        check("v210 current<base rejected", bad.error,
                MeterCheckCalc.ERR_READING_ORDER);
        check("v210 current<base exact string", bad.error,
                "Nayi reading bill wali reading se kam hai \u2014 dobara check karo");
        MeterCheckCalc.Result eq = MeterCheckCalc.compute(
                "765", "765", "Protected", "330", null, "5", "4280", 12);
        check("v210 current==base rejected", eq.error,
                MeterCheckCalc.ERR_READING_ORDER);
        MeterCheckCalc.Result unp = MeterCheckCalc.compute(
                "abc", "800", "Protected", "330", null, "5", "4280", 12);
        check("v210 unparseable rejected", unp.error,
                MeterCheckCalc.ERR_READING_UNPARSEABLE);

        // ---- tax-ratio andaza on the REAL 2026-09-20 bill
        //      (prev 652 -> curr 765 = 113 units, payable 2065) ----
        String realBill = readUserFile("bijli-bill-debug-20260920-003440.html");
        int cut = realBill.indexOf("-->");
        if (cut > 0 && realBill.startsWith("<!--")) {
            realBill = realBill.substring(cut + 3);
        }
        BillInfo rb = LescoBillFetcher.parseBillHtml(realBill,
                "02118240618604U");
        check("v210 real bill prev", rb.prevReading, "652");
        check("v221 real bill curr (present)", rb.currReading, "765");
        check("v210 real bill taxes", rb.taxes, "330");
        // ---- tax-ratio andaza FORMULA check on 113 units ----
        // (base 652 is hypothetical here — this documents the andaza
        // formula itself: energy + bill tax ratio. v2.2.1 counts units
        // from the bill's PRESENT reading; the formula is unchanged.)
        MeterCheckCalc.Result r113 = MeterCheckCalc.compute(
                rb.prevReading, rb.currReading, rb.category,
                rb.taxes, rb.gst, rb.fpa, rb.electricityCharges, 12);
        check("v210 real bill no error", r113.error, null);
        checkClose("v210 real bill energy 113 protected",
                r113.energyEstimate, 1223.13, 0.01);
        // taxRatio = (330 + 5) / 4280 = 0.078271...
        checkClose("v210 real bill taxRatio", r113.taxRatio,
                335.0 / 4280.0, 0.0001);
        check("v210 real bill no fallback", r113.taxRatioFallback, false);
        // estimatedTotal = 1223.13 * (1 + 335/4280) = 1318.87 (hand)
        checkClose("v210 real bill andaza total", r113.estimatedTotal,
                1318.87, 0.02);
        double devReal = Math.abs(r113.estimatedTotal - 2065.0) / 2065.0
                * 100.0;
        System.out.println("info v210 andaza deviation vs real bill 2065: "
                + String.format(java.util.Locale.US, "%.1f", devReal)
                + "% (spec formula on this bill's subsidy structure)");

        // ---- tax-ratio andaza on the internally-consistent 250-unit
        //      fixture: must land within +-15% of its grand total ----
        String full = read("lesco-bill-full-SYNTHETIC.html");
        BillInfo fb = LescoBillFetcher.parseBillHtml(full, "x");
        MeterCheckCalc.Result r250 = MeterCheckCalc.compute(
                "0", "250", fb.category, fb.taxes, fb.gst, fb.fpa,
                fb.electricityCharges, 12);
        check("v210 fixture250 no error", r250.error, null);
        checkClose("v210 fixture250 energy", r250.energyEstimate, 6790.0,
                0.01);
        double dev250 = Math.abs(r250.estimatedTotal - 9109.20) / 9109.20
                * 100.0;
        System.out.println("info v210 andaza deviation vs fixture 9109.20: "
                + String.format(java.util.Locale.US, "%.2f", dev250) + "%");
        check("v210 fixture250 within +-15%", dev250 <= 15.0, true);

        // ---- tax fallback: bill fields missing -> 0.18 GST-only ----
        MeterCheckCalc.Result fbl = MeterCheckCalc.compute(
                "100", "150", "Domestic", null, null, null, null, 12);
        check("v210 tax fallback ratio", fbl.taxRatioFallback, true);
        checkClose("v210 tax fallback value", fbl.taxRatio, 0.18, 0.0001);

        // ---- projection: 35 units on day 12 -> round(35/12*30)=88 ----
        check("v210 projection day12", ok.projectedMonthUnits, 88);
        MeterCheckCalc.Result nod = MeterCheckCalc.compute(
                "765", "800", "Protected", "330", null, "5", "4280", 0);
        check("v210 projection day<1 disabled", nod.projectedMonthUnits, -1);

        // ---- slab alerts (35 units since bill) ----
        check("v210 35 nextSlab", ok.nextSlab, 100);
        checkClose("v210 35 remaining", ok.remaining, 65.0, 1e-9);
        check("v210 35 no slabWarning", ok.slabWarning, false);
        check("v210 35 protectedCliff", ok.protectedCliff, true);
        check("v210 35 isProtected", ok.isProtected, true);
        MeterCheckCalc.Result r190 = MeterCheckCalc.compute(
                "0", "190", "Protected", "330", null, "5", "4280", 12);
        check("v210 190 slabWarning (10 left)", r190.slabWarning, true);
        checkClose("v210 190 remaining", r190.remaining, 10.0, 1e-9);
        MeterCheckCalc.Result r750 = MeterCheckCalc.compute(
                "0", "750", "Domestic", null, null, null, null, 12);
        check("v210 750 top slab nextSlab", r750.nextSlab, -1);
        check("v210 750 no slabWarning", r750.slabWarning, false);
        check("v210 750 no protectedCliff", r750.protectedCliff, false);
        MeterCheckCalc.Result r200 = MeterCheckCalc.compute(
                "0", "200", "Protected", "330", null, "5", "4280", 12);
        check("v210 200 cliff edge (not <200)", r200.protectedCliff, false);
        check("v210 200 nextSlab", r200.nextSlab, 300);

        // ---- EN/UR string parity (all keys, incl. new meter_* ones) ----
        java.util.Map<String, String> en = readStrings(
                "res/values/strings.xml");
        java.util.Map<String, String> ur = readStrings(
                "res/values-ur/strings.xml");
        check("v210 parity key sets equal", en.keySet(), ur.keySet());
        String[] mustExist = {"meter_check_btn", "meter_check_title",
                "meter_step1_text", "meter_reading_hint", "meter_slab_warn",
                "meter_protected_warn", "meter_projection",
                "meter_disclaimer", "meter_result_title"};
        for (String k : mustExist) {
            check("v210 en has " + k, en.containsKey(k), true);
            check("v210 ur has " + k, ur.containsKey(k), true);
            check("v210 ur " + k + " non-empty",
                    ur.get(k) != null && !ur.get(k).trim().isEmpty(), true);
        }
        // v2.5.0: renamed METER CHECK -> UNIT CHECK (user request).
        // v2.6.0 design: no emoji in the button string — a vector video
        // icon is drawn by HomeActivity (R.drawable.ic_video).
        check("v250 ur meter_check_btn", ur.get("meter_check_btn"),
                "یونٹ چیک");
        check("v250 en meter_check_btn", en.get("meter_check_btn"),
                "UNIT CHECK");
        check("v250 ur meter_check_title", ur.get("meter_check_title"),
                "یونٹ چیک");
        check("v250 en meter_check_title", en.get("meter_check_title"),
                "Unit Check");
        check("v250 en meter_result_title", en.get("meter_result_title"),
                "Unit Check — ANDAZA");
        check("v250 ur meter_result_title", ur.get("meter_result_title"),
                "یونٹ چیک — تخمینہ");
        check("v250 en meter_no_bill", en.get("meter_no_bill"),
                "Pehle BILL DEKHO se bill nikalo — phir Unit Check hoga");
        // format-arg parity: every %1$s / %1$d in EN exists in UR
        int fmtMismatch = 0;
        for (String k : en.keySet()) {
            if (!fmtArgs(en.get(k)).equals(fmtArgs(ur.get(k)))) {
                System.out.println("FAIL v210 fmt-args mismatch: " + k);
                fmtMismatch++;
            }
        }
        check("v210 fmt-arg parity", fmtMismatch, 0);
    }

    // ---- v2.2.0 tests ----

    /** Shorthand for a mocked OCR element in v2.2.0 tests. */
    static KwhAnchorPicker.OcrElement el(String text, int l, int t, int r,
            int b) {
        return new KwhAnchorPicker.OcrElement(text, l, t, r, b);
    }

    static void runV220Tests() throws Exception {
        // ---- kWh-anchored picker (mocked elements; real OCR can't run
        //      headless). Models the user's meter photo: "765.03" LEFT
        //      of "kWh", same line. ----
        java.util.List<java.util.List<KwhAnchorPicker.OcrElement>> f1 =
                new java.util.ArrayList<java.util.List<KwhAnchorPicker.OcrElement>>();
        java.util.List<KwhAnchorPicker.OcrElement> fr1 =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        fr1.add(el("765.03", 100, 200, 260, 260));
        fr1.add(el("kWh", 280, 200, 340, 260));
        f1.add(fr1);
        KwhAnchorPicker.Picked p1 = KwhAnchorPicker.pick(f1);
        check("v220 kwh anchored raw", p1.rawValue, "765.03");
        check("v220 kwh anchored billing", p1.billingValue, "765");
        check("v220 kwh anchored flag", p1.kwhAnchored, true);

        // Far-away digits ("220" near "V", another line) lose to the
        // kWh-anchored reading.
        java.util.List<KwhAnchorPicker.OcrElement> fr2 =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        fr2.add(el("220", 100, 500, 220, 560));
        fr2.add(el("V", 240, 500, 280, 560));
        fr2.add(el("765.03", 100, 200, 260, 260));
        fr2.add(el("kWh", 280, 200, 340, 260));
        java.util.List<java.util.List<KwhAnchorPicker.OcrElement>> f2 =
                new java.util.ArrayList<java.util.List<KwhAnchorPicker.OcrElement>>();
        f2.add(fr2);
        KwhAnchorPicker.Picked p2 = KwhAnchorPicker.pick(f2);
        check("v220 far digits ignored", p2.rawValue, "765.03");
        check("v220 far digits anchored", p2.kwhAnchored, true);

        // Digits and "kWh" inside ONE element: perfect anchor.
        java.util.List<java.util.List<KwhAnchorPicker.OcrElement>> f3 =
                new java.util.ArrayList<java.util.List<KwhAnchorPicker.OcrElement>>();
        java.util.List<KwhAnchorPicker.OcrElement> fr3 =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        fr3.add(el("765.03 kWh", 100, 200, 340, 260));
        f3.add(fr3);
        KwhAnchorPicker.Picked p3 = KwhAnchorPicker.pick(f3);
        check("v220 single-block anchor", p3.rawValue, "765.03");
        check("v220 single-block flag", p3.kwhAnchored, true);

        // No kWh token anywhere -> fallback: most frequent run.
        java.util.List<java.util.List<KwhAnchorPicker.OcrElement>> f4 =
                new java.util.ArrayList<java.util.List<KwhAnchorPicker.OcrElement>>();
        java.util.List<KwhAnchorPicker.OcrElement> a =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        a.add(el("111", 0, 0, 10, 10));
        java.util.List<KwhAnchorPicker.OcrElement> b =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        b.add(el("222", 0, 0, 10, 10));
        java.util.List<KwhAnchorPicker.OcrElement> c =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        c.add(el("111", 0, 0, 10, 10));
        f4.add(a);
        f4.add(b);
        f4.add(c);
        KwhAnchorPicker.Picked p4 = KwhAnchorPicker.pick(f4);
        check("v220 no-kwh fallback", p4.rawValue, "111");
        check("v220 no-kwh not anchored", p4.kwhAnchored, false);

        // Fallback tie -> first seen.
        java.util.List<java.util.List<KwhAnchorPicker.OcrElement>> f5 =
                new java.util.ArrayList<java.util.List<KwhAnchorPicker.OcrElement>>();
        f5.add(a);
        f5.add(b);
        KwhAnchorPicker.Picked p5 = KwhAnchorPicker.pick(f5);
        check("v220 fallback tie->first", p5.rawValue, "111");

        // Empty / null -> null.
        check("v220 pick null", KwhAnchorPicker.pick(null), null);
        check("v220 pick empty", KwhAnchorPicker.pick(
                new java.util.ArrayList<java.util.List<KwhAnchorPicker.OcrElement>>()),
                null);

        // ---- decimal truncation (billing uses the integer part) ----
        check("v220 trunc 765.03",
                KwhAnchorPicker.billingInteger("765.03"), "765");
        check("v220 trunc plain",
                KwhAnchorPicker.billingInteger("1234"), "1234");
        check("v220 trunc null", KwhAnchorPicker.billingInteger(null),
                null);

        // ---- adaptive frame math ----
        check("v220 frames 45s->12",
                KwhAnchorPicker.frameCountFor(45000L, 12), 12);
        check("v220 frames 8s->8",
                KwhAnchorPicker.frameCountFor(8000L, 12), 8);
        check("v220 frames 0.5s->1",
                KwhAnchorPicker.frameCountFor(500L, 12), 1);
        check("v220 frames 120s->12",
                KwhAnchorPicker.frameCountFor(120000L, 12), 12);
        java.util.List<Long> ts =
                KwhAnchorPicker.frameTimestampsUs(8000L, 12);
        check("v220 ts count", ts.size(), 8);
        check("v220 ts first", ts.get(0), 500000L);
        boolean mono = true;
        for (int i = 1; i < ts.size(); i++) {
            if (ts.get(i) <= ts.get(i - 1)) mono = false;
        }
        check("v220 ts monotonic", mono, true);
        check("v220 ts last within video",
                ts.get(ts.size() - 1) < 8000000L, true);

        // ---- v2.2.0 guard: duration + size validation (mocked
        //      metadata — the pure checkVideo decision function) ----
        check("v220 vid ok", KwhAnchorPicker.checkVideo(45000L,
                50000000L), KwhAnchorPicker.VideoCheck.OK);
        check("v220 vid 60s boundary ok", KwhAnchorPicker.checkVideo(
                60000L, 1L), KwhAnchorPicker.VideoCheck.OK);
        check("v220 vid 150MB boundary ok", KwhAnchorPicker.checkVideo(
                1000L, 150L * 1024L * 1024L),
                KwhAnchorPicker.VideoCheck.OK);
        check("v220 vid too long", KwhAnchorPicker.checkVideo(60001L,
                1L), KwhAnchorPicker.VideoCheck.TOO_LONG);
        check("v220 vid hours long", KwhAnchorPicker.checkVideo(
                7200000L, 1L), KwhAnchorPicker.VideoCheck.TOO_LONG);
        check("v220 vid too big", KwhAnchorPicker.checkVideo(1000L,
                150L * 1024L * 1024L + 1),
                KwhAnchorPicker.VideoCheck.TOO_BIG);
        check("v220 vid unknown allowed", KwhAnchorPicker.checkVideo(
                -1L, -1L), KwhAnchorPicker.VideoCheck.OK);
        check("v220 vid duration checked first",
                KwhAnchorPicker.checkVideo(70000L, 999999999L),
                KwhAnchorPicker.VideoCheck.TOO_LONG);

        // ---- structural: camera + gallery route into ONE pipeline ----
        byte[] sb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/MeterCheckActivity.java"));
        String src = new String(sb,
                java.nio.charset.StandardCharsets.UTF_8);
        check("v220 gallery intent",
                src.contains("ACTION_GET_CONTENT"), true);
        check("v220 gallery mime", src.contains("\"video/*\""), true);
        check("v220 both codes routed together", src.contains(
                "requestCode == REQ_VIDEO || requestCode == REQ_GALLERY"),
                true);
        check("v220 shared pipeline",
                src.contains("processVideo(videoUri)"), true);
        check("v220 60s cap", src.contains("MAX_VIDEO_SECONDS"), true);
        check("v220 old 10s cap gone",
                !src.contains("EXTRA_DURATION_LIMIT, VIDEO_SECONDS"),
                true);
        check("v220 kwh picker wired",
                src.contains("KwhAnchorPicker.pick("), true);
        check("v220 guard wired",
                src.contains("checkVideoLimits(uri)"), true);

        // ---- EN/UR parity for the new v2.2.0 strings ----
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] newKeys = {"meter_gallery_btn", "meter_gallery_sub",
                "meter_kwh_found", "meter_video_too_long",
                "meter_video_too_big"};
        for (String k : newKeys) {
            check("v220 key en " + k, en.containsKey(k), true);
            check("v220 key ur " + k, ur.containsKey(k), true);
            check("v220 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }
        check("v220 step1 mentions 60s (en)",
                en.get("meter_step1_text").contains("60"), true);
        check("v220 step1 mentions 60s (ur)",
                ur.get("meter_step1_text").contains("60"), true);
    }

    // ---- v2.2.1 tests ----

    static void runV221Tests() throws Exception {
        // ---- BUG 1: base = bill's PRESENT reading (fallback: previous) ----
        check("v221 resolveBase present",
                MeterCheckCalc.resolveBase("765", "652"), "765");
        check("v221 resolveBase null",
                MeterCheckCalc.resolveBase(null, "652"), "652");
        check("v221 resolveBase empty",
                MeterCheckCalc.resolveBase("", "652"), "652");
        check("v221 resolveBase zero",
                MeterCheckCalc.resolveBase("0", "652"), "652");
        check("v221 resolveBase unparseable",
                MeterCheckCalc.resolveBase("abc", "652"), "652");

        // User's live case: bill present 765, new reading 800 ->
        // 35 units SINCE the bill.
        MeterCheckCalc.Result r35 = MeterCheckCalc.compute(
                "765", "800", "Protected", "330", null, "5", "4280", 12);
        check("v221 35 no error", r35.error, null);
        checkClose("v221 35 units", r35.unitsUsed, 35.0, 1e-9);
        checkClose("v221 35 baseReading", r35.baseReading, 765.0, 1e-9);
        checkClose("v221 35 energy", r35.energyEstimate, 35 * 10.54,
                0.01);
        // 368.90 * (1 + 335/4280) = 397.77 (hand)
        checkClose("v221 35 andaza total", r35.estimatedTotal, 397.77,
                0.02);
        check("v221 35 nextSlab", r35.nextSlab, 100);
        checkClose("v221 35 remaining", r35.remaining, 65.0, 1e-9);
        check("v221 35 protectedCliff", r35.protectedCliff, true);
        check("v221 35 projection", r35.projectedMonthUnits, 88);

        // ---- BUG 2a: FramePrep.sharpness (Laplacian variance) ----
        int[] flat = new int[64]; // 8x8 uniform -> exactly 0
        checkClose("v221 sharpness flat",
                FramePrep.sharpness(flat, 8, 8), 0.0, 1e-9);
        int[] checker = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                checker[y * 8 + x] =
                        ((x + y) % 2 == 0) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        double cs = FramePrep.sharpness(checker, 8, 8);
        check("v221 sharpness checker>flat", cs > 0.0, true);
        check("v221 sharpness tiny invalid",
                FramePrep.sharpness(new int[]{1}, 1, 1), 0.0);
        check("v221 sharpness null",
                FramePrep.sharpness(null, 8, 8), 0.0);
        // ranking: sharp frame wins, ties keep lower index (stable)
        double[] scores = {0.0, cs, 0.0};
        int[] top = FramePrep.topSharpestIndices(scores, 2);
        check("v221 topSharpest len", top.length, 2);
        check("v221 topSharpest first", top[0], 1);
        double[] tie = {5.0, 5.0, 3.0};
        int[] topTie = FramePrep.topSharpestIndices(tie, 2);
        check("v221 topSharpest stable", topTie[0], 0);
        check("v221 topSharpest stable2", topTie[1], 1);

        // ---- BUG 2b: preprocessing on small bitmaps (no crash) ----
        int[] g4 = {10, 10, 200, 200};
        int[] s4 = FramePrep.contrastStretch(g4);
        check("v221 stretch lo->0", s4[0], 0);
        check("v221 stretch hi->255", s4[2], 255);
        int[] gsame = {77, 77, 77};
        check("v221 stretch degenerate",
                FramePrep.contrastStretch(gsame)[1], 77);
        check("v221 stretch null",
                FramePrep.contrastStretch(null).length, 0);
        int[] tiny = {0, 255, 255, 0}; // 2x2
        int[] big = FramePrep.upscale2x(tiny, 2, 2);
        check("v221 upscale 2x2->4x4", big.length, 16);
        int[] inv = FramePrep.invert(new int[]{0, 255, 128});
        check("v221 invert", inv[0] == 255 && inv[1] == 0
                && inv[2] == 127, true);
        int[] c10 = new int[100];
        for (int i = 0; i < 100; i++) c10[i] = i;
        int[] cc = FramePrep.centerCrop(c10, 10, 10, 0.6f, 0.6f);
        check("v221 crop 10x10->6x6", cc.length, 36);
        check("v221 crop first pixel", cc[0], 22); // (2,2) -> 2*10+2
        int[] enh = FramePrep.enhanced(
                new int[]{0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000},
                2, 2);
        check("v221 enhanced packed w", enh[0], 4);
        check("v221 enhanced packed h", enh[1], 4);
        check("v221 enhanced packed len", enh.length, 18);
        int[] argb = FramePrep.toArgb(new int[]{0, 300, -5});
        check("v221 toArgb clamp", argb[1], (int) 0xFFFFFFFF);

        // ---- BUG 2c: bounded OCR job plan ----
        java.util.List<FramePrep.Job> plan8 = FramePrep.buildOcrPlan(8);
        check("v221 plan8 size", plan8.size(), 26);
        check("v221 plan8 within cap",
                plan8.size() <= FramePrep.MAX_OCR_CALLS, true);
        check("v221 plan8 originals first", plan8.get(0).variant,
                FramePrep.Variant.ORIGINAL);
        check("v221 plan8 8th original", plan8.get(7).variant,
                FramePrep.Variant.ORIGINAL);
        check("v221 plan8 enhanced next", plan8.get(8).variant,
                FramePrep.Variant.ENHANCED);
        check("v221 plan8 inverted next", plan8.get(16).variant,
                FramePrep.Variant.INVERTED);
        check("v221 plan8 crop last", plan8.get(25).variant,
                FramePrep.Variant.CROP);
        check("v221 plan3 size", FramePrep.buildOcrPlan(3).size(), 11);
        check("v221 plan0 empty", FramePrep.buildOcrPlan(0).size(), 0);
        check("v221 plan12 capped", FramePrep.buildOcrPlan(12).size(),
                26);

        // ---- BUG 2d: multi-pass merge — kWh anchor from ANY pass wins --
        // pass 0 (original): no kWh token, "220" near "V"
        java.util.List<KwhAnchorPicker.OcrElement> pass0 =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        pass0.add(el("220", 100, 500, 220, 560));
        pass0.add(el("V", 240, 500, 280, 560));
        // pass 1 (inverted): the kWh reading shows up here
        java.util.List<KwhAnchorPicker.OcrElement> pass1 =
                new java.util.ArrayList<KwhAnchorPicker.OcrElement>();
        pass1.add(el("765.03", 100, 200, 260, 260));
        pass1.add(el("kWh", 280, 200, 340, 260));
        java.util.List<java.util.List<KwhAnchorPicker.OcrElement>> passes =
                new java.util.ArrayList<java.util.List<KwhAnchorPicker.OcrElement>>();
        passes.add(pass0);
        passes.add(pass1);
        KwhAnchorPicker.Picked pm = KwhAnchorPicker.pick(passes);
        check("v221 multipass kwh wins", pm.rawValue, "765.03");
        check("v221 multipass anchored", pm.kwhAnchored, true);
        check("v221 multipass billing", pm.billingValue, "765");

        // ---- BUG 2e: failure-guidance strings (EN+UR) ----
        java.util.Map<String, String> en2 =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur2 =
                readStrings("res/values-ur/strings.xml");
        String[] failKeys = {"meter_ocr_fail_tips", "meter_zoom_hint"};
        for (String k : failKeys) {
            check("v221 key en " + k, en2.containsKey(k), true);
            check("v221 key ur " + k, ur2.containsKey(k), true);
            check("v221 ur " + k + " non-empty",
                    ur2.get(k) != null && !ur2.get(k).trim().isEmpty(),
                    true);
            check("v221 fmt " + k, fmtArgs(en2.get(k)),
                    fmtArgs(ur2.get(k)));
        }
        check("v221 tips mention flash",
                en2.get("meter_ocr_fail_tips").contains("flash"), true);
        check("v221 readings line 3 args",
                fmtArgs(en2.get("meter_readings_line")).toString(),
                "[%1$s, %2$s, %3$s]");

        // ---- structural: v2.2.1 wiring in the activities ----
        byte[] mb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/MeterCheckActivity.java"));
        String msrc = new String(mb,
                java.nio.charset.StandardCharsets.UTF_8);
        check("v221 strong ocr wired",
                msrc.contains("recognizeStrong("), true);
        check("v221 old boxes call gone",
                !msrc.contains("recognizeFramesWithBoxes("), true);
        check("v221 zoom dialog wired",
                msrc.contains("showZoomDialog("), true);
        check("v221 fail tips wired",
                msrc.contains("meter_ocr_fail_tips"), true);
        check("v221 base resolved in activity",
                msrc.contains("resolveBase("), true);
        check("v221 old prev base gone",
                !msrc.contains("bill.prevReading, typed"), true);
        check("v260 readings pair wired",
                msrc.contains("R.string.meter_readings_pair"), true);
        // v3.1.0: the usage card was folded into the receipt's row 0;
        // used units are now the screen hero (est_units_eyebrow).
        check("v310 units hero wired",
                msrc.contains("R.string.est_units_eyebrow"), true);
        byte[] bb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/BillActivity.java"));
        String bsrc = new String(bb,
                java.nio.charset.StandardCharsets.UTF_8);
        check("v221 base resolved in BillActivity",
                bsrc.contains("resolveBase("), true);
        check("v221 old prev guard gone",
                !bsrc.contains("lastPage.info.prevReading) < 0"), true);
    }

    // ---- v2.3.0 tests ----

    static void runV230Tests() throws Exception {
        // ---- auto frame pick: bestFrameIndex, no user input ----
        check("v230 best picks sharpest",
                KwhAnchorPicker.bestFrameIndex(new double[]{1.0, 5.0, 3.0}),
                1);
        check("v230 best single",
                KwhAnchorPicker.bestFrameIndex(new double[]{7.0}), 0);
        check("v230 tie keeps first",
                KwhAnchorPicker.bestFrameIndex(new double[]{4.0, 4.0, 4.0}),
                0);
        check("v230 empty -> -1",
                KwhAnchorPicker.bestFrameIndex(new double[]{}), -1);
        check("v230 null -> -1",
                KwhAnchorPicker.bestFrameIndex(null), -1);

        // ---- MeterCheckActivity: NO mandatory frame picker in the flow ----
        byte[] mb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/MeterCheckActivity.java"));
        String msrc = new String(mb,
                java.nio.charset.StandardCharsets.UTF_8);
        check("v230 no showFrames() call", !msrc.contains("showFrames()"),
                true);
        check("v230 no showFrames definition",
                !msrc.contains("void showFrames"), true);
        check("v230 no refreshStrip", !msrc.contains("refreshStrip"), true);
        check("v230 autoPickFrame wired",
                msrc.contains("autoPickFrame()"), true);
        check("v230 confirm screen directly",
                msrc.contains("showConfirm()"), true);
        check("v230 ocr progress string",
                msrc.contains("meter_ocr_reading"), true);
        check("v230 andaza explainer wired",
                msrc.contains("meter_why_andaza_a"), true);

        // ---- BillReminder: all 12 months + 2-digit year ----
        String[] months = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                           "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        for (int m = 0; m < 12; m++) {
            long dueMs = BillReminder.parseDueDate(
                    "22 " + months[m] + " 26");
            check("v230 parse " + months[m], dueMs > 0, true);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(dueMs);
            check("v230 month idx " + months[m],
                    c.get(java.util.Calendar.MONTH), m);
            check("v230 year 26 -> 2026",
                    c.get(java.util.Calendar.YEAR), 2026);
            check("v230 day " + months[m],
                    c.get(java.util.Calendar.DAY_OF_MONTH), 22);
        }
        check("v230 lowercase month",
                BillReminder.parseDueDate("22 sep 26") > 0, true);
        check("v230 bad month", BillReminder.parseDueDate("22 XXX 26")
                < 0, true);
        check("v230 bad day", BillReminder.parseDueDate("31 FEB 26")
                < 0, true);
        check("v230 null due", BillReminder.parseDueDate(null) < 0, true);
        check("v230 empty due", BillReminder.parseDueDate("") < 0, true);

        // ---- reminder = 2 days before, 09:00 ----
        java.util.Calendar fut = java.util.Calendar.getInstance();
        fut.add(java.util.Calendar.DAY_OF_MONTH, 40);
        String dueText = new java.text.SimpleDateFormat("dd MMM yy",
                java.util.Locale.US).format(fut.getTime()).toUpperCase();
        long dueMs = BillReminder.parseDueDate(dueText);
        long remMs = BillReminder.reminderTimeMs(dueMs);
        check("v230 future reminder scheduled", remMs > 0, true);
        java.util.Calendar rc = java.util.Calendar.getInstance();
        rc.setTimeInMillis(remMs);
        check("v230 reminder hour 9",
                rc.get(java.util.Calendar.HOUR_OF_DAY), 9);
        check("v230 reminder minute 0",
                rc.get(java.util.Calendar.MINUTE), 0);
        java.util.Calendar expect = java.util.Calendar.getInstance();
        expect.setTimeInMillis(dueMs);
        expect.add(java.util.Calendar.DAY_OF_MONTH, -2);
        check("v230 reminder day = due - 2",
                rc.get(java.util.Calendar.DAY_OF_MONTH),
                expect.get(java.util.Calendar.DAY_OF_MONTH));
        check("v230 reminder month = due month",
                rc.get(java.util.Calendar.MONTH),
                expect.get(java.util.Calendar.MONTH));
        check("v230 reminder label", BillReminder.reminderLabel(remMs)
                .length() > 0, true);

        // ---- past / too-close / invalid due -> no reminder ----
        check("v230 past due -> -1", BillReminder.reminderTimeMs(
                System.currentTimeMillis() - 86400000L), -1L);
        check("v230 invalid due -> -1",
                BillReminder.reminderTimeMs(-1), -1L);
        java.util.Calendar soon = java.util.Calendar.getInstance();
        soon.add(java.util.Calendar.DAY_OF_MONTH, 1);
        soon.set(java.util.Calendar.HOUR_OF_DAY, 0);
        soon.set(java.util.Calendar.MINUTE, 0);
        soon.set(java.util.Calendar.SECOND, 0);
        soon.set(java.util.Calendar.MILLISECOND, 0);
        check("v230 due tomorrow -> -1 (reminder would be past)",
                BillReminder.reminderTimeMs(soon.getTimeInMillis()), -1L);

        // ---- PDF filename + pagination ----
        check("v230 pdf filename",
                BillPdf.fileName("02118240618604U", "20260920"),
                "bijli-bill-02118240618604-20260920.pdf");
        check("v230 pdf filename empty ref",
                BillPdf.fileName("", "20260920"),
                "bijli-bill-bill-20260920.pdf");
        check("v230 pages exact", BillPdf.pageCount(3000, 1000), 3);
        check("v230 pages round up", BillPdf.pageCount(3001, 1000), 4);
        check("v230 pages min 1", BillPdf.pageCount(0, 1000), 1);
        check("v230 pages bad height", BillPdf.pageCount(5000, 0), 1);

        // ---- BillActivity structure: no REPORT/PRINT, DOWNLOAD + ----
        // ---- reminder + chart wired ----
        byte[] bb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/BillActivity.java"));
        String bsrc = new String(bb,
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] vb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/BillViewerActivity.java"));
        String vsrc = new String(vb,
                java.nio.charset.StandardCharsets.UTF_8);
        check("v230 no report_btn in UI", !bsrc.contains("report_btn"),
                true);
        check("v230 no printBill", !bsrc.contains("printBill"), true);
        check("v230 no PrintManager", !bsrc.contains("PrintManager"),
                true);
        check("v230 no summary strip", !bsrc.contains("summaryStrip"),
                true);
        check("v369 viewer download", vsrc.contains("downloadBill("),
                true);
        check("v369 viewer pdf", vsrc.contains("PrintedPdfDocument"),
                true);
        check("v230 pdf save", bsrc.contains("savePdfToDownloads"), true);
        check("v369 viewer pdf filename", vsrc.contains("BijliBill-"),
                true);
        check("v240 no detailsCard", !bsrc.contains("detailsCard("),
                true);
        check("v240 no historySection",
                !bsrc.contains("historySection("), true);
        check("v240 no HistoryChartView",
                !bsrc.contains("HistoryChartView"), true);
        check("v240 no BillDetails", !bsrc.contains("BillDetails"), true);
        check("v240 no HistoryChart", !bsrc.contains("HistoryChart"),
                true);
        check("v300 bill pay card", bsrc.contains("payCard("), true);
        check("v300 bill no green hero", !bsrc.contains("heroCard("),
                true);
        check("v300 bill no late card",
                !bsrc.contains("lateCard("), true);
        check("v300 bill compact late", bsrc.contains("compactLate("),
                true);
        check("v369 view bill button",
                bsrc.contains("viewBillBtn("), true);
        check("v300 bill detail card", bsrc.contains("detailCard("), true);
        check("v300 bill holder card", bsrc.contains("holderCard("),
                true);
        // v3.1.1 (user request): PAY BILL sticky CTA removed entirely —
        // the button, its wiring, strings and fade drawable are gone.
        check("v311 bill sticky CTA removed",
                !bsrc.contains("stickyCta("), true);
        check("v311 bill no pay_bill string",
                !bsrc.contains("R.string.pay_bill"), true);
        check("v260 bill app bar", bsrc.contains("R.string.bill_title"), true);
        check("v260 bill days-left", bsrc.contains("due_days_left"), true);
        check("v300 bill no tier strings",
                !bsrc.contains("late_tier_value"), true);
        check("v300 bill no invented status",
                !bsrc.contains("Unpaid"), true);
        check("v260 bill fonts: amount 900",
                readSrc("java/com/hatch/bijlibill/Ds.java").contains(
                        "static void amount("), true);
        check("v260 bill fonts: mono",
                readSrc("java/com/hatch/bijlibill/Ds.java").contains(
                        "static void mono("), true);
        check("v240 summary fields",
                bsrc.contains("BillSummary.fields("), true);
        check("v369 viewer textZoom", vsrc.contains("setTextZoom(120)"),
                true);
        check("v369 viewer card", vsrc.contains("ds_card_24"),
                true);
        // v3.6.7: bill is now a zoomable IMAGE (user request) — WebView zoom
        // settings replaced by ZoomableImageView.
        check("v369 viewer activity",
                vsrc.contains("class BillViewerActivity"), true);
        check("v369 viewer back", vsrc.contains("ic_chev_right"), true);
        check("v367 pinch zoom", new java.io.File(
                "java/com/hatch/bijlibill/ZoomableImageView.java").exists(),
                true);
        check("v230 alarm manager", bsrc.contains("AlarmManager"), true);
        check("v230 exact alarm",
                bsrc.contains("setExactAndAllowWhileIdle"), true);
        check("v230 update-current (reschedule)",
                bsrc.contains("FLAG_UPDATE_CURRENT"), true);
        check("v230 receiver ref", bsrc.contains("ReminderReceiver"),
                true);
        check("v230 post-notifications",
                bsrc.contains("POST_NOTIFICATIONS"), true);
        check("v230 reminder prefs keys",
                bsrc.contains("BillReminder.KEY_TIME"), true);

        // ---- manifest: version + permissions + receiver ----
        // (v3.1.3: versionCode 27 / versionName 3.1.3 — net energy
        // basis for the Protected subsidy + category fix)
        byte[] mfb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("AndroidManifest.xml"));
        String man = new String(mfb,
                java.nio.charset.StandardCharsets.UTF_8);
        check("v360 versionCode 31",
                man.contains("android:versionCode=\"90\""), true);
        check("v360 versionName 3.5.0",
                man.contains("android:versionName=\"3.6.58\""), true);
        check("v230 manifest POST_NOTIFICATIONS",
                man.contains("android.permission.POST_NOTIFICATIONS"),
                true);
        check("v230 manifest SCHEDULE_EXACT_ALARM",
                man.contains("android.permission.SCHEDULE_EXACT_ALARM"),
                true);
        check("v230 manifest receiver",
                man.contains("android:name=\".ReminderReceiver\""), true);

        // ---- new strings: present in EN + Urdu ----
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] newKeys = {"download_btn", "download_saved",
                "download_failed", "reminder_rationale", "reminder_denied",
                "reminder_set", "reminder_channel", "reminder_notif_title",
                "reminder_notif_text", "meter_ocr_reading",
                "meter_why_andaza_q", "meter_why_andaza_a",
                "parse_partial_note"};
        for (String k : newKeys) {
            check("v230 key " + k + " EN", en.containsKey(k), true);
            check("v230 key " + k + " UR", ur.containsKey(k), true);
        }
        // Full key + format-arg parity (every %1$s in EN exists in UR).
        check("v230 parity key sets equal", en.keySet(), ur.keySet());
        java.util.List<String> fmtMismatch =
                new java.util.ArrayList<String>();
        for (String k : en.keySet()) {
            if (!fmtArgs(en.get(k)).equals(fmtArgs(ur.get(k)))) {
                fmtMismatch.add(k);
            }
        }
        check("v230 fmt-arg parity", fmtMismatch.size(), 0);
    }

    static void runV240Tests() throws Exception {
        // ---- decimal readings: parse + full calc ----
        checkClose("v240 parse 352.32",
                MeterCheckCalc.parseReadingD("352.32"), 352.32, 1e-9);
        checkClose("v240 parse 352,32",
                MeterCheckCalc.parseReadingD("352,32"), 352.32, 1e-9);
        checkClose("v240 parse 1,240 thousands",
                MeterCheckCalc.parseReadingD("1,240"), 1240.0, 1e-9);
        checkClose("v240 parse 1,234.56",
                MeterCheckCalc.parseReadingD("1,234.56"), 1234.56, 1e-9);
        checkClose("v240 parse arabic decimal",
                MeterCheckCalc.parseReadingD("352\u066B32"), 352.32,
                1e-9);
        checkClose("v240 parse 765.03",
                MeterCheckCalc.parseReadingD("765.03"), 765.03, 1e-9);
        checkClose("v240 parse null",
                MeterCheckCalc.parseReadingD(null), -1.0, 1e-9);
        checkClose("v240 parse garbage",
                MeterCheckCalc.parseReadingD("abc"), -1.0, 1e-9);

        // User's live case: base 330, new 352.32 -> 22.32 units.
        MeterCheckCalc.Result rd = MeterCheckCalc.compute(
                "330", "352.32", "Protected", "330", null, "5", "4280",
                12);
        check("v240 dec no error", rd.error, null);
        checkClose("v240 dec units", rd.unitsUsed, 22.32, 1e-9);
        checkClose("v240 dec base", rd.baseReading, 330.0, 1e-9);
        checkClose("v240 dec curr", rd.currReading, 352.32, 1e-9);
        // 22.32 * 10.54 (Protected) = 235.2528
        checkClose("v240 dec energy", rd.energyEstimate, 235.2528,
                0.01);
        checkClose("v240 dec total", rd.estimatedTotal,
                235.2528 * (1.0 + 335.0 / 4280.0), 0.05);
        checkClose("v240 dec remaining", rd.remaining, 77.68, 1e-9);
        check("v240 dec protectedCliff", rd.protectedCliff, true);
        check("v240 dec projection", rd.projectedMonthUnits,
                (int) Math.round(22.32 / 12.0 * 30.0));

        // Validation still holds on decimals.
        MeterCheckCalc.Result rb = MeterCheckCalc.compute(
                "352.32", "330", "Protected", null, null, null, null, 12);
        check("v240 dec order error", rb.error,
                MeterCheckCalc.ERR_READING_ORDER);

        // ---- display helpers ----
        check("v240 fmt 22.0", MeterCheckCalc.fmtUnits(22.0), "22");
        check("v240 fmt 22.5", MeterCheckCalc.fmtUnits(22.5), "22.5");
        check("v240 fmt 22.32", MeterCheckCalc.fmtUnits(22.32), "22.32");
        check("v240 fmt 0.1+0.2", MeterCheckCalc.fmtUnits(0.1 + 0.2),
                "0.3");
        check("v240 pct 18", MeterCheckCalc.fmtPct(18.0), "18");
        check("v240 pct 17.6", MeterCheckCalc.fmtPct(17.647), "17.6");

        // ---- breakdown: rows == the estimator's real terms ----
        java.util.List<MeterCheckCalc.BreakRow> rows =
                MeterCheckCalc.breakdown(rd);
        // ENERGY, TAXES, FPA, TOTAL (GST was null -> skipped).
        check("v240 rows count", rows.size(), 4);
        check("v240 row0 ENERGY", rows.get(0).kind,
                MeterCheckCalc.BreakKind.ENERGY);
        checkClose("v240 row0 aux units", rows.get(0).aux, 22.32, 1e-9);
        checkClose("v240 row0 == energyEstimate", rows.get(0).amount,
                rd.energyEstimate, 1e-9);
        MeterCheckCalc.BreakRow taxRow = null;
        MeterCheckCalc.BreakRow fpaRow = null;
        for (MeterCheckCalc.BreakRow br : rows) {
            if (br.kind == MeterCheckCalc.BreakKind.TAXES) taxRow = br;
            if (br.kind == MeterCheckCalc.BreakKind.FPA) fpaRow = br;
        }
        check("v240 TAXES row present", taxRow != null, true);
        checkClose("v240 TAXES pct", taxRow.aux, 330.0 / 4280.0 * 100.0,
                1e-9);
        checkClose("v240 TAXES amount", taxRow.amount,
                rd.energyEstimate * 330.0 / 4280.0, 1e-9);
        check("v240 FPA row present", fpaRow != null, true);
        checkClose("v240 FPA pct", fpaRow.aux, 5.0 / 4280.0 * 100.0,
                1e-9);
        double sum = 0;
        for (MeterCheckCalc.BreakRow br : rows) {
            if (br.kind != MeterCheckCalc.BreakKind.TOTAL) {
                sum += br.amount;
            }
        }
        checkClose("v240 rows sum == total", sum, rd.estimatedTotal,
                1e-6);
        check("v240 last row TOTAL",
                rows.get(rows.size() - 1).kind,
                MeterCheckCalc.BreakKind.TOTAL);
        checkClose("v240 total == estimatedTotal",
                rows.get(rows.size() - 1).amount, rd.estimatedTotal,
                1e-9);

        // Fallback (no tax fields): TAX_FALLBACK at 18%.
        MeterCheckCalc.Result rf = MeterCheckCalc.compute(
                "100", "150", "Protected", null, null, null, null, 12);
        java.util.List<MeterCheckCalc.BreakRow> rowsF =
                MeterCheckCalc.breakdown(rf);
        check("v240 fallback rows", rowsF.size(), 3);
        check("v240 fallback kind", rowsF.get(1).kind,
                MeterCheckCalc.BreakKind.TAX_FALLBACK);
        checkClose("v240 fallback pct", rowsF.get(1).aux, 18.0, 1e-9);
        checkClose("v240 fallback amount", rowsF.get(1).amount,
                rf.energyEstimate * 0.18, 1e-9);
        check("v240 breakdown on error",
                MeterCheckCalc.breakdown(rb).size(), 0);
        check("v240 breakdown on null",
                MeterCheckCalc.breakdown(null).size(), 0);

        // ---- BillSummary: slim field mapping ----
        LescoBillFetcher.BillInfo info = new LescoBillFetcher.BillInfo();
        info.consumerName = "ALI KHAN";
        info.referenceNumber = "02118240618604";
        info.amount = "2,065";
        info.dueDate = "22 SEP 26";
        info.lpSurcharge = "173";
        info.amountAfterDueDate = "2,238";
        info.units = "113";
        java.util.List<BillSummary.Field> fs = BillSummary.fields(info);
        check("v240 sum fields count", fs.size(), 6);
        check("v240 sum name", BillSummary.value(fs, "name"), "ALI KHAN");
        check("v240 sum payable", BillSummary.value(fs, "payable"),
                "2,065");
        check("v240 sum due", BillSummary.value(fs, "due"), "22 SEP 26");
        check("v240 sum lateSurcharge",
                BillSummary.value(fs, "lateSurcharge"), "173");
        check("v240 sum lateTotal", BillSummary.value(fs, "lateTotal"),
                "2,238");
        check("v240 sum units", BillSummary.value(fs, "units"), "113");
        // Name falls back to the reference number — never blank.
        LescoBillFetcher.BillInfo info2 = new LescoBillFetcher.BillInfo();
        info2.referenceNumber = "02118240618604";
        check("v240 name fallback ref",
                BillSummary.value(BillSummary.fields(info2), "name"),
                "02118240618604");
        check("v240 no name when blank",
                BillSummary.value(
                        BillSummary.fields(new LescoBillFetcher.BillInfo()),
                        "name"),
                null);
        LescoBillFetcher.BillInfo info3 = new LescoBillFetcher.BillInfo();
        info3.consumerName = "X";
        check("v240 sparse count", BillSummary.fields(info3).size(), 1);
        check("v240 null info", BillSummary.fields(null).size(), 0);

        // ---- deleted classes are really gone ----
        check("v240 BillDetails.java deleted",
                !java.nio.file.Files.exists(java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/BillDetails.java")),
                true);
        check("v240 HistoryChart.java deleted",
                !java.nio.file.Files.exists(java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/HistoryChart.java")),
                true);
        check("v240 HistoryChartView.java deleted",
                !java.nio.file.Files.exists(java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/HistoryChartView.java")),
                true);

        // ---- MeterCheckActivity: decimal input + breakdown wired ----
        byte[] mb = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "java/com/hatch/bijlibill/MeterCheckActivity.java"));
        String msrc = new String(mb,
                java.nio.charset.StandardCharsets.UTF_8);
        check("v240 decimal input",
                msrc.contains("TYPE_NUMBER_FLAG_DECIMAL"), true);
        check("v240 breakdown wired", msrc.contains("addBreakdown("),
                true);
        check("v240 fmtUnits used", msrc.contains("fmtUnits("), true);
        check("v260 andaza label rows",
                msrc.contains("andaza_lbl_energy"), true);
        check("v260 slab card wired",
                msrc.contains("R.string.slab_card_title"), true);
        check("v300 slab card separate", msrc.contains("slabCard("),
                true);
        check("v300 receipt always visible",
                msrc.contains("tafseelCard(")
                && !msrc.contains("body.setVisibility(View.GONE)"),
                true);
        check("v300 warn strip", msrc.contains("warnStrip("), true);
        check("v300 plain receipt amounts",
                msrc.contains("moneyPlain("), true);
        check("v260 steps wired",
                msrc.contains("R.string.step_record"), true);
        check("v240 how title", msrc.contains("andaza_how_title"), true);

        // ---- new strings: present in EN + Urdu, fmt-arg parity ----
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] newKeys = {"bill_sum_payable", "bill_sum_due",
                "bill_sum_late", "bill_sum_late_full", "bill_sum_late_only",
                "bill_sum_after_only", "bill_sum_units", "andaza_how_title",
                "andaza_row_energy", "andaza_row_taxes", "andaza_row_gst",
                "andaza_row_fpa", "andaza_row_tax_fallback",
                "andaza_row_total"};
        for (String k : newKeys) {
            check("v240 key " + k + " EN", en.containsKey(k), true);
            check("v240 key " + k + " UR", ur.containsKey(k), true);
            check("v240 key " + k + " UR non-empty",
                    ur.get(k) != null && !ur.get(k).trim().isEmpty(),
                    true);
        }
        String[] v260keys = {"step_bill_loaded", "step_record",
                "step_analyze", "step_estimate", "nav_back",
                "andaza_lbl_energy", "andaza_lbl_taxes", "andaza_lbl_gst",
                "andaza_lbl_fpa", "andaza_lbl_tax_fallback",
                "andaza_lbl_total", "meter_readings_pair",
                "meter_units_since", "slab_card_title", "slab_card_suffix"};
        for (String k : v260keys) {
            check("v260 key " + k + " EN", en.containsKey(k), true);
            check("v260 key " + k + " UR", ur.containsKey(k), true);
            check("v260 key " + k + " UR non-empty",
                    ur.get(k) != null && !ur.get(k).trim().isEmpty(),
                    true);
        }
        check("v240 parity key sets equal", en.keySet(), ur.keySet());
        java.util.List<String> fmtMismatch =
                new java.util.ArrayList<String>();
        for (String k : en.keySet()) {
            if (!fmtArgs(en.get(k)).equals(fmtArgs(ur.get(k)))) {
                fmtMismatch.add(k);
            }
        }
        check("v240 fmt-arg parity", fmtMismatch.size(), 0);
    }

    // ---- v2.5.0 tests: multi-disco, Mere Meter, default-EN,
    //      UNIT CHECK rename, UI structure ----

    /** In-memory SavedMeters.Storage for headless tests. */
    static final class MemStore implements SavedMeters.Storage {
        String json;
        @Override public String load() { return json; }
        @Override public void save(String j) { json = j; }
    }

    static String readSrc(String rel) throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(rel));
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void runV250Tests() throws Exception {
        // ---- Disco: all 9 PITC mappings + K-Electric disabled ----
        check("v250 disco count", Disco.values().length, 10);
        check("v250 lesco url", Disco.LESCO.baseUrl(),
                "https://bill.pitc.com.pk/lescobill");
        check("v250 iesco url", Disco.IESCO.baseUrl(),
                "https://bill.pitc.com.pk/iescobill");
        check("v250 gepco url", Disco.GEPCO.baseUrl(),
                "https://bill.pitc.com.pk/gepcobill");
        check("v250 fesco url", Disco.FESCO.baseUrl(),
                "https://bill.pitc.com.pk/fescobill");
        check("v250 mepco url", Disco.MEPCO.baseUrl(),
                "https://bill.pitc.com.pk/mepcobill");
        check("v250 pesco url", Disco.PESCO.baseUrl(),
                "https://bill.pitc.com.pk/pescobill");
        check("v250 hesco url", Disco.HESCO.baseUrl(),
                "https://bill.pitc.com.pk/hescobill");
        check("v250 sepco url", Disco.SEPCO.baseUrl(),
                "https://bill.pitc.com.pk/sepcobill");
        check("v250 qesco url", Disco.QESCO.baseUrl(),
                "https://bill.pitc.com.pk/qescobill");
        check("v250 ke comingSoon", Disco.KELECTRIC.comingSoon, true);
        check("v250 ke no pitc path", Disco.KELECTRIC.pitcPath, null);
        check("v250 lesco not comingSoon", Disco.LESCO.comingSoon, false);
        check("v250 fetchable count", Disco.fetchable().length, 9);
        boolean keFetchable = false;
        for (Disco d : Disco.fetchable()) {
            if (d == Disco.KELECTRIC) keFetchable = true;
        }
        check("v250 ke not fetchable", keFetchable, false);
        java.util.Set<String> codes = new java.util.HashSet<String>();
        for (Disco d : Disco.values()) codes.add(d.code);
        check("v250 distinct codes", codes.size(), 10);
        check("v250 fromCode null -> lesco", Disco.fromCode(null),
                Disco.LESCO);
        check("v250 fromCode unknown -> lesco", Disco.fromCode("nope"),
                Disco.LESCO);
        check("v250 fromCode iesco", Disco.fromCode("iesco"),
                Disco.IESCO);
        check("v250 http fallback base", Disco.GEPCO.baseUrlHttp(),
                "http://bill.pitc.com.pk/gepcobill");
        check("v250 general url",
                Disco.PESCO.generalUrl("04123456789012", ""),
                "https://bill.pitc.com.pk/pescobill"
                        + "/general?refno=04123456789012");
        check("v250 general url suffix",
                Disco.HESCO.generalUrl("04123456789012", "U"),
                "https://bill.pitc.com.pk/hescobill"
                        + "/general?refno=04123456789012U");

        // ---- fetchBillPageWith hits the disco's own URL ----
        String digits = "06113530462901";
        String fullPage = read("lesco-bill-fullpage-SYNTHETIC.html");
        FakeTransport t = new FakeTransport();
        t.add(200, fakeFormHtml("T"),
                "https://bill.pitc.com.pk/iescobill",
                "Set-Cookie", FAKE_COOKIE);
        t.add(200, fullPage,
                "https://bill.pitc.com.pk/iescobill/general?refno="
                        + digits);
        LescoBillFetcher.BillPage pg =
                LescoBillFetcher.fetchBillPageWith(t, digits + "U",
                        Disco.IESCO);
        check("v250 iesco fetchPath", pg.fetchPath, "direct");
        check("v250 iesco seed url", t.seenUrls.get(0),
                "https://bill.pitc.com.pk/iescobill");
        check("v250 iesco direct url", t.seenUrls.get(1),
                "https://bill.pitc.com.pk/iescobill/general?refno="
                        + digits);
        check("v250 iesco exchanges", t.exchanges, 2);
        // null disco -> LESCO (legacy two-arg stays LESCO)
        FakeTransport tl = new FakeTransport();
        tl.add(200, fakeFormHtml("T"),
                "https://bill.pitc.com.pk/lescobill",
                "Set-Cookie", FAKE_COOKIE);
        tl.add(200, fullPage,
                "https://bill.pitc.com.pk/lescobill/general?refno="
                        + digits);
        LescoBillFetcher.fetchBillPageWith(tl, digits + "U", null);
        check("v250 null disco seed url", tl.seenUrls.get(0),
                "https://bill.pitc.com.pk/lescobill");

        // ---- customer-ID fetch wired to the disco's form (best-effort
        //      mode=custid; NOT live-verified, see README) ----
        FakeTransport tc = new FakeTransport();
        tc.add(200, fakeFormHtml("T"),
                "https://bill.pitc.com.pk/mepcobill",
                "Set-Cookie", FAKE_COOKIE);
        tc.add(200, "<html><body>Record Not Found</body></html>",
                "https://bill.pitc.com.pk/mepcobill");
        LescoBillFetcher.BillErrorException notFound = null;
        try {
            LescoBillFetcher.fetchByCustomerIdWith(tc, "7654321",
                    Disco.MEPCO);
        } catch (LescoBillFetcher.BillErrorException e) {
            notFound = e;
        }
        check("v250 cid mepco not-found kind",
                notFound != null ? notFound.kind : null,
                FetchKind.NOT_FOUND);
        check("v250 cid mepco get url", tc.seenUrls.get(0),
                "https://bill.pitc.com.pk/mepcobill");
        check("v250 cid mepco post url", tc.seenUrls.get(1),
                "https://bill.pitc.com.pk/mepcobill");
        check("v250 cid mepco post mode",
                tc.seenBodies.get(1).contains("rbSearchByList=custid"),
                true);
        check("v250 cid mepco post id",
                tc.seenBodies.get(1).contains("searchTextBox=7654321"),
                true);
        boolean cidBad = false;
        try {
            LescoBillFetcher.fetchByCustomerIdWith(tc, "123",
                    Disco.MEPCO);
        } catch (IllegalArgumentException e) {
            cidBad = true;
        }
        check("v250 cid invalid rejected", cidBad, true);
        check("v250 cid invalid no fetch", tc.exchanges, 2);

        // ---- orchestrator with disco: cid path + ref path ----
        FakeTransport to = new FakeTransport();
        to.add(200, fakeFormHtml("T"),
                "https://bill.pitc.com.pk/fescobill",
                "Set-Cookie", FAKE_COOKIE);
        to.add(200, fakeBillHtml(),
                "https://bill.pitc.com.pk/fescobill");
        LescoBillFetcher.BillPage po =
                LescoBillFetcher.FetchOrchestrator.fetchOrThrow(to,
                        new LescoBillFetcher.FetchOrchestrator
                                .OnlineChecker() {
                            @Override public boolean isOnline() {
                                return true;
                            }
                        }, "7654321", "cid", Disco.FESCO);
        check("v250 orch cid disco parseOk", po.parseOk, true);
        check("v250 orch cid disco amount", po.info.amount, "1234.56");
        check("v250 orch cid disco post url", to.seenUrls.get(1),
                "https://bill.pitc.com.pk/fescobill");
        FakeTransport tr = new FakeTransport();
        tr.add(200, fakeFormHtml("T"),
                "https://bill.pitc.com.pk/qescobill",
                "Set-Cookie", FAKE_COOKIE);
        tr.add(200, fullPage,
                "https://bill.pitc.com.pk/qescobill/general?refno="
                        + digits);
        LescoBillFetcher.BillPage pr =
                LescoBillFetcher.FetchOrchestrator.fetchOrThrow(tr,
                        new LescoBillFetcher.FetchOrchestrator
                                .OnlineChecker() {
                            @Override public boolean isOnline() {
                                return true;
                            }
                        }, digits + "U", "ref", Disco.QESCO);
        check("v250 orch ref disco path", pr.fetchPath, "direct");
        check("v250 orch ref disco url", tr.seenUrls.get(1),
                "https://bill.pitc.com.pk/qescobill/general?refno="
                        + digits);

        // ---- browser fallback URL per disco ----
        check("v250 browser ref disco",
                LescoBillFetcher.browserFallbackUrl(digits + "U", "ref",
                        Disco.SEPCO),
                "https://bill.pitc.com.pk/sepcobill/general?refno="
                        + digits);
        check("v250 browser cid disco",
                LescoBillFetcher.browserFallbackUrl("7654321", "cid",
                        Disco.QESCO),
                "https://bill.pitc.com.pk/qescobill");
        check("v250 browser ke -> lesco",
                LescoBillFetcher.browserFallbackUrl(digits, "ref",
                        Disco.KELECTRIC),
                "https://bill.pitc.com.pk/lescobill/general?refno="
                        + digits);
        check("v250 browser legacy lesco",
                LescoBillFetcher.browserFallbackUrl(digits, "ref"),
                "https://bill.pitc.com.pk/lescobill/general?refno="
                        + digits);

        // ---- SavedMeters: create/read/update/delete/dedupe/cap ----
        MemStore ms = new MemStore();
        check("v250 meters empty", SavedMeters.load(ms).size(), 0);
        SavedMeters.add(ms, "lescobill", "ref", digits, "Ali Raza",
                1000L);
        java.util.List<SavedMeters.Meter> one =
                SavedMeters.load(ms);
        check("v250 meters one", one.size(), 1);
        check("v250 meters disco", one.get(0).disco, "lescobill");
        check("v250 meters idType", one.get(0).idType, "ref");
        check("v250 meters idValue", one.get(0).idValue, digits);
        check("v250 meters holder", one.get(0).holderName, "Ali Raza");
        // same (disco,idValue) again -> dedupe, new name wins, still 1
        SavedMeters.add(ms, "lescobill", "ref", digits, "Ali Raza Jr",
                2000L);
        java.util.List<SavedMeters.Meter> dedup =
                SavedMeters.load(ms);
        check("v250 meters dedupe size", dedup.size(), 1);
        check("v250 meters dedupe name", dedup.get(0).holderName,
                "Ali Raza Jr");
        // same idValue on ANOTHER disco -> separate row
        SavedMeters.add(ms, "iescobill", "ref", digits, "Ali Iesco",
                3000L);
        check("v250 meters other disco separate",
                SavedMeters.load(ms).size(), 2);
        // newest first
        check("v250 meters newest first",
                SavedMeters.load(ms).get(0).disco, "iescobill");
        // delete
        SavedMeters.remove(ms, "lescobill", digits);
        check("v250 meters after delete", SavedMeters.load(ms).size(),
                1);
        check("v250 meters remaining disco",
                SavedMeters.load(ms).get(0).disco, "iescobill");
        SavedMeters.remove(ms, "lescobill", digits); // missing: no-op
        check("v250 meters delete missing no-op",
                SavedMeters.load(ms).size(), 1);
        // cap at 20
        MemStore capStore = new MemStore();
        for (int i = 0; i < 25; i++) {
            SavedMeters.add(capStore, "lescobill", "ref",
                    String.format("%014d", i), "H" + i, 1000L + i);
        }
        check("v250 meters cap 20", SavedMeters.load(capStore).size(),
                20);
        // blank holder name -> idValue used as display name
        MemStore blankName = new MemStore();
        SavedMeters.add(blankName, "lescobill", "ref", digits, "  ",
                1000L);
        check("v250 meters blank name fallback",
                SavedMeters.load(blankName).get(0).holderName, digits);
        // masked ids
        check("v250 mask long", SavedMeters.maskId("06113530462901"),
                "0611********01");
        check("v250 mask short", SavedMeters.maskId("7654321"),
                "7654*21");
        check("v250 mask tiny", SavedMeters.maskId("12345"), "****");
        check("v250 mask null", SavedMeters.maskId(null), "");
        // corrupt storage -> empty, no crash
        MemStore bad = new MemStore();
        bad.json = "garbage[[[";
        check("v250 corrupt empty", SavedMeters.load(bad).size(), 0);
        // legacy migration: items are [name, mode, value]
        MemStore mig = new MemStore();
        java.util.List<String[]> legacy =
                new java.util.ArrayList<String[]>();
        legacy.add(new String[]{"Old Name", "ref", "06113530462901"});
        legacy.add(new String[]{"bad-row"});
        SavedMeters.migrateLegacy(mig, legacy);
        java.util.List<SavedMeters.Meter> migged =
                SavedMeters.load(mig);
        check("v250 migrate size", migged.size(), 1);
        check("v250 migrate name", migged.get(0).holderName,
                "Old Name");
        check("v250 migrate disco", migged.get(0).disco,
                Disco.LESCO.code);
        check("v250 migrate idType", migged.get(0).idType, "ref");

        // ---- default language is English ----
        check("v250 default lang EN", LangPolicy.defaultLang(), "en");

        // ---- cache-key contract: disco:mode:ref (used by BillActivity,
        //      HomeActivity and MeterCheckActivity alike) ----
        String k1 = Disco.IESCO.code + ":ref:" + digits;
        String k2 = Disco.LESCO.code + ":ref:" + digits;
        check("v250 cache key format", k1, "iesco:ref:" + digits);
        check("v250 cache keys differ by disco", k1.equals(k2), false);

        // ---- new string keys: EN/UR parity + non-empty UR ----
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] newKeys = {"disco_label", "kelectric_soon",
                "meters_title", "meters_empty"};
        for (String k : newKeys) {
            check("v250 key " + k + " EN", en.containsKey(k), true);
            check("v250 key " + k + " UR", ur.containsKey(k), true);
            check("v250 key " + k + " UR non-empty",
                    ur.get(k) != null && !ur.get(k).trim().isEmpty(),
                    true);
        }
        check("v250 ur disco_label", ur.get("disco_label"),
                "بجلی کمپنی");
        check("v250 ur meters_title", ur.get("meters_title"),
                "میرے میٹر");
        check("v250 parity key sets equal", en.keySet(), ur.keySet());
        java.util.List<String> fmtMismatch =
                new java.util.ArrayList<String>();
        for (String k : en.keySet()) {
            if (!fmtArgs(en.get(k)).equals(fmtArgs(ur.get(k)))) {
                fmtMismatch.add(k);
            }
        }
        check("v250 fmt-arg parity", fmtMismatch.size(), 0);

        // ---- UI structure (headless source scans) ----
        String splash = readSrc(
                "java/com/hatch/bijlibill/SplashActivity.java");
        check("v260 splash centered", splash.contains(
                "Gravity.CENTER"), true);
        check("v260 splash no weights", !splash.contains("1f)"), true);
        check("v260 splash no fixed px icon",
                !splash.contains("Ui.px("), true);
        // v2.6.0 design system: splash uses the new drawables + fonts
        check("v260 splash bg radial", splash.contains(
                "R.drawable.splash_bg"), true);
        // v3.6.34: splash now uses real app icon (not squircle/glow)
        // v3.6.36: transparent splash_icon drawable
        check("v3634 splash real icon", splash.contains(
                "R.drawable.splash_icon"), true);
        check("v3637 splash no float anim", splash.contains(
                "floatAnim"), false);
        check("v260 splash urdu font", splash.contains(
                "Fonts.urdu(this)"), true);
        check("v260 splash title string", splash.contains(
                "R.string.splash_title"), true);
        String sbg = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("res/drawable/splash_bg.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        check("v300 splash_bg linear gradient",
                !sbg.contains("android:type=\"radial\"")
                && sbg.contains("#0D5A43")
                && sbg.contains("#04271E"), true);
        check("v300 splash_bg no dot pattern",
                !sbg.contains("dot_pattern"), true);
        check("v260 fonts bundled",
                new java.io.File("assets/fonts/Archivo-ExtraBold.ttf").exists()
                && new java.io.File(
                        "assets/fonts/PlusJakartaSans-Bold.ttf").exists()
                && new java.io.File(
                        "assets/fonts/Archivo-Black.ttf").exists(), // 900
                true);
        String home = readSrc(
                "java/com/hatch/bijlibill/HomeActivity.java");
        check("v260 home disco chips", home.contains(
                "R.drawable.ds_chip_on"), true);
        check("v260 home passes disco", home.contains(
                "BillActivity.EXTRA_DISCO"), true);
        // v2.6.0 design system: bottom nav + lang toggle + paste
        check("v260 home bottom nav", home.contains(
                "Ds.bottomNav(this, Ds.TAB_HOME)"), true);
        check("v260 home lang toggle", home.contains(
                "Ds.langToggle(this)"), true);
        // v3.0.0: visible PASTE button removed by design; native
        // EditText long-press paste remains.
        check("v300 home no paste button",
                !home.contains("R.string.paste"), true);
        check("v300 home native paste fields",
                home.contains("new android.widget.EditText(this)"),
                true);
        check("v260 home meter cards", home.contains(
                "MeterUi.card("), true);
        check("v260 home keeps fetch logic", home.contains(
                "LescoBillFetcher.isValidReference"), true);
        check("v260 home keeps metercheck logic", home.contains(
                "KEY_LAST_DISCO"), true);
        String bills = readSrc(
                "java/com/hatch/bijlibill/BillsActivity.java");
        check("v260 bills activity", bills.contains(
                "MeterUi.card(") && bills.contains("SavedMeters.load"),
                true);
        check("v260 bills in manifest",
                new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("AndroidManifest.xml")),
                        java.nio.charset.StandardCharsets.UTF_8)
                        .contains("android:name=\".BillsActivity\""),
                true);
        check("v250 home no manual save field",
                !home.contains("saveName"), true);
        check("v250 home meters list", home.contains(
                "R.string.meters_title"), true);
        // v2.6.0 design: home builds its own ScrollView directly.
        check("v250 home scrolls", home.contains(
                "new ScrollView(this)"), true);
        check("v250 home auto-save label removed",
                !home.contains("R.string.saved_title"), true);
        String ui = readSrc("java/com/hatch/bijlibill/Ui.java");
        check("v250 ui no px units",
                !ui.contains("COMPLEX_UNIT_PX"), true);
        check("v250 ui chip helper", ui.contains(
                "public static TextView chip("), true);
        String card = readSrc("res/drawable/card_bg.xml");
        check("v250 card 12dp corners",
                card.contains("android:radius=\"12dp\""), true);
        String bill = readSrc(
                "java/com/hatch/bijlibill/BillActivity.java");
        check("v250 bill disco cache key", bill.contains(
                "disco.code + \":\" + mode + \":\" + ref"), true);
        check("v250 bill auto-save", bill.contains(
                "autoSaveMeter(") && bill.contains("SavedMeters.add("),
                true);
        String mc = readSrc(
                "java/com/hatch/bijlibill/MeterCheckActivity.java");
        check("v250 meter disco cache key", mc.contains(
                "disco.code + \":\" + mode + \":\" + ref"), true);
        check("v250 meter renamed title comment",
                mc.contains("یونٹ چیک"), true);
        String loc = readSrc(
                "java/com/hatch/bijlibill/LocaleHelper.java");
        check("v250 locale default EN policy",
                loc.contains("LangPolicy.defaultLang()"), true);
    }

    // ---- v3.1.0 tests: BillMirrorCalc + units-first UI ----

    /** Round to 2dp for money comparisons. */
    static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static LescoBillFetcher.BillInfo mirrorBill(String units, String elec,
            String fpa, String gst, String taxes, String subsidy,
            String category) {
        LescoBillFetcher.BillInfo b = new LescoBillFetcher.BillInfo();
        b.units = units;
        b.electricityCharges = elec;
        b.fpa = fpa;
        b.gst = gst;
        b.taxes = taxes;
        b.subsidy = subsidy;
        b.category = category;
        return b;
    }

    static BillMirrorCalc.MirrorRow rowByLabel(
            BillMirrorCalc.MirrorResult m, String label) {
        for (BillMirrorCalc.MirrorRow r : m.rows) {
            if (r.label.equals(label)) return r;
        }
        return null;
    }

    static void runV310Tests() throws Exception {
        // ---- tariffCat mirrors MeterCheckCalc.compute() ----
        check("v310 cat protected",
                BillMirrorCalc.tariffCat("Protected Domestic"),
                Tariff.PROTECTED);
        check("v310 cat lifeline",
                BillMirrorCalc.tariffCat("Lifeline"),
                Tariff.LIFELINE);
        check("v310 cat null",
                BillMirrorCalc.tariffCat(null),
                Tariff.NON_PROTECTED);
        check("v310 cat other",
                BillMirrorCalc.tariffCat("RESIDENTIAL"),
                Tariff.NON_PROTECTED);

        // ---- PER-UNIT: FPA 100 on 200 bill units; 50 est units ----
        LescoBillFetcher.BillInfo b1 = mirrorBill("200", "2000", "100",
                null, null, null, "RESIDENTIAL");
        BillMirrorCalc.MirrorResult m1 = BillMirrorCalc.mirror(b1, 50.0,
                1000.0, BillMirrorCalc.tariffCat(b1.category));
        check("v310 m1 usable", m1.usable, true);
        check("v310 m1 rows", m1.rows.size(), 1);
        BillMirrorCalc.MirrorRow fpa1 = rowByLabel(m1, "FPA");
        check("v310 m1 fpa kind", fpa1.kind,
                BillMirrorCalc.LineKind.PER_UNIT);
        check("v310 m1 fpa est", r2(fpa1.estAmount), 25.0);
        check("v310 m1 fpa aux", r2(fpa1.aux), 0.5);
        // v3.1.2 calibration: k = 2000/5135 = 0.38948 in [0.25,4.0]
        // -> energy 1000 x k = 389.48; total = 389.48 + 25
        check("v310 m1 total", r2(m1.estimatedTotal), 414.48);

        // ---- PERCENTAGE: GST 180 on base 1000 -> 18% ----
        LescoBillFetcher.BillInfo b2 = mirrorBill("100", "1000", null,
                "180", null, null, "RESIDENTIAL");
        BillMirrorCalc.MirrorResult m2 = BillMirrorCalc.mirror(b2, 50.0,
                500.0, BillMirrorCalc.tariffCat(b2.category));
        check("v310 m2 rows", m2.rows.size(), 1);
        BillMirrorCalc.MirrorRow gst2 = rowByLabel(m2, "GST");
        check("v310 m2 gst kind", gst2.kind,
                BillMirrorCalc.LineKind.PERCENTAGE);
        check("v310 m2 gst est", r2(gst2.estAmount), 40.11);
        check("v310 m2 gst aux", r2(gst2.aux), 18.0);
        // v3.1.2 calibration: k = 1000/2244 = 0.44563 -> energy
        // 500 x k = 222.82; GST 18% of 222.82 = 40.11
        check("v310 m2 total", r2(m2.estimatedTotal), 262.92);

        // ---- FIXED: TV Fee + Fixed Charges as-is ----
        LescoBillFetcher.BillInfo b3 = mirrorBill("100", "1000", null,
                null, null, null, "RESIDENTIAL");
        b3.extraLines.add(new String[]{"TV Fee", "35"});
        b3.extraLines.add(new String[]{"Fixed Charges", "120.50"});
        BillMirrorCalc.MirrorResult m3 = BillMirrorCalc.mirror(b3, 50.0,
                500.0, BillMirrorCalc.tariffCat(b3.category));
        check("v310 m3 rows", m3.rows.size(), 2);
        check("v310 m3 tv kind", rowByLabel(m3, "TV Fee").kind,
                BillMirrorCalc.LineKind.FIXED);
        check("v310 m3 tv est", r2(rowByLabel(m3, "TV Fee").estAmount),
                35.0);
        check("v310 m3 fixedTotal", r2(m3.fixedTotal), 155.5);
        // v3.1.2 calibration: energy 500 x 0.44563 = 222.82
        check("v310 m3 total", r2(m3.estimatedTotal), 378.32);

        // ---- classification sweep + percentage base math ----
        LescoBillFetcher.BillInfo b4 = mirrorBill("100", "1000", null,
                null, null, null, "RESIDENTIAL");
        b4.extraLines.add(new String[]{"Income Tax", "75"});
        b4.extraLines.add(new String[]{"Meter Rent", "20"});
        b4.extraLines.add(new String[]{"NJ Surcharge", "30"});
        b4.extraLines.add(new String[]{"Service Charges", "10"});
        BillMirrorCalc.MirrorResult m4 = BillMirrorCalc.mirror(b4, 50.0,
                500.0, BillMirrorCalc.tariffCat(b4.category));
        check("v310 m4 rows", m4.rows.size(), 4);
        check("v310 m4 itax kind", rowByLabel(m4, "Income Tax").kind,
                BillMirrorCalc.LineKind.PERCENTAGE);
        check("v310 m4 rent kind", rowByLabel(m4, "Meter Rent").kind,
                BillMirrorCalc.LineKind.FIXED);
        check("v310 m4 nj kind", rowByLabel(m4, "NJ Surcharge").kind,
                BillMirrorCalc.LineKind.PER_UNIT);
        check("v310 m4 svc kind", rowByLabel(m4, "Service Charges").kind,
                BillMirrorCalc.LineKind.FIXED);
        // est base = 222.82 + 30 fixed + 15 NJ = 267.82;
        // 75/1030 x 267.82
        check("v310 m4 itax est",
                r2(rowByLabel(m4, "Income Tax").estAmount), 19.50);
        check("v310 m4 itax aux",
                r2(rowByLabel(m4, "Income Tax").aux), 7.28);
        check("v310 m4 total", r2(m4.estimatedTotal), 287.32);

        // ---- one-time items never mirrored ----
        LescoBillFetcher.BillInfo b5 = mirrorBill("100", "1000", "50",
                "100", null, null, "RESIDENTIAL");
        b5.extraLines.add(new String[]{"Arrears", "500"});
        b5.extraLines.add(new String[]{"Late Payment Surcharge", "60"});
        b5.extraLines.add(new String[]{"Installment", "200"});
        b5.extraLines.add(new String[]{"W.E Credit", "150"});
        b5.arrears = "500";
        BillMirrorCalc.MirrorResult m5 = BillMirrorCalc.mirror(b5, 50.0,
                500.0, BillMirrorCalc.tariffCat(b5.category));
        check("v310 m5 rows", m5.rows.size(), 2);
        check("v310 m5 no arrears", rowByLabel(m5, "Arrears"), null);
        check("v310 m5 excluded", m5.excludedCount, 5);
        // v3.1.2 calibration: energy 222.82; FPA 25; GST
        // 100/1050 x 247.82 = 23.60
        check("v310 m5 total", r2(m5.estimatedTotal), 271.42);

        // ---- SUBSIDY: v3.1.3 nets it into the energy basis ----
        // (Total 2244 - Subsidy 1190 = Net 1054; k = 1054/2244;
        // energy 1122 x k = 527.0; no subsidy row — its value is
        // baked into the net basis. Same total as the old
        // gross-minus-subsidy presentation.)
        LescoBillFetcher.BillInfo b6 = mirrorBill("100", "2244", null,
                null, null, "1190", "RESIDENTIAL");
        BillMirrorCalc.MirrorResult m6 = BillMirrorCalc.mirror(b6, 50.0,
                1122.0, BillMirrorCalc.tariffCat(b6.category));
        check("v310 m6 netted", m6.subsidyNetted, true);
        checkClose("v310 m6 energy net", m6.energyEstimate, 527.0,
                0.01);
        check("v310 m6 no subsidy row", rowByLabel(m6, "Subsidy"),
                null);
        check("v310 m6 rows", m6.rows.size(), 0);
        check("v310 m6 not usable", m6.usable, false);

        // ---- SUBSIDY guard: bill at NEPRA basis, estimator at
        //      protected basis -> skip (no double-count). v3.1.3:
        //      the skip now happens via netting (1054 basis), same
        //      rows/usable outcome. ----
        LescoBillFetcher.BillInfo b7 = mirrorBill("100", "2244", null,
                null, null, "1190", "Protected Domestic");
        BillMirrorCalc.MirrorResult m7 = BillMirrorCalc.mirror(b7, 50.0,
                527.0, BillMirrorCalc.tariffCat(b7.category));
        check("v310 m7 rows", m7.rows.size(), 0);
        check("v310 m7 usable", m7.usable, false);

        // ---- unusable bases -> legacy path ----
        check("v310 null bill",
                BillMirrorCalc.mirror(null, 50.0, 500.0,
                        Tariff.NON_PROTECTED).usable,
                false);
        LescoBillFetcher.BillInfo b8 = mirrorBill("0", "1000", "50",
                null, null, null, "RESIDENTIAL");
        check("v310 zero units",
                BillMirrorCalc.mirror(b8, 50.0, 500.0,
                        Tariff.NON_PROTECTED).usable,
                false);
        LescoBillFetcher.BillInfo b9 = mirrorBill("100", null, null,
                null, null, null, "RESIDENTIAL");
        check("v310 no elec",
                BillMirrorCalc.mirror(b9, 50.0, 500.0,
                        Tariff.NON_PROTECTED).usable,
                false);
        LescoBillFetcher.BillInfo b10 = mirrorBill("100", "1000", null,
                null, null, null, "RESIDENTIAL");
        check("v310 no rows",
                BillMirrorCalc.mirror(b10, 50.0, 500.0,
                        Tariff.NON_PROTECTED).usable,
                false);

        // ---- new strings: EN + UR present ----
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] v310keys = {"est_units_eyebrow", "est_units_word",
                "est_hero_mirror", "est_excluded_note",
                "andaza_rate_per_unit"};
        for (String k : v310keys) {
            check("v310 key " + k + " EN", en.containsKey(k), true);
            check("v310 key " + k + " UR", ur.containsKey(k), true);
            check("v310 key " + k + " UR non-empty",
                    ur.get(k) != null && !ur.get(k).trim().isEmpty(),
                    true);
        }

        // ---- UI structure: units-first hero, mirror wired ----
        String msrc = readSrc(
                "java/com/hatch/bijlibill/MeterCheckActivity.java");
        check("v310 units hero",
                msrc.contains("est_units_eyebrow"), true);
        check("v310 mirror wired",
                msrc.contains("BillMirrorCalc.mirror("), true);
        check("v310 usage card gone",
                !msrc.contains("usageCard("), true);
        check("v310 mirror rows ui",
                msrc.contains("mirror.rows"), true);
    }

    // ---- v3.1.1 tests: Unit Check crash fix + manual reading entry ----

    static void runV311Tests() throws Exception {
        String msrc = readSrc(
                "java/com/hatch/bijlibill/MeterCheckActivity.java");

        // ---- BUG (v3.1.0): the estimate hero view was added to its
        // parent TWICE —
        //   content.addView(Ui.padded(this, hero));
        //   content.addView(Ui.padded(this, hero));
        // Ui.padded() does wrap.addView(view), and Android forbids one
        // child having two parents: the second addView throws
        // IllegalStateException ("The specified child already has a
        // parent"). It fired on EVERY calculate tap (both the bill-
        // mirror and the legacy path reach showResult), i.e. the
        // "keeps stopping" dialog — and when the system suppresses
        // that dialog (MIUI does this often) the dead activity just
        // drops away, which the user saw as "jumped back to the bill
        // page". Same crash, two presentations.
        // Regression: within any ONE method, no view may be
        // wrapped+added twice. (Same local name reused across
        // different methods is fine — each is a fresh view.)
        String[] mlines = msrc.split("\n");
        java.util.regex.Pattern sig = java.util.regex.Pattern.compile(
                "^\\s*(private|public|protected)\\s+[\\w<>\\[\\]]+\\s+"
                        + "(\\w+)\\s*\\(");
        java.util.regex.Pattern add = java.util.regex.Pattern.compile(
                "addView\\(Ui\\.padded\\(this,\\s*(\\w+)\\s*\\)\\)");
        String curMethod = "<init>";
        java.util.Map<String, java.util.Map<String, Integer>> perMethod =
                new java.util.LinkedHashMap<String,
                        java.util.Map<String, Integer>>();
        boolean anyDup = false;
        String dupWhere = "";
        for (String ln : mlines) {
            java.util.regex.Matcher sm = sig.matcher(ln);
            if (sm.find()) curMethod = sm.group(2);
            java.util.regex.Matcher am = add.matcher(ln);
            while (am.find()) {
                String v = am.group(1);
                java.util.Map<String, Integer> seen =
                        perMethod.get(curMethod);
                if (seen == null) {
                    seen = new java.util.LinkedHashMap<String,
                            Integer>();
                    perMethod.put(curMethod, seen);
                }
                int n = seen.containsKey(v) ? seen.get(v) : 0;
                seen.put(v, n + 1);
                if (n + 1 > 1) {
                    anyDup = true;
                    dupWhere = curMethod + ":" + v;
                }
            }
        }
        check("v311 no double addView", anyDup, false);
        if (anyDup) System.out.println("   dup at " + dupWhere);
        java.util.Map<String, Integer> sr =
                perMethod.get("doShowResult");
        check("v311 hero added exactly once",
                sr != null && sr.containsKey("hero")
                        && sr.get("hero") == 1,
                true);

        // ---- the other v3.1.0 suspects: mirror calc edge paths must
        // never throw — they degrade to the legacy path instead ----
        BillMirrorCalc.MirrorResult mn = BillMirrorCalc.mirror(null,
                50.0, 1000.0, Tariff.NON_PROTECTED);
        check("v311 mirror null bill usable", mn.usable, false);
        LescoBillFetcher.BillInfo be = mirrorBill("", "", null, null,
                null, null, null);
        BillMirrorCalc.MirrorResult me = BillMirrorCalc.mirror(be, 50.0,
                1000.0, Tariff.NON_PROTECTED);
        check("v311 mirror empty bill usable", me.usable, false);
        // junk extraLines (null pair, short pair, unparseable amount)
        // must not throw and must not invent rows.
        LescoBillFetcher.BillInfo bj = mirrorBill("200", "2000", null,
                null, null, null, "RESIDENTIAL");
        bj.extraLines.add(new String[]{"Weird Label", "abc"});
        bj.extraLines.add(null);
        bj.extraLines.add(new String[]{"OnlyLabel"});
        BillMirrorCalc.MirrorResult mj = BillMirrorCalc.mirror(bj, 50.0,
                1000.0, Tariff.NON_PROTECTED);
        check("v311 mirror junk lines usable", mj.usable, false);
        check("v311 mirror junk lines rows", mj.rows.size(), 0);

        // ---- manual entry: third option on the record step ----
        check("v311 manual button wired",
                msrc.contains("R.string.meter_manual_btn"), true);
        check("v311 manual icon", msrc.contains("R.drawable.ic_edit"),
                true);
        check("v311 manual screen",
                msrc.contains("showManualEntry()"), true);
        check("v311 manual calc",
                msrc.contains("onManualCalculate("), true);
        check("v311 manual routes to result",
                msrc.contains("showResult(r, typed)"), true);
        check("v311 manual prev conditional",
                msrc.contains("meter_manual_prev_label"), true);
        // v3.1.1: no-bill path — manual entry reachable from
        // showNoBill, adaptive steps, back-link returns correctly,
        // and the breakdown uses the result's parsed base (never
        // the null billBaseReading field).
        check("v311 manual no-bill entry",
                msrc.contains("manualFromNoBill"), true);
        check("v311 manual no-bill button",
                msrc.contains("showNoBill(noBillMsgRes)"), true);
        check("v311 manual adaptive steps",
                msrc.contains("R.string.step_record, R.string.step_estimate"),
                true);
        check("v311 breakdown uses result base",
                msrc.contains("fmtUnits(r.baseReading)"), true);
        check("v311 breakdown no null base",
                !msrc.contains("meter_readings_pair,\n"
                        + "                billBaseReading"), true);

        // ---- EN/UR parity for the new v3.1.1 strings ----
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] newKeys = {"meter_manual_btn", "meter_manual_title",
                "meter_manual_sub", "meter_manual_prev_label",
                "meter_manual_prev_hint"};
        for (String k : newKeys) {
            check("v311 key en " + k, en.containsKey(k), true);
            check("v311 key ur " + k, ur.containsKey(k), true);
            check("v311 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }

        // ---- manual-entry math rides the SAME pipeline ----
        MeterCheckCalc.Result rm = MeterCheckCalc.compute(
                "765", "787.98", "RESIDENTIAL", null, null, null, null,
                12);
        check("v311 manual compute no error", rm.error, null);
        checkClose("v311 manual units", rm.unitsUsed, 22.98, 1e-9);
        MeterCheckCalc.Result rmBad = MeterCheckCalc.compute(
                "765", "700", "RESIDENTIAL", null, null, null, null,
                12);
        check("v311 manual order rejected", rmBad.error != null, true);
        MeterCheckCalc.Result rmEmpty = MeterCheckCalc.compute(
                "765", "", "RESIDENTIAL", null, null, null, null, 12);
        check("v311 manual empty rejected", rmEmpty.error != null,
                true);

        // ---- v3.1.1: automatic previous-dues status from Arrears ----
        // Ground truth: the bill carries NO paid/unpaid field for the
        // current bill; the only real signal is the printed Arrears
        // figure. Derivation must never invent PAID/UNPAID.
        ArrearsStatus.Result a1 = ArrearsStatus.derive("1500.00");
        check("v311 arrears>0 kind", a1.kind,
                ArrearsStatus.Kind.HAS_ARREARS);
        checkClose("v311 arrears>0 amt", a1.amount, 1500.0, 1e-9);
        ArrearsStatus.Result a2 = ArrearsStatus.derive("1,250.75");
        check("v311 arrears comma kind", a2.kind,
                ArrearsStatus.Kind.HAS_ARREARS);
        checkClose("v311 arrears comma amt", a2.amount, 1250.75, 1e-9);
        ArrearsStatus.Result a3 = ArrearsStatus.derive("0.00");
        check("v311 arrears zero kind", a3.kind,
                ArrearsStatus.Kind.NO_ARREARS);
        ArrearsStatus.Result a4 = ArrearsStatus.derive("0");
        check("v311 arrears 0 kind", a4.kind,
                ArrearsStatus.Kind.NO_ARREARS);
        ArrearsStatus.Result a5 = ArrearsStatus.derive(null);
        check("v311 arrears null kind", a5.kind,
                ArrearsStatus.Kind.UNKNOWN);
        ArrearsStatus.Result a6 = ArrearsStatus.derive("");
        check("v311 arrears empty kind", a6.kind,
                ArrearsStatus.Kind.UNKNOWN);
        ArrearsStatus.Result a7 = ArrearsStatus.derive("N/A");
        check("v311 arrears junk kind", a7.kind,
                ArrearsStatus.Kind.UNKNOWN);
        ArrearsStatus.Result a8 = ArrearsStatus.derive("-50");
        check("v311 arrears credit kind", a8.kind,
                ArrearsStatus.Kind.NO_ARREARS);

        // Honesty guard: no invented current-bill PAID/UNPAID anywhere;
        // the chip is previous-dues only and wired from ArrearsStatus.
        String bsrc = readSrc("java/com/hatch/bijlibill/BillActivity.java");
        check("v311 arrears rule wired",
                bsrc.contains("ArrearsStatus.derive"), true);
        check("v311 no paid toggle",
                !bsrc.contains("MARK AS PAID")
                        && !bsrc.contains("bill_mark_paid_btn"), true);
        check("v311 arrears strings en",
                en.containsKey("arrears_chip_has")
                        && en.containsKey("arrears_chip_none"), true);
        check("v311 arrears strings ur",
                ur.containsKey("arrears_chip_has")
                        && ur.containsKey("arrears_chip_none"), true);
        check("v311 arrears fmt",
                fmtArgs(en.get("arrears_chip_has")),
                fmtArgs(ur.get("arrears_chip_has")));

        // ---- v3.1.1: DAILY reminder rule ----
        long todayMid = DailyReminder.midnight(
                System.currentTimeMillis());
        long dayMs = 86400000L;
        check("v311 days future",
                DailyReminder.daysUntilDue(todayMid + 3 * dayMs,
                        todayMid), 3);
        check("v311 days today",
                DailyReminder.daysUntilDue(todayMid, todayMid), 0);
        check("v311 days overdue",
                DailyReminder.daysUntilDue(todayMid - dayMs, todayMid),
                -1);
        check("v311 notify future",
                DailyReminder.shouldNotify(false, todayMid + dayMs,
                        todayMid), true);
        check("v311 notify today",
                DailyReminder.shouldNotify(false, todayMid, todayMid),
                true);
        check("v311 no notify when paid",
                DailyReminder.shouldNotify(true, todayMid + dayMs,
                        todayMid), false);
        check("v311 no notify unknown due",
                DailyReminder.shouldNotify(false, -1, todayMid), false);
        check("v311 notify 7d overdue",
                DailyReminder.shouldNotify(false, todayMid - 7 * dayMs,
                        todayMid), true);
        check("v311 stop 8d overdue",
                DailyReminder.shouldNotify(false, todayMid - 8 * dayMs,
                        todayMid), false);
        check("v311 variant future",
                DailyReminder.variant(5), DailyReminder.Variant.FUTURE);
        check("v311 variant today",
                DailyReminder.variant(0), DailyReminder.Variant.TODAY);
        check("v311 variant overdue",
                DailyReminder.variant(-3),
                DailyReminder.Variant.OVERDUE);
        // next 09:00: 08:00 -> today 09:00; 10:00 -> tomorrow 09:00.
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 8);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long nineA = DailyReminder.nextEightPm(cal.getTimeInMillis());
        java.util.Calendar ra = java.util.Calendar.getInstance();
        ra.setTimeInMillis(nineA);
        check("v3629 8pm hour",
                ra.get(java.util.Calendar.HOUR_OF_DAY), 20);
        check("v3629 8pm same day",
                ra.get(java.util.Calendar.DAY_OF_YEAR),
                cal.get(java.util.Calendar.DAY_OF_YEAR));
        cal.set(java.util.Calendar.HOUR_OF_DAY, 21);
        long nineB = DailyReminder.nextEightPm(cal.getTimeInMillis());
        java.util.Calendar rb = java.util.Calendar.getInstance();
        rb.setTimeInMillis(nineB);
        check("v3629 8pm next day",
                rb.getTimeInMillis() - cal.getTimeInMillis()
                        < 25 * 3600000L
                        && rb.get(java.util.Calendar.HOUR_OF_DAY) == 20,
                true);

        // ---- v3.1.1: daily reminder wiring ----
        String manifest = readSrc("AndroidManifest.xml");
        check("v311 boot permission",
                manifest.contains("RECEIVE_BOOT_COMPLETED"), true);
        check("v311 daily receiver",
                manifest.contains(".DailyReminderReceiver"), true);
        check("v311 boot receiver",
                manifest.contains(".BootReceiver")
                        && manifest.contains("BOOT_COMPLETED"), true);
        String hsrc = readSrc(
                "java/com/hatch/bijlibill/HomeActivity.java");
        // v3.3.0: the toggle is GONE — no toggle UI, code, or
        // listeners may remain in HomeActivity.
        check("v330 home toggle removed",
                !hsrc.contains("reminderToggle()")
                        && !hsrc.contains("reminderArmed()")
                        && !hsrc.contains("paintRemToggle()")
                        && !hsrc.contains("REQ_REM_TOGGLE")
                        && !hsrc.contains(
                                "DailyReminderAlarm.setEnabled")
                        && !hsrc.contains("daily_rem_toggle"),
                true);
        check("v311 bill arms daily",
                bsrc.contains("DailyReminderAlarm.schedule"), true);
        String[] dailyKeys = {"daily_rem_channel", "daily_rem_title",
                "daily_rem_future", "daily_rem_today",
                "daily_rem_overdue"};
        for (String k : dailyKeys) {
            check("v311 key en " + k, en.containsKey(k), true);
            check("v311 key ur " + k, ur.containsKey(k), true);
            check("v311 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }
        // v3.3.0: toggle strings removed from BOTH languages (parity).
        String[] goneKeys = {"daily_rem_toggle", "daily_rem_toggle_sub",
                "daily_rem_need_bill"};
        for (String k : goneKeys) {
            check("v330 key gone en " + k, en.containsKey(k), false);
            check("v330 key gone ur " + k, ur.containsKey(k), false);
        }
    }

    // ---- v3.1.2 tests: energy-basis calibration + reminder UX ----

    static void runV312Tests() throws Exception {
        long dayMs = 86400000L;
        long todayMid = DailyReminder.midnight(
                System.currentTimeMillis());

        // ---- BUG A: the parser extracts the user's real bill fields
        //      from the captured-structure fixture (302 units) ----
        String u302 = read("lesco-bill-user302-SYNTHETIC.html");
        BillInfo ub = LescoBillFetcher.parseFullBillHtml(u302,
                "06113530462902");
        check("v312 u302 prev", ub.prevReading, "48481");
        check("v312 u302 curr", ub.currReading, "48783");
        check("v312 u302 units", ub.units, "302");
        check("v312 u302 elec", ub.electricityCharges, "12528");
        check("v312 u302 taxes", ub.taxes, "2445");
        check("v312 u302 fpa", ub.fpa, "702");
        check("v312 u302 grand", ub.grandTotal, "15676");

        // ---- BUG A regression: 302 units on the user's real bill
        //      must estimate ~= 15675, not the stale ~10,927 ----
        MeterCheckCalc.Result ru = MeterCheckCalc.compute(
                ub.prevReading, ub.currReading, ub.category, ub.taxes,
                ub.gst, ub.fpa, ub.electricityCharges, 12);
        check("v312 u302 compute no error", ru.error, null);
        checkClose("v312 u302 units", ru.unitsUsed, 302.0, 1e-9);
        BillMirrorCalc.MirrorResult mu = BillMirrorCalc.mirror(ub,
                ru.unitsUsed, ru.energyEstimate,
                BillMirrorCalc.tariffCat(ub.category));
        check("v312 u302 usable", mu.usable, true);
        // stale table basis 8520.98 x k(12528/8520.98) = 12528
        // energy; + 702 FPA + 2445 taxes = 15675
        checkClose("v312 u302 energy calibrated",
                mu.energyEstimate, 12528.0, 0.01);
        checkClose("v312 u302 total", mu.estimatedTotal, 15675.0,
                3.0);

        // ---- calibration clamp: garbage k keeps it uncalibrated --
        LescoBillFetcher.BillInfo lo = mirrorBill("100", "100", null,
                "180", null, null, "RESIDENTIAL");
        BillMirrorCalc.MirrorResult mlo = BillMirrorCalc.mirror(lo,
                50.0, 500.0, Tariff.NON_PROTECTED);
        check("v312 k-low usable", mlo.usable, true);
        checkClose("v312 k-low energy uncalibrated",
                mlo.energyEstimate, 500.0, 1e-9);
        LescoBillFetcher.BillInfo hi = mirrorBill("100", "20000",
                null, "180", null, null, "RESIDENTIAL");
        BillMirrorCalc.MirrorResult mhi = BillMirrorCalc.mirror(hi,
                50.0, 500.0, Tariff.NON_PROTECTED);
        checkClose("v312 k-high energy uncalibrated",
                mhi.energyEstimate, 500.0, 1e-9);
        // boundary k = 0.25 applies: elec = 2244 x 0.25 = 561
        LescoBillFetcher.BillInfo bd = mirrorBill("100", "561", null,
                null, null, null, "RESIDENTIAL");
        BillMirrorCalc.MirrorResult mbd = BillMirrorCalc.mirror(bd,
                50.0, 500.0, Tariff.NON_PROTECTED);
        checkClose("v312 k-boundary applies",
                mbd.energyEstimate, 125.0, 1e-9);

        // ---- breakdown shows the calibrated energy (rows add up) --
        String msrc = readSrc(
                "java/com/hatch/bijlibill/MeterCheckActivity.java");
        check("v312 breakdown calibrated energy",
                msrc.contains(
                        "Tariff.moneyPlain(mirror.energyEstimate)"),
                true);

        // ---- v3.3.0: always-on arm-state decision matrix (no toggle) ----
        check("v330 arm armed", DailyReminder.armState(true,
                todayMid + dayMs, todayMid),
                DailyReminder.ArmState.ARMED);
        check("v330 arm no perm", DailyReminder.armState(false,
                todayMid + dayMs, todayMid),
                DailyReminder.ArmState.NO_PERMISSION);
        check("v330 arm no bill", DailyReminder.armState(true,
                -1, todayMid), DailyReminder.ArmState.NO_BILL);
        check("v330 arm window closed", DailyReminder.armState(true,
                todayMid - 8 * dayMs, todayMid),
                DailyReminder.ArmState.WINDOW_CLOSED);
        check("v330 arm 7d edge", DailyReminder.armState(true,
                todayMid - 7 * dayMs, todayMid),
                DailyReminder.ArmState.ARMED);
        // OFF_BY_USER is gone from the enum entirely.
        boolean hasOffByUser = false;
        for (DailyReminder.ArmState s
                : DailyReminder.ArmState.values()) {
            if ("OFF_BY_USER".equals(s.name())) hasOffByUser = true;
        }
        check("v330 no OFF_BY_USER", hasOffByUser, false);
        // due parse fail -> the schedule path treats it as NO_BILL
        check("v312 due parse fail",
                BillReminder.parseDueDate("not a date") < 0, true);
        check("v312 due parse null",
                BillReminder.parseDueDate(null) < 0, true);

        // ---- new strings: EN + UR present, non-empty, fmt parity --
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] v312keys = {"daily_rem_armed", "daily_rem_tap_enable",
                "daily_rem_need_perm"};
        for (String k : v312keys) {
            check("v312 key en " + k, en.containsKey(k), true);
            check("v312 key ur " + k, ur.containsKey(k), true);
            check("v312 key ur non-empty " + k,
                    ur.get(k) != null && !ur.get(k).trim().isEmpty(),
                    true);
            check("v312 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }
        // Honesty: the nudge never claims a payment happened.
        check("v312 nudge not a claim",
                !en.get("daily_rem_future").contains("Paid")
                        && !ur.get("daily_rem_future")
                                .contains("ادا کر دیا"),
                true);

        // ---- BUG B wiring: visible confirmation + re-ask ----
        String bsrc = readSrc(
                "java/com/hatch/bijlibill/BillActivity.java");
        check("v312 bill daily line", bsrc.contains("dailyRemLine"),
                true);
        check("v312 bill armed string",
                bsrc.contains("R.string.daily_rem_armed"), true);
        check("v312 bill hint string",
                bsrc.contains("R.string.daily_rem_tap_enable"), true);
        check("v312 bill re-ask code",
                bsrc.contains("REQ_NOTIFY_DAILY"), true);
        check("v312 bill returns state", bsrc.contains(
                "DailyReminder.ArmState scheduleDailyReminder"), true);
        String hsrc = readSrc(
                "java/com/hatch/bijlibill/HomeActivity.java");
        check("v330 home no re-ask round-trip",
                !hsrc.contains("REQ_REM_TOGGLE"), true);
        check("v330 home no armed helper",
                !hsrc.contains("reminderArmed()"), true);
        // The toggle is gone: no currentState() read, no repaint on
        // flip, no no-bill toast in HomeActivity anymore.
        check("v330 home no alarm read",
                !hsrc.contains("DailyReminderAlarm.currentState(this)"),
                true);
        check("v330 home no flip repaint",
                !hsrc.contains("remOn = reminderArmed();"), true);
        check("v330 home no no-bill toast",
                !hsrc.contains("R.string.daily_rem_need_bill"), true);
        String asrc = readSrc(
                "java/com/hatch/bijlibill/DailyReminderAlarm.java");
        check("v312 alarm uses armState",
                asrc.contains("DailyReminder.ArmState"), true);

        // ---- no duplicated methods from the v3.1.2 patching ----
        check("v312 updater wired everywhere",
                countOcc(bsrc, "updateDailyReminderLine(") >= 3, true);
        check("v312 one scheduleDailyReminder def",
                countOcc(bsrc,
                        "private DailyReminder.ArmState "
                                + "scheduleDailyReminder("),
                1);
        check("v330 no reminderArmed def",
                countOcc(hsrc, "private boolean reminderArmed("), 0);
        check("v312 one armState def",
                countOcc(readSrc(
                        "java/com/hatch/bijlibill/DailyReminder.java"),
                        "public static ArmState armState("),
                1);
    }

    static void runV313Tests() throws Exception {
        // ---- BUG: v3.1.2 calibrated on the GROSS electricityCharges
        //      and dropped the Protected subsidy via the basis guard,
        //      giving Rs 4,615 for the real 113-unit Protected bill
        //      (Total Electricity Charges 4280 - Subsidies 2551 =
        //      Net 1729; Taxes 330; FPA 5; Grand Total 2065).
        //      v3.1.3 nets the subsidy into the energy basis first.
        LescoBillFetcher.BillInfo b113 =
                new LescoBillFetcher.BillInfo();
        b113.category = "Protected";
        b113.units = "113";
        b113.prevReading = "652";
        b113.currReading = "765";
        b113.electricityCharges = "4280";
        b113.subsidy = "2551";
        b113.netCharges = "1729";
        b113.taxes = "330";
        b113.fpa = "5";
        b113.mf = "1";
        // exact showResult call sequence: compute -> mirror
        MeterCheckCalc.Result r113 = MeterCheckCalc.compute(
                b113.prevReading, b113.currReading, b113.category,
                b113.taxes, b113.gst, b113.fpa,
                b113.electricityCharges, 20);
        check("v313 u113 compute no error", r113.error, null);
        checkClose("v313 u113 units", r113.unitsUsed, 113.0, 1e-9);
        check("v313 u113 isProtected", r113.isProtected, true);
        BillMirrorCalc.MirrorResult m113 = BillMirrorCalc.mirror(b113,
                r113.unitsUsed, r113.energyEstimate,
                BillMirrorCalc.tariffCat(b113.category));
        check("v313 u113 usable", m113.usable, true);
        check("v313 u113 subsidy netted", m113.subsidyNetted, true);
        // net basis 1729: k = 1729/1223.13 in [0.25,4.0] -> energy
        // 1729 + FPA 5 + taxes 330 = 2064; no subsidy row (baked in)
        checkClose("v313 u113 energy net", m113.energyEstimate,
                1729.0, 0.01);
        check("v313 u113 no subsidy row",
                rowByLabel(m113, "Subsidy"), null);
        checkClose("v313 u113 total", m113.estimatedTotal, 2064.0,
                5.0);

        // ---- subsidy-only netting (no netCharges line printed) ----
        LescoBillFetcher.BillInfo bs = mirrorBill("113", "4280", "5",
                null, "330", "2551", "Protected");
        BillMirrorCalc.MirrorResult ms = BillMirrorCalc.mirror(bs,
                113.0, 1223.13,
                BillMirrorCalc.tariffCat(bs.category));
        check("v313 subsidy-only netted", ms.subsidyNetted, true);
        checkClose("v313 subsidy-only total", ms.estimatedTotal,
                2064.0, 5.0);

        // ---- 302-unit Non-Protected case unchanged (~15675) ----
        LescoBillFetcher.BillInfo b302 = mirrorBill("302", "12528",
                "702", null, "2445", null, "Non-Protected");
        MeterCheckCalc.Result r302 = MeterCheckCalc.compute(
                "48481", "48783", "Non-Protected", "2445", null, "702",
                "12528", 12);
        check("v313 u302 compute no error", r302.error, null);
        check("v313 u302 isProtected false", r302.isProtected, false);
        BillMirrorCalc.MirrorResult m302 = BillMirrorCalc.mirror(b302,
                r302.unitsUsed, r302.energyEstimate,
                BillMirrorCalc.tariffCat(b302.category));
        check("v313 u302 usable", m302.usable, true);
        check("v313 u302 not netted", m302.subsidyNetted, false);
        checkClose("v313 u302 total", m302.estimatedTotal, 15675.0,
                3.0);

        // ---- classification fix: Non-Protected is NOT Protected ----
        check("v313 cat non-protected",
                BillMirrorCalc.tariffCat("Non-Protected"),
                Tariff.NON_PROTECTED);
        check("v313 cat non protected spaced",
                BillMirrorCalc.tariffCat("non protected"),
                Tariff.NON_PROTECTED);
        check("v313 cat protected",
                BillMirrorCalc.tariffCat("Protected"),
                Tariff.PROTECTED);
        check("v313 cat lifeline",
                BillMirrorCalc.tariffCat("Lifeline"),
                Tariff.LIFELINE);
        check("v313 cat null",
                BillMirrorCalc.tariffCat(null),
                Tariff.NON_PROTECTED);
        MeterCheckCalc.Result rnp = MeterCheckCalc.compute(
                "0", "100", "Non-Protected", null, null, null, null, 12);
        check("v313 compute non-protected flag", rnp.isProtected,
                false);
        MeterCheckCalc.Result rsp = MeterCheckCalc.compute(
                "0", "100", "non protected", null, null, null, null, 12);
        check("v313 compute non protected spaced", rsp.isProtected,
                false);
        MeterCheckCalc.Result rp = MeterCheckCalc.compute(
                "0", "100", "Protected", null, null, null, null, 12);
        check("v313 compute protected flag", rp.isProtected, true);
        MeterCheckCalc.Result rl = MeterCheckCalc.compute(
                "0", "100", "Lifeline", null, null, null, null, 12);
        check("v313 compute lifeline flag", rl.isProtected, false);

        // ---- subsidy garbage (>= elec) is ignored, no crash ----
        LescoBillFetcher.BillInfo bg = mirrorBill("113", "4280", "5",
                null, "330", "5000", "Protected");
        BillMirrorCalc.MirrorResult mg = BillMirrorCalc.mirror(bg,
                113.0, 1223.13,
                BillMirrorCalc.tariffCat(bg.category));
        check("v313 garbage subsidy not netted", mg.subsidyNetted,
                false);
        check("v313 garbage subsidy usable", mg.usable, true);
        // gross basis mirrored as-is (guard drops the subsidy line)
        checkClose("v313 garbage subsidy total", mg.estimatedTotal,
                4615.0, 5.0);
        LescoBillFetcher.BillInfo bn = mirrorBill("113", "4280", "5",
                null, "330", null, "Protected");
        bn.netCharges = "5000"; // >= elec -> ignored
        BillMirrorCalc.MirrorResult mn = BillMirrorCalc.mirror(bn,
                113.0, 1223.13,
                BillMirrorCalc.tariffCat(bn.category));
        check("v313 garbage net not netted", mn.subsidyNetted, false);
        checkClose("v313 garbage net total", mn.estimatedTotal,
                4615.0, 5.0);

        // ---- breakdown shows the net note; strings EN+UR present --
        String msrc313 = readSrc(
                "java/com/hatch/bijlibill/MeterCheckActivity.java");
        check("v313 breakdown net note",
                msrc313.contains("andaza_subsidy_net_note"), true);
        java.util.Map<String, String> en313 =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur313 =
                readStrings("res/values-ur/strings.xml");
        check("v313 key en net note",
                en313.containsKey("andaza_subsidy_net_note"), true);
        check("v313 key ur net note",
                ur313.containsKey("andaza_subsidy_net_note"), true);
        check("v313 key ur net note non-empty",
                ur313.get("andaza_subsidy_net_note") != null
                        && !ur313.get("andaza_subsidy_net_note").trim()
                                .isEmpty(),
                true);
        check("v313 fmt net note",
                fmtArgs(en313.get("andaza_subsidy_net_note")),
                fmtArgs(ur313.get("andaza_subsidy_net_note")));

        // ---- no duplicated methods from the v3.1.3 patching ----
        check("v313 one tariffCat def",
                countOcc(readSrc(
                        "java/com/hatch/bijlibill/BillMirrorCalc.java"),
                        "public static int tariffCat("),
                1);
    }

    static void runV320Tests() throws Exception {
        // ============ A. DUAL CALIBRATION ============
        // Real 113u Protected bill: Total 4280 - Subsidy 2551 = Net
        // 1729; Taxes 330; FPA 5. kProt = 1729/1223.13 = 1.4136,
        // kNp = 4280/2619.83 = 1.6337.
        LescoBillFetcher.BillInfo b113 =
                new LescoBillFetcher.BillInfo();
        b113.category = "Protected";
        b113.units = "113";
        b113.electricityCharges = "4280";
        b113.subsidy = "2551";
        b113.netCharges = "1729";
        b113.taxes = "330";
        b113.fpa = "5";
        b113.mf = "1";
        int cat113 = BillMirrorCalc.tariffCat(b113.category);

        // est 113u (<=200): kProt path, unchanged v3.1.3 result.
        BillMirrorCalc.MirrorResult m113 = BillMirrorCalc.mirror(b113,
                113.0, Tariff.energyCharges(113, Tariff.PROTECTED),
                cat113);
        check("v320 u113 usable", m113.usable, true);
        check("v320 u113 not crossed", m113.crossedCliff, false);
        checkClose("v320 u113 total", m113.estimatedTotal, 2064.0,
                5.0);

        // est 250u (>200): kNp path — NP table x 1.6337, NOT x 1.4136.
        double np250 = Tariff.energyCharges(250, Tariff.PROTECTED);
        check("v320 np250 is NP table", np250,
                Tariff.energyCharges(250, Tariff.NON_PROTECTED));
        double kNp = 4280.0
                / Tariff.energyCharges(113, Tariff.NON_PROTECTED);
        BillMirrorCalc.MirrorResult m250 = BillMirrorCalc.mirror(b113,
                250.0, np250, cat113);
        check("v320 u250 usable", m250.usable, true);
        check("v320 u250 crossed", m250.crossedCliff, true);
        check("v320 u250 netted still", m250.subsidyNetted, true);
        checkClose("v320 u250 energy kNp", m250.energyEstimate,
                np250 * kNp, np250 * kNp * 0.02);
        // ...and it must NOT equal the old single-k (kProt) energy.
        double kProt = 1729.0
                / Tariff.energyCharges(113, Tariff.PROTECTED);
        check("v320 u250 not kProt energy",
                Math.abs(m250.energyEstimate - np250 * kProt)
                        > np250 * 0.05,
                true);

        // 302u Non-Protected bill: kNp path identical to v3.1.3.
        LescoBillFetcher.BillInfo b302 = mirrorBill("302", "12528",
                "702", null, "2445", null, "Non-Protected");
        BillMirrorCalc.MirrorResult m302 = BillMirrorCalc.mirror(b302,
                302.0, Tariff.energyCharges(302, Tariff.NON_PROTECTED),
                BillMirrorCalc.tariffCat(b302.category));
        check("v320 u302 usable", m302.usable, true);
        check("v320 u302 not crossed", m302.crossedCliff, false);
        checkClose("v320 u302 total", m302.estimatedTotal, 15675.0,
                3.0);

        // billUnits > 200, non-protected, estimating 150u: kNp path,
        // sane total (k from the bill's own NP-rate basis).
        double npT250 = Tariff.energyCharges(250,
                Tariff.NON_PROTECTED);
        LescoBillFetcher.BillInfo b250 = mirrorBill("250",
                String.valueOf(npT250 * 1.47), "10", null, "1500",
                null, "Non-Protected");
        double npT150 = Tariff.energyCharges(150,
                Tariff.NON_PROTECTED);
        BillMirrorCalc.MirrorResult m150 = BillMirrorCalc.mirror(b250,
                150.0, npT150,
                BillMirrorCalc.tariffCat(b250.category));
        check("v320 u150 usable", m150.usable, true);
        check("v320 u150 not crossed", m150.crossedCliff, false);
        checkClose("v320 u150 energy kNp", m150.energyEstimate,
                npT150 * 1.47, npT150 * 1.47 * 0.02);
        check("v320 u150 total sane",
                m150.estimatedTotal > 5000
                        && m150.estimatedTotal < 12000,
                true);

        // ============ B. GUARD CALC ============
        GuardCalc.Projection g1 = GuardCalc.project(113, 26, 30);
        checkClose("v320 g1 avg", g1.dailyAvg, 113.0 / 26, 1e-9);
        check("v320 g1 remaining", g1.remainingDays, 4);
        checkClose("v320 g1 projected", g1.projectedUnits,
                113 + (113.0 / 26) * 4, 1e-9);
        checkClose("v320 g1 budget", g1.dailyBudget, 87.0 / 4, 1e-9);
        check("v320 g1 safe", g1.status, GuardCalc.Status.SAFE);

        GuardCalc.Projection g2 = GuardCalc.project(180, 24, 30);
        checkClose("v320 g2 projected", g2.projectedUnits, 225.0,
                1e-9);
        checkClose("v320 g2 budget", g2.dailyBudget, 20.0 / 6,
                1e-9);
        check("v320 g2 danger", g2.status, GuardCalc.Status.DANGER);

        GuardCalc.Projection g3 = GuardCalc.project(190, 29, 30);
        check("v320 g3 warning", g3.status,
                GuardCalc.Status.WARNING);

        // zero days elapsed: no division by zero, budget over full 30.
        GuardCalc.Projection g4 = GuardCalc.project(50, 0, 30);
        checkClose("v320 g4 avg", g4.dailyAvg, 0.0, 1e-9);
        checkClose("v320 g4 projected", g4.projectedUnits, 50.0,
                1e-9);
        checkClose("v320 g4 budget", g4.dailyBudget, 150.0 / 30,
                1e-9);
        check("v320 g4 safe", g4.status, GuardCalc.Status.SAFE);

        // reading below anchor -> used clamped to 0, no crash.
        GuardCalc.Projection g5 = GuardCalc.project(-5, 10, 30);
        checkClose("v320 g5 used", g5.usedUnits, 0.0, 1e-9);
        checkClose("v320 g5 projected", g5.projectedUnits, 0.0,
                1e-9);
        check("v320 g5 safe", g5.status, GuardCalc.Status.SAFE);

        // non-positive cycle -> 30-day default.
        GuardCalc.Projection g6 = GuardCalc.project(100, 10, 0);
        check("v320 g6 remaining", g6.remainingDays, 20);
        check("v320 g6 danger", g6.status, GuardCalc.Status.DANGER);

        // ============ B2. GUARD REMINDER POLICY ============
        check("v320 rem danger fires",
                GuardReminder.shouldNotify(GuardCalc.Status.DANGER,
                        "", "2026-09-27"),
                true);
        check("v320 rem warning fires",
                GuardReminder.shouldNotify(GuardCalc.Status.WARNING,
                        "2026-09-26", "2026-09-27"),
                true);
        check("v320 rem already notified",
                GuardReminder.shouldNotify(GuardCalc.Status.WARNING,
                        "2026-09-27", "2026-09-27"),
                false);
        check("v320 rem safe silent",
                GuardReminder.shouldNotify(GuardCalc.Status.SAFE,
                        "", "2026-09-27"),
                false);
        check("v320 daysBetween", GuardReminder.daysBetween(
                "2026-09-01", "2026-09-27"), 26);
        check("v320 daysBetween neg", GuardReminder.daysBetween(
                "2026-09-27", "2026-09-01"), -26);
        check("v320 daysBetween bad", GuardReminder.daysBetween(
                "bad", "2026-09-27"), 0);

        // ============ C. WHAT-IF ORDERING (exact pipeline) ============
        double[] slots = {190, 200, 210, 250};
        double[] totals = new double[slots.length];
        for (int i = 0; i < slots.length; i++) {
            MeterCheckCalc.Result r = MeterCheckCalc.compute("0",
                    String.valueOf((long) slots[i]), b113.category,
                    null, null, null, null, 15);
            BillMirrorCalc.MirrorResult m = BillMirrorCalc.mirror(
                    b113, r.unitsUsed, r.energyEstimate, cat113);
            totals[i] = (m != null && m.usable) ? m.estimatedTotal
                    : r.estimatedTotal;
        }
        check("v320 whatif 210>200", totals[2] > totals[1], true);
        check("v320 whatif 250>210", totals[3] > totals[2], true);
        double jumpLo = totals[1] - totals[0]; // 190 -> 200
        double jumpHi = totals[2] - totals[1]; // 200 -> 210 (cliff)
        check("v320 whatif cliff jump bigger", jumpHi > jumpLo,
                true);
        check("v320 whatif cliff jump big",
                jumpHi > 3000, true); // ~Rs 6.6k on this bill

        // ============ D. STRUCTURE ============
        String mirrorSrc = readSrc(
                "java/com/hatch/bijlibill/BillMirrorCalc.java");
        check("v320 one kOf def",
                countOcc(mirrorSrc, "private static double kOf("), 1);
        check("v320 crossedCliff field",
                mirrorSrc.contains("public boolean crossedCliff;"),
                true);
        check("v320 new activities",
                readSrc("java/com/hatch/bijlibill/GuardActivity.java")
                        .contains("class GuardActivity")
                        && readSrc(
                                "java/com/hatch/bijlibill/WhatIfActivity.java")
                                .contains("class WhatIfActivity"),
                true);
        String man = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("AndroidManifest.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        check("v360 manifest versionCode",
                man.contains("android:versionCode=\"90\""), true);
        check("v360 manifest versionName",
                man.contains("android:versionName=\"3.6.58\""), true);
        check("v320 manifest guard activity",
                man.contains("android:name=\".GuardActivity\""), true);
        check("v320 manifest whatif activity",
                man.contains("android:name=\".WhatIfActivity\""),
                true);
        check("v320 manifest guard receiver",
                man.contains("android:name=\".GuardReminderReceiver\""),
                true);

        // ---- new strings EN+UR, non-empty, fmt-arg parity ----
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");
        String[] newKeys = {"guard_title", "guard_projection",
                "guard_daily_budget", "guard_status_danger",
                "guard_notif_warning", "guard_notif_danger",
                "guard_save_reading_btn", "whatif_title",
                "whatif_compare_btn", "whatif_jump",
                "whatif_max_jump", "whatif_cliff_banner",
                "whatif_note_estimate", "whatif_open_btn",
                "crossed_cliff_banner"};
        for (String k : newKeys) {
            check("v320 key en " + k, en.containsKey(k), true);
            check("v320 key ur " + k,
                    ur.containsKey(k)
                            && ur.get(k) != null
                            && !ur.get(k).trim().isEmpty(),
                    true);
            check("v320 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }
    }

    static void runV330Tests() throws Exception {
        String bsrc = readSrc(
                "java/com/hatch/bijlibill/BillActivity.java");
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");

        // ============ A. ACCURACY MATH (pure) ============
        checkClose("v330 pct equal",
                AccuracyTracker.pct(2064, 2064), 100.0, 1e-9);
        // Rs 2,180 estimate vs Rs 2,065 actual -> ~94.4%.
        checkClose("v330 pct near miss",
                AccuracyTracker.pct(2180, 2065), 94.4, 0.05);
        // Estimate double the bill -> floor 0.
        checkClose("v330 pct double",
                AccuracyTracker.pct(4130, 2065), 0.0, 1e-9);
        // Worse than double -> clamped, never negative.
        checkClose("v330 pct clamped",
                AccuracyTracker.pct(99999, 2065), 0.0, 1e-9);
        // Missing bill total -> 0, never NaN/Infinity.
        checkClose("v330 pct no bill",
                AccuracyTracker.pct(2064, 0), 0.0, 1e-9);
        double negRes = AccuracyTracker.pct(2064, -5);
        check("v330 pct neg bill finite",
                Double.isFinite(negRes) && negRes == 0.0, true);

        // ============ B. PENDING STORE ============
        AccuracyTracker.MemStore st = new AccuracyTracker.MemStore();
        // Take with nothing pending -> null (the no-card path).
        check("v330 take empty",
                AccuracyTracker.takePending(st, "k1") == null, true);
        AccuracyTracker.savePending(st, "k1", 113, 2064);
        AccuracyTracker.Pending p =
                AccuracyTracker.takePending(st, "k1");
        check("v330 pending round-trip", p != null, true);
        checkClose("v330 pending units", p.estUnits, 113.0, 1e-9);
        checkClose("v330 pending total", p.estTotal, 2064.0, 1e-9);
        // Consumed exactly once: second take -> null.
        check("v330 pending consumed",
                AccuracyTracker.takePending(st, "k1") == null, true);
        // Latest estimate supersedes the older pending one.
        AccuracyTracker.savePending(st, "k2", 100, 1900);
        AccuracyTracker.savePending(st, "k2", 120, 2400);
        AccuracyTracker.Pending p2 =
                AccuracyTracker.takePending(st, "k2");
        checkClose("v330 pending latest", p2.estTotal, 2400.0, 1e-9);
        // Per-meter isolation.
        AccuracyTracker.savePending(st, "a", 10, 100);
        AccuracyTracker.savePending(st, "b", 20, 200);
        checkClose("v330 pending isolation a",
                AccuracyTracker.takePending(st, "a").estTotal,
                100.0, 1e-9);
        checkClose("v330 pending isolation b",
                AccuracyTracker.takePending(st, "b").estTotal,
                200.0, 1e-9);
        // Garbage in store -> null, never a crash.
        st.put("pending_bad", "garbage");
        check("v330 pending garbage",
                AccuracyTracker.takePending(st, "bad") == null, true);

        // ============ C. HISTORY CAP ============
        AccuracyTracker.MemStore hst = new AccuracyTracker.MemStore();
        for (int i = 0; i < 8; i++) {
            AccuracyTracker.Comparison c =
                    new AccuracyTracker.Comparison();
            c.billMonth = "M" + i;
            c.estUnits = 100 + i;
            c.estTotal = 2000 + i;
            c.billUnits = 100 + i;
            c.billTotal = 2000 + i;
            c.pct = AccuracyTracker.pct(c.estTotal, c.billTotal);
            AccuracyTracker.addComparison(hst, "k", c);
        }
        java.util.List<AccuracyTracker.Comparison> hist =
                AccuracyTracker.history(hst, "k");
        check("v330 history capped", hist.size(), 6);
        // Oldest two dropped: first entry is M2, newest last is M7.
        check("v330 history oldest dropped", hist.get(0).billMonth,
                "M2");
        check("v330 history newest last", hist.get(5).billMonth,
                "M7");
        // Empty history -> empty list, never null.
        check("v330 history empty",
                AccuracyTracker.history(hst, "nope").isEmpty(), true);

        // ============ D. GUARD ALWAYS-ON ============
        String gsrc = readSrc(
                "java/com/hatch/bijlibill/GuardStore.java");
        check("v330 guard store no setEnabled",
                !gsrc.contains("setEnabled"), true);
        check("v330 guard store no isEnabled",
                !gsrc.contains("isEnabled"), true);
        String gasrc = readSrc(
                "java/com/hatch/bijlibill/GuardReminderAlarm.java");
        check("v330 guard alarm hasPermission",
                gasrc.contains("public static boolean hasPermission("),
                true);
        check("v330 guard alarm scheduleIfEnabled kept",
                gasrc.contains("scheduleIfEnabled"), true);
        check("v330 guard alarm no isEnabled",
                !gasrc.contains("GuardStore.isEnabled"), true);
        String grsrc = readSrc(
                "java/com/hatch/bijlibill/GuardReminderReceiver.java");
        check("v330 guard receiver no isEnabled",
                !grsrc.contains("GuardStore.isEnabled"), true);
        check("v330 guard receiver uses hasPermission",
                grsrc.contains("GuardReminderAlarm.hasPermission"),
                true);

        // ============ E. WIRING ============
        String msrc = readSrc(
                "java/com/hatch/bijlibill/MeterCheckActivity.java");
        check("v330 meter saves pending",
                msrc.contains("AccuracyTracker.savePending"), true);
        check("v330 bill takes pending",
                bsrc.contains("AccuracyTracker.takePending"), true);
        check("v330 bill adds comparison",
                bsrc.contains("AccuracyTracker.addComparison"), true);
        check("v330 bill accuracy card",
                bsrc.contains("addAccuracyCard("), true);

        // ============ F. NEW STRINGS ============
        String[] accKeys = {"acc_title", "acc_est_line",
                "acc_bill_line", "acc_pct_line", "acc_units_delta",
                "acc_history_title", "acc_hist_row"};
        for (String k : accKeys) {
            check("v330 key en " + k, en.containsKey(k), true);
            check("v330 key ur " + k,
                    ur.containsKey(k) && ur.get(k) != null
                            && !ur.get(k).trim().isEmpty(),
                    true);
            check("v330 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }

        // ============ G. MANIFEST ============
        // Covered by the v330 versionCode/versionName checks above
        // (shared manifest assertions in runV230Tests/runV320Tests).
    }

    static void runV340Tests() throws Exception {
        String gsrc = readSrc(
                "java/com/hatch/bijlibill/GuardActivity.java");
        String hsrc = readSrc(
                "java/com/hatch/bijlibill/HomeActivity.java");
        String bsrc = readSrc(
                "java/com/hatch/bijlibill/BillActivity.java");
        String bootsrc = readSrc(
                "java/com/hatch/bijlibill/BootReceiver.java");
        String stsrc = readSrc(
                "java/com/hatch/bijlibill/GuardStore.java");
        String manifest = readSrc("AndroidManifest.xml");
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");

        // ============ A. STREAK TRANSITIONS — WEEKLY (v3.6.0, pure) ============
        com.hatch.bijlibill.StreakCalc.Result r;
        // First check-in starts the streak (2026-09-27 is a Sunday).
        r = com.hatch.bijlibill.StreakCalc.checkin("", 0,
                "2026-09-27");
        check("v360 streak first last", r.lastIso, "2026-09-27");
        check("v360 streak first count", r.count, 1);

        // Same calendar week twice: idempotent (Mon -> Fri same week).
        r = com.hatch.bijlibill.StreakCalc.checkin("2026-09-28", 3,
                "2026-10-02");
        check("v360 streak same week count", r.count, 3);
        check("v360 streak same week last", r.lastIso, "2026-10-02");

        // Next week (Sun -> Mon): extends.
        r = com.hatch.bijlibill.StreakCalc.checkin("2026-09-27", 3,
                "2026-09-28");
        check("v360 streak next week count", r.count, 4);
        check("v360 streak next week last", r.lastIso, "2026-09-28");

        // A skipped week: resets to 1.
        r = com.hatch.bijlibill.StreakCalc.checkin("2026-09-20", 10,
                "2026-10-04");
        check("v360 streak gap count", r.count, 1);
        check("v360 streak gap last", r.lastIso, "2026-10-04");

        // Garbled stored date: resets, never throws.
        r = com.hatch.bijlibill.StreakCalc.checkin("bogus", 5,
                "2026-09-27");
        check("v360 streak garbage last count", r.count, 1);

        // Garbled today: state preserved, never throws.
        r = com.hatch.bijlibill.StreakCalc.checkin("2026-09-21", 3,
                "bogus");
        check("v360 streak garbage today count", r.count, 3);

        // Year boundary: Sun 2025-12-28 -> Sun 2026-01-04 extends.
        r = com.hatch.bijlibill.StreakCalc.checkin("2025-12-28", 2,
                "2026-01-04");
        check("v360 streak year boundary", r.count, 3);

        // Milestones are weekly now: 4 and 12.
        check("v360 milestone 4",
                com.hatch.bijlibill.StreakCalc.isMilestone(4), true);
        check("v360 milestone 12",
                com.hatch.bijlibill.StreakCalc.isMilestone(12), true);
        check("v360 not milestone 7",
                com.hatch.bijlibill.StreakCalc.isMilestone(7), false);
        check("v360 not milestone 30",
                com.hatch.bijlibill.StreakCalc.isMilestone(30), false);
        check("v360 not milestone 0",
                com.hatch.bijlibill.StreakCalc.isMilestone(0), false);

        // ============ B. BILL-DAY NUDGE DATE MATH (pure) ============
        long trig = com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                "05-SEP-26", 0);
        check("v340 nudge parses", trig > 0, true);
        // Same calendar day in other formats -> identical trigger.
        check("v340 nudge fmt slash",
                com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                        "05/09/2026", 0),
                trig);
        check("v340 nudge fmt dash",
                com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                        "05-09-2026", 0),
                trig);
        check("v340 nudge fmt iso",
                com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                        "2026-09-05", 0),
                trig);
        // Trigger fires at 09:00 local, 28 days after reading day.
        java.util.Calendar cTrig = java.util.Calendar.getInstance();
        cTrig.setTimeInMillis(trig);
        check("v340 nudge hour", cTrig.get(
                java.util.Calendar.HOUR_OF_DAY), 9);
        check("v340 nudge day", cTrig.get(
                java.util.Calendar.DAY_OF_MONTH), 3);
        check("v340 nudge month", cTrig.get(
                java.util.Calendar.MONTH),
                java.util.Calendar.OCTOBER);
        // Stale trigger (bill already old): never fires -> -1.
        check("v340 nudge stale",
                com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                        "05-SEP-26", trig + 1),
                -1L);
        // Garbage reading date: skip silently -> -1.
        check("v340 nudge garbage",
                com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                        "not a date", 0),
                -1L);
        check("v340 nudge empty",
                com.hatch.bijlibill.BillDayNudge.nudgeTimeMs("", 0),
                -1L);
        check("v340 nudge null",
                com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(null, 0),
                -1L);
        // Month-boundary arithmetic: 30 Sep + 28d = 28 Oct; exactly
        // 25 days after the 05 Sep trigger (no DST drift).
        long trig2 = com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                "30-SEP-26", 0);
        check("v340 nudge month boundary", trig2 - trig,
                25L * 86400000L);
        // Leap reading: 29 Feb 2024 + 28d = 28 Mar 2024.
        long trigLeap = com.hatch.bijlibill.BillDayNudge.nudgeTimeMs(
                "29-FEB-24", 0);
        java.util.Calendar cLeap = java.util.Calendar.getInstance();
        cLeap.setTimeInMillis(trigLeap);
        check("v340 nudge leap day", cLeap.get(
                java.util.Calendar.DAY_OF_MONTH), 28);
        check("v340 nudge leap month", cLeap.get(
                java.util.Calendar.MONTH),
                java.util.Calendar.MARCH);

        // ============ C. WEEKLY REPORT AGGREGATION (pure) ============
        java.util.List<com.hatch.bijlibill.WeeklyReport.Point> pts =
                new java.util.ArrayList<
                        com.hatch.bijlibill.WeeklyReport.Point>();
        long d = 86400000L;
        // now = day 9; window starts at day 2.
        addPoint(pts, 0, 100);
        addPoint(pts, 2, 110);
        addPoint(pts, 5, 118);
        addPoint(pts, 8, 130);
        checkClose("v340 week units",
                com.hatch.bijlibill.WeeklyReport.weekUnits(pts, 9 * d),
                20.0, 1e-9);
        // All points inside the window: earliest point is the base.
        pts.clear();
        addPoint(pts, 5, 118);
        addPoint(pts, 8, 130);
        checkClose("v340 week units inside",
                com.hatch.bijlibill.WeeklyReport.weekUnits(pts, 9 * d),
                12.0, 1e-9);
        // Empty: 0.
        pts.clear();
        checkClose("v340 week units empty",
                com.hatch.bijlibill.WeeklyReport.weekUnits(pts, 9 * d),
                0.0, 1e-9);
        // Meter reset / decreasing readings: clamp to 0.
        pts.clear();
        addPoint(pts, 0, 200);
        addPoint(pts, 8, 50);
        checkClose("v340 week units negative",
                com.hatch.bijlibill.WeeklyReport.weekUnits(pts, 9 * d),
                0.0, 1e-9);
        // Fallback proration: 302 bill units -> ~70.47/week.
        checkClose("v340 prorate",
                com.hatch.bijlibill.WeeklyReport.prorateWeekUnits(
                        302),
                302.0 / 30 * 7, 1e-9);
        check("v340 prorate zero",
                com.hatch.bijlibill.WeeklyReport.prorateWeekUnits(0),
                0.0);
        check("v340 prorate negative",
                com.hatch.bijlibill.WeeklyReport.prorateWeekUnits(-5),
                0.0);

        // ============ E. STRINGS: keys + EN/UR fmt parity ============
        String[] v340keys = {
                "streak_line", "streak_milestone_4w",
                "streak_milestone_12w", "streak_home_card",
                "nudge_notif_channel", "nudge_notif_title",
                "nudge_notif_text", "weekly_notif_channel",
                "weekly_notif_title", "weekly_notif_text",
                "weekly_notif_text_danger",
                "weekly_notif_text_fallback"};
        for (String k : v340keys) {
            check("v340 key en " + k, en.containsKey(k), true);
            check("v340 key ur " + k,
                    ur.containsKey(k) && ur.get(k) != null
                            && !ur.get(k).trim().isEmpty(),
                    true);
            check("v340 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }

        // ============ F. MANIFEST ============
        check("v360 manifest versionCode",
                manifest.contains("android:versionCode=\"90\""),
                true);
        check("v360 manifest versionName",
                manifest.contains("android:versionName=\"3.6.58\""),
                true);
        check("v340 manifest nudge receiver",
                manifest.contains(".BillDayNudgeReceiver"), true);
        check("v340 manifest weekly receiver",
                manifest.contains(".WeeklyReportReceiver"), true);

        // ============ G. WIRING (headless source greps) ============
        check("v340 guard checkin", gsrc.contains(
                "StreakCalc.checkin"), true);
        check("v340 guard streak ui", gsrc.contains("streak_line"),
                true);
        check("v340 home streak card", hsrc.contains(
                "streak_home_card"), true);
        check("v340 home weekly schedule", hsrc.contains(
                "WeeklyReportAlarm.schedule"), true);
        check("v340 bill nudge schedule", bsrc.contains(
                "BillDayNudgeAlarm.scheduleFromReadingDate"), true);
        check("v340 boot nudge rearm", bootsrc.contains(
                "BillDayNudgeAlarm.scheduleIfArmed"), true);
        check("v340 boot weekly rearm", bootsrc.contains(
                "WeeklyReportAlarm.schedule"), true);
        check("v340 guardstore streak", stsrc.contains(
                "streak_count_"), true);
    }

    // ---- v3.6.0 tests ----
    static void runV360Tests() throws Exception {
        String gsrc = readSrc(
                "java/com/hatch/bijlibill/GuardActivity.java");
        String hsrc = readSrc(
                "java/com/hatch/bijlibill/HomeActivity.java");
        String wrsrc = readSrc(
                "java/com/hatch/bijlibill/WeeklyReportReceiver.java");
        String wrasrc = readSrc(
                "java/com/hatch/bijlibill/WeeklyReportAlarm.java");
        String scsrc = readSrc(
                "java/com/hatch/bijlibill/StreakCalc.java");
        String gcsrc = readSrc(
                "java/com/hatch/bijlibill/GuardCalc.java");
        String manifest = readSrc("AndroidManifest.xml");
        java.util.Map<String, String> en =
                readStrings("res/values/strings.xml");
        java.util.Map<String, String> ur =
                readStrings("res/values-ur/strings.xml");

        // ============ A. AUTO-PROJECTION MATH (pure) ============
        com.hatch.bijlibill.GuardCalc.Projection a =
                com.hatch.bijlibill.GuardCalc.autoProjectFromBill(
                        150, 30, 0);
        check("v360 auto estimate flag", a.estimate, true);
        checkClose("v360 auto dailyAvg", a.dailyAvg, 5.0, 1e-9);
        checkClose("v360 auto projected", a.projectedUnits, 150.0,
                1e-9);
        check("v360 auto status safe", a.status,
                com.hatch.bijlibill.GuardCalc.Status.SAFE);
        checkClose("v360 auto budget", a.dailyBudget, 200.0 / 30,
                1e-9);
        check("v360 auto daysElapsed", a.daysElapsed, 0);
        check("v360 auto remaining", a.remainingDays, 30);

        // 210-unit pace -> DANGER vs the 200 line.
        a = com.hatch.bijlibill.GuardCalc.autoProjectFromBill(
                210, 30, 10);
        check("v360 auto danger", a.status,
                com.hatch.bijlibill.GuardCalc.Status.DANGER);
        checkClose("v360 auto danger used", a.usedUnits, 70.0, 1e-9);
        check("v360 auto danger estimate", a.estimate, true);

        // Stale bill (35 days elapsed): used 227.5 >= 190 but
        // projected 195 <= 200 -> WARNING, no days left -> no budget.
        a = com.hatch.bijlibill.GuardCalc.autoProjectFromBill(
                195, 30, 35);
        check("v360 auto stale warning", a.status,
                com.hatch.bijlibill.GuardCalc.Status.WARNING);
        check("v360 auto stale remaining", a.remainingDays, 0);
        checkClose("v360 auto stale budget", a.dailyBudget, 0.0,
                1e-9);

        // Garbage inputs clamp, never throw.
        a = com.hatch.bijlibill.GuardCalc.autoProjectFromBill(
                -5, 0, -3);
        checkClose("v360 auto neg projected", a.projectedUnits, 0.0,
                1e-9);
        check("v360 auto neg safe", a.status,
                com.hatch.bijlibill.GuardCalc.Status.SAFE);
        check("v360 auto neg estimate", a.estimate, true);

        // Reading-based projections are NOT estimates.
        com.hatch.bijlibill.GuardCalc.Projection pr =
                com.hatch.bijlibill.GuardCalc.project(50, 10, 30);
        check("v360 precise not estimate", pr.estimate, false);

        // ============ B. SUNDAY 10AM ALARM MATH (pure) ============
        // Sunday 2026-09-27 08:00 -> next fire is TODAY 10:00.
        java.util.Calendar sun = java.util.Calendar.getInstance();
        sun.set(2026, java.util.Calendar.SEPTEMBER, 27, 8, 0, 0);
        sun.set(java.util.Calendar.MILLISECOND, 0);
        long nxt = com.hatch.bijlibill.WeeklyReportAlarm
                .nextSundayTenAm(sun.getTimeInMillis());
        java.util.Calendar cNxt = java.util.Calendar.getInstance();
        cNxt.setTimeInMillis(nxt);
        check("v360 sunday same day", cNxt.get(
                java.util.Calendar.DAY_OF_MONTH), 27);
        check("v360 sunday hour", cNxt.get(
                java.util.Calendar.HOUR_OF_DAY), 10);
        check("v360 sunday dow", cNxt.get(
                java.util.Calendar.DAY_OF_WEEK),
                java.util.Calendar.SUNDAY);
        // Sunday 10:30 -> next fire is next Sunday 10:00.
        sun.set(java.util.Calendar.HOUR_OF_DAY, 10);
        sun.set(java.util.Calendar.MINUTE, 30);
        nxt = com.hatch.bijlibill.WeeklyReportAlarm
                .nextSundayTenAm(sun.getTimeInMillis());
        cNxt.setTimeInMillis(nxt);
        check("v360 sunday next day", cNxt.get(
                java.util.Calendar.DAY_OF_MONTH), 4);
        check("v360 sunday next month", cNxt.get(
                java.util.Calendar.MONTH),
                java.util.Calendar.OCTOBER);
        check("v360 sunday next hour", cNxt.get(
                java.util.Calendar.HOUR_OF_DAY), 10);
        // Monday -> the coming Sunday 10:00.
        java.util.Calendar mon = java.util.Calendar.getInstance();
        mon.set(2026, java.util.Calendar.SEPTEMBER, 28, 12, 0, 0);
        mon.set(java.util.Calendar.MILLISECOND, 0);
        nxt = com.hatch.bijlibill.WeeklyReportAlarm
                .nextSundayTenAm(mon.getTimeInMillis());
        cNxt.setTimeInMillis(nxt);
        check("v360 monday dow", cNxt.get(
                java.util.Calendar.DAY_OF_WEEK),
                java.util.Calendar.SUNDAY);
        check("v360 monday hour", cNxt.get(
                java.util.Calendar.HOUR_OF_DAY), 10);

        // ============ C. STRINGS: keys + EN/UR fmt parity ============
        String[] v360keys = {
                "guard_estimate_note", "weekly_notif_text_auto",
                "weekly_notif_text_auto_danger",
                "weekly_notif_nudge", "notif_off_title",
                "notif_off_text", "notif_off_open",
                "streak_milestone_4w", "streak_milestone_12w"};
        for (String k : v360keys) {
            check("v360 key en " + k, en.containsKey(k), true);
            check("v360 key ur " + k,
                    ur.containsKey(k) && ur.get(k) != null
                            && !ur.get(k).trim().isEmpty(),
                    true);
            check("v360 fmt " + k, fmtArgs(en.get(k)),
                    fmtArgs(ur.get(k)));
        }
        // Old daily keys are gone.
        check("v360 old key 7 gone",
                en.containsKey("streak_milestone_7"), false);
        check("v360 old key 30 gone",
                en.containsKey("streak_milestone_30"), false);

        // ============ D. MANIFEST ============
        check("v360 manifest versionCode",
                manifest.contains("android:versionCode=\"90\""),
                true);
        check("v360 manifest versionName",
                manifest.contains("android:versionName=\"3.6.58\""),
                true);
        check("v360 manifest targetSdk 36",
                manifest.contains("android:targetSdkVersion=\"36\""),
                true);

        // ============ E. WIRING (headless source greps) ============
        check("v360 guard autoProjection", gsrc.contains(
                "autoProjection()"), true);
        check("v360 guard estimate note", gsrc.contains(
                "guard_estimate_note"), true);
        check("v360 guard precise priority",
                gsrc.contains("always takes priority")
                        || gsrc.contains("takes priority"), true);
        check("v360 guard milestone 4w", gsrc.contains(
                "MILESTONE_4W"), true);
        check("v360 streak weekly", scsrc.contains(
                "MILESTONE_4W"), true);
        check("v360 streak no daily const", !scsrc.contains(
                "MILESTONE_WEEK"), true);
        check("v360 alarm ten am", wrasrc.contains(
                "nextSundayTenAm"), true);
        check("v360 alarm no nine am", !wrasrc.contains(
                "nextSundayNineAm"), true);
        check("v360 receiver nudge", wrsrc.contains(
                "weekly_notif_nudge"), true);
        check("v360 receiver auto", wrsrc.contains(
                "autoProjectFromBill"), true);
        check("v360 receiver meter fallback", wrsrc.contains(
                "firstSavedMeterKey"), true);
        check("v360 receiver bigtext", wrsrc.contains(
                "BigTextStyle"), true);
        check("v360 home notif banner", hsrc.contains(
                "refreshNotifBanner"), true);
        check("v360 home settings intent", hsrc.contains(
                "ACTION_APP_NOTIFICATION_SETTINGS"), true);
        // Strictly always-on: no notification toggle anywhere.
        check("v360 no toggle home",
                !hsrc.contains("setOnCheckedChangeListener"), true);
        check("v360 no toggle guard",
                !gsrc.contains("setOnCheckedChangeListener"), true);
        check("v360 no switch widget",
                !hsrc.contains("android.widget.Switch")
                        && !gsrc.contains("android.widget.Switch"),
                true);
    }

    static void addPoint(
            java.util.List<com.hatch.bijlibill.WeeklyReport.Point> pts,
            int day, double reading) {
        com.hatch.bijlibill.WeeklyReport.Point p =
                new com.hatch.bijlibill.WeeklyReport.Point();
        p.dayMs = (long) day * 86400000L;
        p.reading = reading;
        pts.add(p);
    }

    /** Counts non-overlapping occurrences of a literal. */
    static int countOcc(String hay, String needle) {
        int n = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /** Minimal strings.xml reader: name -> raw value (headless). */
    static java.util.Map<String, String> readStrings(String path)
            throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(path));
        String xml = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        java.util.Map<String, String> out =
                new java.util.LinkedHashMap<String, String>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<string name=\"([^\"]+)\">(.*?)</string>",
                java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /** Sorted list of %n$x / %n$d placeholders in a string. */
    static java.util.List<String> fmtArgs(String s) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (s == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "%\\d+\\$[sd]").matcher(s);
        while (m.find()) out.add(m.group());
        java.util.Collections.sort(out);
        return out;
    }
}
