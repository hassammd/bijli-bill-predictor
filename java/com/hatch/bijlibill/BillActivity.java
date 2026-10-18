package com.hatch.bijlibill;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hatch.bijlibill.LescoBillFetcher.BillErrorException;
import com.hatch.bijlibill.LescoBillFetcher.BillInfo;
import com.hatch.bijlibill.LescoBillFetcher.BillPage;
import com.hatch.bijlibill.LescoBillFetcher.FetchException;
import com.hatch.bijlibill.LescoBillFetcher.FetchKind;

import java.io.IOException;
import java.util.List;

/**
 * v2.6.0 design-system Bill Result (logic unchanged from v2.5.0):
 *  - App bar: back, "Bijli ka Bill", masked Ref/ID, language toggle.
 *  - "Saved copy" pill when served from the 24h cache.
 *  - Reminder status as an amber card (bell icon).
 *  - Holder card (name, address, company pill).
 *  - Green-gradient HERO: total payable (Archivo 900), due date,
 *    this month's units, amber "N din baqi hain" chip.
 *  - Amber late-fee card with the two surcharge tiers (only what the
 *    bill actually printed — no invented dates).
 *  - Detail card: billing month, meter reading (monospace).
 *  - Secondary actions: UNIT CHECK (opens the meter-check flow) and
 *    refresh; the full official bill stays in the pinch-zoomable
 *    WebView below (the PDF download renders from it).
 *  - Sticky bottom removed (v3.1.1, user request): no PAY BILL
 *    button anywhere. Banner ad slot sits at the bottom instead.
 *
 * Fonts (user rule): headings Archivo 800 (big amounts 900), body
 * Plus Jakarta Sans 400/500, Urdu Noto Nastaliq Urdu, reference
 * numbers/readings monospace. No other fonts.
 */
public class BillActivity extends BaseActivity {

    public static final String EXTRA_REF = "ref";
    public static final String EXTRA_MODE = "mode"; // "ref" or "cid"
    public static final String EXTRA_DISCO = "disco"; // v2.5.0

    private static final int REQ_NOTIFY = 51;
    /** v3.1.2: re-ask POST_NOTIFICATIONS from the daily hint. */
    private static final int REQ_NOTIFY_DAILY = 53;

    private String ref;
    private String mode;
    private Disco disco = Disco.LESCO; // v2.5.0
    private ScrollView content;
    private LinearLayout bannerBox;
    private boolean fromCache;
    /** v2.1.0: last shown bill page — feeds Meter Check (needs prevReading). */
    private BillPage lastPage;

    // v2.3.0 reminder UI state (v2.6.0: amber card instead of a line).
    private TextView reminderLine;
    /** v3.1.2: daily-reminder arm-state line (confirmation / hint). */
    private TextView dailyRemLine;
    private String pendingRemDue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ref = getIntent().getStringExtra(EXTRA_REF);
        if (ref == null) ref = "";
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (!"cid".equals(mode)) mode = "ref";
        // v2.5.0: which company this bill belongs to.
        disco = Disco.fromCode(getIntent().getStringExtra(EXTRA_DISCO));

        android.view.Window w = getWindow();
        if (w != null) {
            w.setStatusBarColor(Ds.bg());
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        // Root: app bar / scrolling content (weight 1) / banner.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ds.bg());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(appBar());

        content = new ScrollView(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content);

        // Banner ad slot (bottom).
        bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        bannerBox.setPadding(Ui.dp(this, 16), Ui.dp(this, 6),
                Ui.dp(this, 16), Ui.dp(this, 6));
        root.addView(bannerBox);

        // v3.6.17: bottom nav (was missing — user feedback).
        root.addView(Ds.bottomNav(this, Ds.TAB_BILLS));

        setContentView(root);
        loadBill(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_NOTIFY) {
            if (granted && pendingRemDue != null) {
                long dueMs = BillReminder.parseDueDate(pendingRemDue);
                long remMs = BillReminder.reminderTimeMs(dueMs);
                if (remMs > 0) doScheduleReminder(pendingRemDue, remMs);
                pendingRemDue = null;
                // v3.1.1: permission just granted — arm the daily
                // reminder too (state was persisted at fetch time).
                DailyReminderAlarm.schedule(this);
                // v3.1.2: refresh the daily line — the grant arms it.
                updateDailyReminderLine(
                        DailyReminderAlarm.currentState(this));
            } else {
                showReminderDenied();
                // v3.1.2: the tappable daily hint offers the way back
                // after a denial (the one-time flow never asks again).
                updateDailyReminderLine(
                        DailyReminderAlarm.currentState(this));
                pendingRemDue = null;
            }
        } else if (requestCode == REQ_NOTIFY_DAILY) {
            // v3.1.2: permission re-ask from the tappable daily hint.
            if (granted) {
                DailyReminderAlarm.schedule(this);
            }
            updateDailyReminderLine(
                    DailyReminderAlarm.currentState(this));
        }
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------
    // App bar (v2.6.0 design)
    // ------------------------------------------------------------------

    private View appBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(14), dp(20), dp(10));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chev_right);
        back.setRotation(180);
        back.setColorFilter(Ds.g800(), PorterDuff.Mode.SRC_IN);
        int bs = dp(38);
        back.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        back.setBackgroundResource(R.drawable.ds_back_btn);
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(12);
        col.setLayoutParams(clp);

        TextView title = new TextView(this);
        title.setText(getString(R.string.bill_title));
        title.setTextSize(18);
        title.setTextColor(Ds.ink());
        Ds.heading(this, title);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString("cid".equals(mode)
                ? R.string.bill_id_masked : R.string.bill_ref_masked,
                SavedMeters.maskId(ref)));
        sub.setTextSize(12);
        sub.setTextColor(Ds.muted());
        Ds.mono(sub);
        col.addView(sub);
        bar.addView(col);

        LinearLayout spacer = new LinearLayout(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                0, 1, 1f));
        bar.addView(spacer);
        bar.addView(Ds.langToggle(this));
        return bar;
    }

    // Small builders (v2.6.0 design)
    // ------------------------------------------------------------------

    /** White 20dp card with the standard side margins. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.ds_card);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), dp(14), dp(20), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private View dashDivider() {
        View v = new View(this);
        v.setBackgroundResource(R.drawable.ds_dash_line);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
        return v;
    }

    /** Amber alert card (reminder / late fee): icon + text column. */
    private LinearLayout alertCard(int iconRes) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setBackgroundResource(R.drawable.ds_alert_amber);
        c.setPadding(dp(15), dp(15), dp(15), dp(15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), dp(14), dp(18), 0);
        c.setLayoutParams(lp);

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        int s = dp(20);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                s, s);
        ilp.rightMargin = dp(13);
        ilp.topMargin = dp(2);
        ic.setLayoutParams(ilp);
        c.addView(ic);
        return c;
    }

    /** Detail row: label left (body), value right (heading). */
    private LinearLayout detailRow(String label, String value,
            boolean valueMono, boolean mutedSmall) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));

        TextView k = new TextView(this);
        k.setText(label);
        k.setTextSize(13.5f);
        k.setTextColor(Ds.muted());
        Ds.body(this, k, 400);
        k.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(k);

        TextView v = new TextView(this);
        v.setText(value);
        if (mutedSmall) {
            v.setTextSize(12.5f);
            v.setTextColor(Ds.muted());
            Ds.body(this, v, 500);
        } else {
            v.setTextSize(13.5f);
            v.setTextColor(Ds.ink());
            if (valueMono) Ds.mono(v); else Ds.heading(this, v);
        }
        v.setGravity(Gravity.END);
        r.addView(v);
        return r;
    }

    private View amberButton(String text, View.OnClickListener onClick) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.ds_btn_amber);
        b.setMinimumHeight(dp(58));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (Build.VERSION.SDK_INT >= 21) b.setElevation(dp(6));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setLetterSpacing(0.05f);
        t.setTextColor(Ds.amberDark());
        Ds.heading(this, t);
        b.addView(t);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private View outlineButton(String text, View.OnClickListener onClick) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.ds_dl_btn);
        b.setMinimumHeight(dp(58));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setLetterSpacing(0.05f);
        t.setTextColor(Ds.g800());
        Ds.heading(this, t);
        b.addView(t);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private View ghostButton(String text, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Ds.muted());
        Ds.heading(this, b);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(14), 0, dp(14));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    // ------------------------------------------------------------------
    // Internet pre-check (user guide)
    // ------------------------------------------------------------------

    /**
     * True when the device currently has internet. Fail-OPEN: if the
     * check itself errors (missing permission, odd device), we attempt
     * the fetch anyway rather than showing a false offline screen.
     */
    private boolean isOnlineNow() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network net = cm.getActiveNetwork();
                if (net == null) return false;
                android.net.NetworkCapabilities caps =
                        cm.getNetworkCapabilities(net);
                return caps != null && caps.hasCapability(
                        android.net.NetworkCapabilities
                                .NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Fetch
    // ------------------------------------------------------------------

    private void loadBill(final boolean forceRefresh) {
        fromCache = false;
        showLoading();
        if (!forceRefresh) {
            BillCache.CachedPage cached =
                    BillCache.getHtml(this, cacheKey());
            if (cached != null) {
                try {
                    final BillPage p = LescoBillFetcher.pageFromHtml(
                            cached.html, ref, cached.fetchPath);
                    fromCache = true;
                    showBillPage(p);
                    AdsManager.onCalculationDone(this);
                    return;
                } catch (Exception e) {
                    // Stale/unparseable cache — fall through to a fresh
                    // fetch instead of showing anything odd.
                }
            }
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final BillPage p = LescoBillFetcher.FetchOrchestrator
                            .fetchOrThrow(
                                    LescoBillFetcher.newTransport(),
                                    new LescoBillFetcher.FetchOrchestrator
                                            .OnlineChecker() {
                                        @Override public boolean isOnline() {
                                            return isOnlineNow();
                                        }
                                    },
                                    ref, mode, disco);
                    BillCache.putHtml(BillActivity.this, cacheKey(),
                            p.html, p.fetchPath);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            showBillPage(p);
                            AdsManager.onCalculationDone(BillActivity.this);
                        }
                    });
                } catch (final BillErrorException e) {
                    // Server rejected the number: the fetcher already
                    // classified it as NOT_FOUND vs INVALID_REF. The raw
                    // server text is the real error line.
                    final int msgRes = e.kind == FetchKind.NOT_FOUND
                            ? R.string.fetch_not_found
                            : R.string.fetch_invalid_ref;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            showError(getString(msgRes), e.getMessage());
                        }
                    });
                } catch (final FetchException e) {
                    // Fetch-time failure kinds. OFFLINE gets its own
                    // screen; everything else shares the error screen
                    // with the REAL technical detail underneath.
                    if (e.kind == FetchKind.OFFLINE) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                showOfflineError();
                            }
                        });
                        return;
                    }
                    final int msgRes;
                    switch (e.kind) {
                        case TOKEN_ERROR:
                            msgRes = R.string.fetch_token_error;
                            break;
                        case ANTIFORGERY_ERROR:
                            msgRes = R.string.fetch_antiforgery_error;
                            break;
                        case HTTP_ERROR:
                            msgRes = R.string.fetch_http_error;
                            break;
                        case PARSE_EMPTY:
                            msgRes = R.string.fetch_parse_empty;
                            break;
                        default:
                            msgRes = LescoBillFetcher.isNetworkDown(e)
                                    ? R.string.fetch_network_down
                                    : R.string.fetch_failed;
                            break;
                    }
                    final String realDetail = e.getMessage()
                            + (e.debug.isEmpty() ? ""
                                    : " | " + e.debug);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            showError(getString(msgRes), realDetail);
                        }
                    });
                } catch (final IOException e) {
                    // Real exception text: class + message, never a
                    // generic "failed".
                    final int msgRes = LescoBillFetcher.isNetworkDown(e)
                            ? R.string.fetch_network_down
                            : R.string.fetch_failed;
                    final String realDetail =
                            e.getClass().getSimpleName()
                                    + (e.getMessage() == null
                                            ? "" : ": " + e.getMessage());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            showError(getString(msgRes), realDetail);
                        }
                    });
                }
            }
        }).start();
    }

    /** v2.5.0 cache key includes the disco: disco:mode:ref.
     *  Cache key: typed string already includes the U/R letter. */
    private String cacheKey() {
        return disco.code + ":" + mode + ":" + ref;
    }

    // ------------------------------------------------------------------
    // States
    // ------------------------------------------------------------------

    private void showLoading() {
        content.removeAllViews();
        // v3.6.5: shimmer-style loading (user request) — spinner + pulsing
        // skeleton cards instead of plain text.
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(dp(20), dp(32), dp(20), dp(20));

        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminate(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            spinner.getIndeterminateDrawable().setColorFilter(
                    Ds.deepGreen(),
                    android.graphics.PorterDuff.Mode.SRC_IN);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                dp(44), dp(44));
        slp.bottomMargin = dp(16);
        wrap.addView(spinner, slp);

        TextView t = new TextView(this);
        t.setText(getString(R.string.fetching));
        t.setTextSize(15);
        t.setTextColor(Ds.muted());
        Ds.body(this, t, 400);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(20);
        wrap.addView(t, tlp);

        // Skeleton cards with pulse animation (shimmer feel).
        final java.util.List<View> skeletons = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            View sk = new View(this);
            sk.setBackgroundResource(R.drawable.card_bg);
            sk.setAlpha(0.55f);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(74));
            klp.bottomMargin = dp(12);
            wrap.addView(sk, klp);
            skeletons.add(sk);
        }
        content.addView(wrap);

        // Pulse animation — staggered for a wave effect.
        for (int i = 0; i < skeletons.size(); i++) {
            final View sk = skeletons.get(i);
            android.animation.ObjectAnimator pulse =
                    android.animation.ObjectAnimator.ofFloat(sk, "alpha", 0.55f, 1f, 0.55f);
            pulse.setDuration(1200);
            pulse.setStartDelay(i * 200);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.start();
            sk.setTag(pulse);
        }
    }

    /**
     * v2.0.5 error screen: friendly main message + the REAL technical
     * detail in a small grey line (HTTP status or exception
     * class+message — never a generic "failed"), plus: retry, open in
     * browser, back. v2.3.0: the REPORT BHEJO button is gone from every
     * visible screen of this activity (sendDebugReport stays as an
     * unused method).
     */
    private void showError(String mainMessage, String debugLine) {
        content.removeAllViews();
        LinearLayout card = card();
        card.setPadding(dp(20), dp(24), dp(20), dp(20));
        TextView t = new TextView(this);
        t.setText(mainMessage);
        t.setTextSize(15);
        t.setTextColor(Ds.red());
        Ds.heading(this, t);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        if (debugLine != null && !debugLine.trim().isEmpty()) {
            TextView d = new TextView(this);
            d.setText(getString(R.string.debug_server,
                    debugLine.trim()));
            d.setTextSize(11);
            d.setTextColor(Ds.muted());
            Ds.mono(d);
            d.setGravity(Gravity.CENTER);
            d.setPadding(0, dp(8), 0, 0);
            card.addView(d);
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
        card.addView(new View(this), sp);
        card.addView(amberButton(getString(R.string.refresh_btn),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        loadBill(true);
                    }
                }));
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        card.addView(new View(this), sp2);
        card.addView(outlineButton(getString(R.string.browser_btn),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openInBrowser();
                    }
                }));
        card.addView(ghostButton(getString(R.string.back),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { finish(); }
                }));
        content.addView(card);
    }

    /** v2.0.5: no internet — shown BEFORE any request is attempted. */
    private void showOfflineError() {
        content.removeAllViews();
        LinearLayout card = card();
        card.setPadding(dp(20), dp(24), dp(20), dp(20));
        TextView t = new TextView(this);
        t.setText(getString(R.string.fetch_offline));
        t.setTextSize(15);
        t.setTextColor(Ds.red());
        Ds.heading(this, t);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
        card.addView(new View(this), sp);
        card.addView(amberButton(getString(R.string.refresh_btn),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        loadBill(true);
                    }
                }));
        card.addView(ghostButton(getString(R.string.back),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { finish(); }
                }));
        content.addView(card);
    }

    /** v2.0.5: open the bill page in the user's own browser. */
    private void openInBrowser() {
        try {
            String url = LescoBillFetcher.browserFallbackUrl(ref, mode, disco);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.browser_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * v2.1.0: METER CHECK entry from the bill screen. The activity
     * re-parses the cached raw HTML itself; here we only gate on having
     * a usable base reading — v2.2.1: the bill's PRESENT reading
     * (currReading), falling back to the previous reading.
     */
    private void openMeterCheck() {
        String base = (lastPage != null && lastPage.parseOk
                && lastPage.info != null)
                ? MeterCheckCalc.resolveBase(
                        lastPage.info.currReading,
                        lastPage.info.prevReading)
                : null;
        if (base == null || base.trim().isEmpty()
                || MeterCheckCalc.parseReading(base) < 0) {
            Toast.makeText(this, R.string.meter_need_prev,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, MeterCheckActivity.class);
        i.putExtra(MeterCheckActivity.EXTRA_DISCO, disco.code);
        i.putExtra(MeterCheckActivity.EXTRA_MODE, mode);
        i.putExtra(MeterCheckActivity.EXTRA_REF, ref);
        i.putExtra(MeterCheckActivity.EXTRA_FROM_BILL, true);
        startActivity(i);
    }

    /**
     * Writes the LAST raw portal response to a debug HTML file: the
     * auto-generated header comment (timestamp, typed reference, fetch
     * path, http/https used, HTTP status, final URL, byte length, page
     * title, POST field summary, cookie names sent, redirect hops)
     * followed by the full raw HTML. For direct legs the raw HTML IS
     * the /general full-bill page. Stored under Downloads via
     * MediaStore (no extra permission), then the share sheet opens.
     * v2.3.0: no visible UI calls this anymore (all REPORT BHEJO
     * buttons removed); kept as an unused debug method.
     */
    private void sendDebugReport() {
        LescoBillFetcher.FetchDebug d = LescoBillFetcher.lastDebug();
        if (d == null || d.rawHtml == null) {
            Toast.makeText(this, R.string.report_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
            String fileName = "bijli-bill-debug-" + stamp + ".html";

            StringBuilder sb = new StringBuilder();
            sb.append(LescoBillFetcher.debugReportHeader(d, "2.3.0"));
            sb.append(d.rawHtml);
            byte[] bytes = sb.toString().getBytes("UTF-8");

            Uri uri = saveToDownloads(fileName, bytes);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/html");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Debug report"));
            Toast.makeText(this, R.string.report_saved,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.report_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Stores a file under Downloads and returns its content:// URI.
     * API 29+: MediaStore.Downloads with RELATIVE_PATH (no permission
     * needed). Below 29: MediaStore.Files with an absolute DATA path
     * (also no extra permission for our own file).
     */
    private Uri saveToDownloads(String fileName, byte[] bytes)
            throws IOException {
        android.content.ContentResolver cr = getContentResolver();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                fileName);
        v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                "text/html");
        Uri collection;
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            collection = android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            java.io.File dir = android.os.Environment
                    .getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            v.put(android.provider.MediaStore.MediaColumns.DATA,
                    new java.io.File(dir, fileName).getAbsolutePath());
            collection = android.provider.MediaStore.Files.getContentUri(
                    "external");
        }
        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new IOException("MediaStore insert failed");
        java.io.OutputStream os = null;
        try {
            os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("openOutputStream failed");
            os.write(bytes);
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignored) { }
            }
        }
        return uri;
    }

    // ------------------------------------------------------------------
    // v2.3.0 DOWNLOAD — WebView straight to PDF, no print dialog
    // ------------------------------------------------------------------


    /**
     * Renders the full WebView document page-by-page (A4 aspect) into
     * a PrintedPdfDocument, saves it under Downloads/BijliBill, toasts,
     * and opens it. enableSlowWholeDocumentDraw (called before the
     * WebView was created) makes draw() capture the whole document.
     */

    /**
     * PDF twin of saveToDownloads: Downloads/BijliBill subfolder,
     * application/pdf MIME. Returns the content:// URI (directly
     * viewable with a read grant — no FileProvider needed).
     */
    private Uri savePdfToDownloads(String fileName, byte[] bytes)
            throws IOException {
        android.content.ContentResolver cr = getContentResolver();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                fileName);
        v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                "application/pdf");
        Uri collection;
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                            + "/BijliBill");
            collection = android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "BijliBill");
            if (!dir.exists()) dir.mkdirs();
            v.put(android.provider.MediaStore.MediaColumns.DATA,
                    new java.io.File(dir, fileName).getAbsolutePath());
            collection = android.provider.MediaStore.Files.getContentUri(
                    "external");
        }
        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new IOException("MediaStore insert failed");
        java.io.OutputStream os = null;
        try {
            os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("openOutputStream failed");
            os.write(bytes);
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignored) { }
            }
        }
        return uri;
    }

    // ------------------------------------------------------------------
    // v2.3.0 due-date reminder
    // ------------------------------------------------------------------

    /**
     * Schedules a local notification for 09:00, 2 days before the
     * bill's due date. Past/unparseable dates -> nothing. Android 13+
     * needs POST_NOTIFICATIONS: asked ONCE with a plain-words rationale;
     * a denial is remembered and skipped silently afterwards.
     */
    private void scheduleDueReminder(BillInfo info) {
        if (info == null || info.dueDate == null) return;
        long dueMs = BillReminder.parseDueDate(info.dueDate);
        if (dueMs < 0) return;
        long remMs = BillReminder.reminderTimeMs(dueMs);
        if (remMs < 0) return; // due date already past — no schedule
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                doScheduleReminder(info.dueDate, remMs);
            } else {
                android.content.SharedPreferences sp =
                        getSharedPreferences(BillReminder.PREFS,
                                Context.MODE_PRIVATE);
                if (!sp.getBoolean(BillReminder.KEY_NOTIF_ASKED, false)) {
                    sp.edit().putBoolean(BillReminder.KEY_NOTIF_ASKED,
                            true).apply();
                    pendingRemDue = info.dueDate;
                    Toast.makeText(this, R.string.reminder_rationale,
                            Toast.LENGTH_LONG).show();
                    requestPermissions(
                            new String[]{
                                    Manifest.permission.POST_NOTIFICATIONS},
                            REQ_NOTIFY);
                } else {
                    showReminderDenied();
                }
            }
        } else {
            doScheduleReminder(info.dueDate, remMs);
        }
    }

    /**
     * v3.1.1: persists the daily-reminder bill state and arms the
     * inexact repeating 09:00 alarm. A newly fetched bill REPLACES the
     * old state (reschedule).
     *
     * v3.1.2: returns the resulting arm state so the bill screen can
     * show it visibly — a confirmation when armed, a tappable
     * permission hint when the permission is missing — instead of
     * skipping silently after a denial.
     */
    private DailyReminder.ArmState scheduleDailyReminder(BillInfo b,
            List<BillSummary.Field> fs) {
        if (b == null || b.dueDate == null) {
            return DailyReminder.ArmState.NO_BILL;
        }
        long dueMs = BillReminder.parseDueDate(b.dueDate);
        if (dueMs < 0) {
            return DailyReminder.ArmState.NO_BILL;
        }
        String payable = BillSummary.value(fs, "payable");
        try {
            getSharedPreferences(DailyReminder.PREFS,
                    Context.MODE_PRIVATE)
                    .edit()
                    .putString(DailyReminder.KEY_REF, ref)
                    .putString(DailyReminder.KEY_MODE, mode)
                    .putString(DailyReminder.KEY_DISCO, disco.code)
                    .putString(DailyReminder.KEY_DUE_TEXT, b.dueDate)
                    .putLong(DailyReminder.KEY_DUE_MS, dueMs)
                    .putString(DailyReminder.KEY_PAYABLE,
                            payable == null ? "" : payable.trim())
                    .apply();
        } catch (Exception ignored) {
            return DailyReminder.ArmState.NO_BILL;
        }
        // DailyReminderAlarm.schedule() arms only when the arm state
        // is ARMED (permission + toggle + open window); currentState()
        // reports the truth back to the UI.
        DailyReminderAlarm.schedule(this);
        return DailyReminderAlarm.currentState(this);
    }

    /**
     * v3.1.2: shows the TRUE daily-reminder state on the bill screen.
     * ARMED -> visible confirmation. NO_PERMISSION -> tappable hint
     * that re-requests POST_NOTIFICATIONS (the one-time flow never
     * asks again after a denial). Anything else -> hidden.
     */
    private void updateDailyReminderLine(DailyReminder.ArmState st) {
        if (dailyRemLine == null) return;
        try {
            if (st == DailyReminder.ArmState.ARMED) {
                dailyRemLine.setText(
                        getString(R.string.daily_rem_armed));
                dailyRemLine.setVisibility(View.VISIBLE);
                dailyRemLine.setClickable(false);
                dailyRemLine.setOnClickListener(null);
            } else if (st == DailyReminder.ArmState.NO_PERMISSION) {
                dailyRemLine.setText(
                        getString(R.string.daily_rem_tap_enable));
                dailyRemLine.setVisibility(View.VISIBLE);
                dailyRemLine.setClickable(true);
                dailyRemLine.setFocusable(true);
                dailyRemLine.setOnClickListener(
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                requestPermissions(new String[]{
                                        Manifest.permission
                                                .POST_NOTIFICATIONS},
                                        REQ_NOTIFY_DAILY);
                            }
                        });
            } else {
                dailyRemLine.setVisibility(View.GONE);
                dailyRemLine.setOnClickListener(null);
            }
        } catch (Exception ignored) {
            // A status line must never break the bill screen.
        }
    }

    /**
     * v3.3.0: "Andaza vs Asal Bill" accuracy tracker. Consumes the
     * meter's pending estimate (saved by MeterCheckActivity's result
     * screen), compares it against this freshly fetched bill, shows
     * a comparison card, and files the result into the per-meter
     * history (last 6). No pending estimate -> no card, nothing
     * recorded. A tracking failure must never break the bill screen.
     */
    private void addAccuracyCard(LinearLayout inner, BillInfo b,
            List<BillSummary.Field> fs) {
        try {
            String meterKey = disco.code + ":" + mode + ":" + ref;
            AccuracyTracker.Pending pend =
                    AccuracyTracker.takePending(this, meterKey);
            if (pend == null) return; // never estimated -> no card
            double billTotal = MeterCheckCalc.parseAmount(
                    BillSummary.value(fs, "payable"));
            int billUnits = b.unitsAsInt();
            if (!(billTotal > 0) || billUnits <= 0) return;
            AccuracyTracker.Comparison c =
                    new AccuracyTracker.Comparison();
            c.billMonth = b.billMonth == null ? ""
                    : b.billMonth.trim();
            c.estUnits = pend.estUnits;
            c.estTotal = pend.estTotal;
            c.billUnits = billUnits;
            c.billTotal = billTotal;
            c.pct = AccuracyTracker.pct(pend.estTotal, billTotal);
            AccuracyTracker.addComparison(this, meterKey, c);
            View card = accuracyCardView(c, meterKey);
            if (card != null) inner.addView(card);
        } catch (Exception ignored) {
        }
    }

    /** The comparison card + history list. Null-safe; null on failure. */
    private View accuracyCardView(AccuracyTracker.Comparison c,
            String meterKey) {
        try {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.ds_alert_blue);
            card.setPadding(dp(15), dp(15), dp(15), dp(15));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(18), dp(14), dp(18), 0);
            card.setLayoutParams(lp);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_check);
            int s = dp(20);
            LinearLayout.LayoutParams ilp =
                    new LinearLayout.LayoutParams(s, s);
            ilp.rightMargin = dp(10);
            ic.setLayoutParams(ilp);
            titleRow.addView(ic);
            TextView title = new TextView(this);
            title.setText(getString(R.string.acc_title));
            title.setTextSize(14);
            title.setTextColor(Ds.ink());
            Ds.heading(this, title);
            titleRow.addView(title);
            card.addView(titleRow);
            card.addView(Ui.spacer(this, 8));

            card.addView(accLine(getString(R.string.acc_est_line,
                    fmtUnitsShort(c.estUnits),
                    Tariff.money(c.estTotal))));
            card.addView(accLine(getString(R.string.acc_bill_line,
                    String.valueOf(c.billUnits),
                    Tariff.money(c.billTotal))));

            TextView pctV = new TextView(this);
            pctV.setText(getString(R.string.acc_pct_line,
                    fmtPct(c.pct)));
            pctV.setTextSize(20);
            pctV.setTextColor(Ds.deepGreen());
            Ds.heading(this, pctV);
            pctV.setGravity(Gravity.CENTER);
            card.addView(Ui.spacer(this, 4));
            card.addView(pctV);

            int dUnits = c.billUnits - (int) Math.round(c.estUnits);
            card.addView(accLine(getString(R.string.acc_units_delta,
                    (dUnits >= 0 ? "+" : "") + dUnits)));

            List<AccuracyTracker.Comparison> hist =
                    AccuracyTracker.history(this, meterKey);
            // The just-added comparison is last; show earlier ones.
            if (hist.size() > 1) {
                card.addView(Ui.spacer(this, 8));
                TextView ht = new TextView(this);
                ht.setText(getString(R.string.acc_history_title));
                ht.setTextSize(12);
                ht.setTextColor(Ds.muted());
                Ds.heading(this, ht);
                card.addView(ht);
                for (int i = 0; i < hist.size() - 1; i++) {
                    AccuracyTracker.Comparison h = hist.get(i);
                    String month = h.billMonth == null
                            || h.billMonth.isEmpty() ? "—"
                            : h.billMonth;
                    card.addView(accLine(getString(
                            R.string.acc_hist_row, month,
                            fmtPct(h.pct))));
                }
            }
            return card;
        } catch (Exception e) {
            return null;
        }
    }

    /** Small body line inside the accuracy card. */
    private TextView accLine(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ds.ink());
        Ds.body(this, t, 500);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    /** "113" for 113.0, "113.5" otherwise. */
    private static String fmtUnitsShort(double u) {
        long r = Math.round(u);
        if (Math.abs(u - r) < 0.005) return String.valueOf(r);
        return String.format(java.util.Locale.US, "%.1f", u);
    }

    /** "99.9%" — 1 decimal, percent sign baked in. */
    private static String fmtPct(double p) {
        return String.format(java.util.Locale.US, "%.1f%%", p);
    }

    /** Arms the AlarmManager alarm and persists it (re-fetch = reschedule). */
    private void doScheduleReminder(String dueText, long remMs) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(
                    Context.ALARM_SERVICE);
            Intent ri = new Intent(this, ReminderReceiver.class);
            ri.setAction(ReminderReceiver.ACTION_REMIND);
            ri.putExtra(ReminderReceiver.EXTRA_REF, ref);
            ri.putExtra(ReminderReceiver.EXTRA_MODE, mode);
            ri.putExtra(ReminderReceiver.EXTRA_DUE, dueText);
            ri.putExtra(ReminderReceiver.EXTRA_DISCO, disco.code); // v2.5.0
            PendingIntent pi = PendingIntent.getBroadcast(this, 1001, ri,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 31
                        && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                            remMs, pi);
                } else {
                    try {
                        am.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP, remMs, pi);
                    } catch (SecurityException se) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                                remMs, pi);
                    }
                }
            }
            getSharedPreferences(BillReminder.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(BillReminder.KEY_REF, ref)
                    .putString(BillReminder.KEY_MODE, mode)
                    .putString(BillReminder.KEY_DUE_TEXT, dueText)
                    .putLong(BillReminder.KEY_TIME, remMs)
                    .apply();
            if (reminderLine != null) {
                reminderLine.setText(getString(R.string.reminder_line,
                        BillReminder.reminderLabel(remMs)));
                reminderLine.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {
            // A failed schedule must never break the bill screen.
        }
    }

    private void showReminderDenied() {
        if (reminderLine != null) {
            reminderLine.setText(getString(R.string.reminder_denied));
            reminderLine.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------
    // Bill rendering — v2.6.0 design system
    // ------------------------------------------------------------------

    /**
     * v2.5.0: auto-save the fetched meter (id + holder name) into
     * "Mere Meter". A missing holder name falls back to the id so the
     * row is never blank.
     */
    private void autoSaveMeter(BillInfo info) {
        try {
            String holder = info.consumerName;
            if (holder == null || holder.trim().isEmpty()) {
                holder = SavedMeters.maskId(ref);
            } else {
                holder = holder.trim();
            }
            SavedMeters.add(MeterUi.store(this), disco.code, mode, ref,
                    holder, System.currentTimeMillis());
        } catch (Exception ignored) {
            // Auto-save must never break the bill screen.
        }
    }

    /**
     * v2.6.0 layout: the whole page scrolls as one — saved-copy pill,
     * reminder card, holder card, green payable hero, late-fee card,
     * detail card, secondary actions (unit check / refresh), then the
     * full official bill in the pinch-zoomable WebView (the PDF
     * download renders from it).
     */
    /**
     * v3.0.0 layout: status line (saved pill + real reminder text),
     * consumer card, white payable card, compact detail rows, PDF
     * download, secondary actions, then the full official bill in the
     * pinch-zoomable WebView (the PDF renders from it).
     */
    private void showBillPage(BillPage p) {
        content.removeAllViews();
        lastPage = p;
        reminderLine = null;
        dailyRemLine = null;

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        content.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Status line: saved-copy pill + the REAL reminder text.
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(20), dp(12), dp(20), 0);
        status.setLayoutParams(slp);
        if (fromCache) status.addView(savedPill());
        reminderLine = new TextView(this);
        reminderLine.setTextSize(12);
        reminderLine.setTextColor(Ds.muted());
        Ds.body(this, reminderLine, 600);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (fromCache) rlp.leftMargin = dp(10);
        reminderLine.setLayoutParams(rlp);
        reminderLine.setVisibility(View.GONE);
        status.addView(reminderLine);
        inner.addView(status);

        // v3.1.2: daily-reminder arm-state line under the status row
        // (confirmation when armed, tappable permission hint when
        // the permission is missing).
        dailyRemLine = new TextView(this);
        dailyRemLine.setTextSize(12);
        dailyRemLine.setTextColor(Ds.g600());
        Ds.body(this, dailyRemLine, 600);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(dp(20), dp(6), dp(20), 0);
        dailyRemLine.setLayoutParams(dlp);
        dailyRemLine.setVisibility(View.GONE);
        inner.addView(dailyRemLine);

        if (p.parseOk && p.info != null) {
            BillInfo b = p.info;
            List<BillSummary.Field> fs = BillSummary.fields(b);

            View holder = holderCard(b, fs);
            if (holder != null) inner.addView(holder);
            View pay = payCard(b, fs);
            if (pay != null) inner.addView(pay);
            // v3.6.16: "View Full Bill" right after the amount — visible
            // without scrolling (user feedback: button was below the fold).
            inner.addView(viewBillBtn(p.html));
            // v3.3.0: "Andaza vs Asal Bill" — compare the meter's
            // pending estimate against this fresh bill (no card when
            // the user never ran an estimate).
            addAccuracyCard(inner, b, fs);
            View detail = detailCard(b, fs);
            if (detail != null) inner.addView(detail);

            scheduleDueReminder(b);
            // v3.1.1: DAILY 09:00 reminder while the bill counts as
            // unpaid (the bill carries no paid-status signal, so a
            // fetched bill counts as unpaid — see DailyReminder).
            // v3.1.2: show the TRUE arm state on the bill screen —
            // confirmation when armed, tappable permission hint when
            // not (never a silent skip after a denial).
            updateDailyReminderLine(scheduleDailyReminder(b, fs));
            // v3.4.0: bill-day nudge — one-shot alarm ~2 days before
            // the next bill is expected, closing the monthly open
            // loop that feeds the accuracy tracker. Replaces any
            // previous nudge; silent when the reading date is
            // unknown.
            BillDayNudgeAlarm.scheduleFromReadingDate(this,
                    GuardStore.meterKey(disco.code, mode, ref),
                    b.readingDate);
            // v2.5.0: auto-save this meter (holder name shown in
            // "Mere Meter").
            autoSaveMeter(b);
        }

        // Parse failed but the page is still shown: say so honestly.
        if (!p.parseOk) {
            LinearLayout nc = alertCard(R.drawable.ic_warning);
            TextView nt = new TextView(this);
            nt.setText(getString(R.string.parse_partial_note));
            nt.setTextSize(13.5f);
            nt.setTextColor(Color.parseColor("#6E4403"));
            nt.setLineSpacing(dp(2), 1.6f);
            Ds.body(this, nt, 500);
            nc.addView(nt, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            inner.addView(nc);
        }

        // Secondary actions: unit check + refresh (features preserved).
        inner.addView(actionsRow());

        // v3.6.16: viewBillBtn moved up (right after pay card) — removed
        // from here to avoid duplication.
        inner.addView(Ui.spacer(this, 8));

        AdsManager.loadBannerInto(this, bannerBox);
    }

    /**
     * v3.6.9: prominent "View Full Bill" button — opens BillViewerActivity
     * (full-screen WebView with back button).
     */
    private View viewBillBtn(final String html) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.ds_btn_green);
        b.setMinimumHeight(dp(56));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), dp(14), dp(20), 0);
        b.setLayoutParams(lp);

        ImageView dic = new ImageView(this);
        dic.setImageResource(R.drawable.ic_nav_bill);
        dic.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        int ds = dp(20);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ds, ds);
        dlp.rightMargin = dp(10);
        dic.setLayoutParams(dlp);
        b.addView(dic);

        TextView t = new TextView(this);
        t.setText(getString(R.string.view_bill_btn));
        t.setTextSize(15);
        t.setLetterSpacing(0.05f);
        t.setTextColor(Color.WHITE);
        Ds.heading(this, t);
        b.addView(t);

        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BillViewerActivity.setBill(html,
                        getString(R.string.bill_title), ref);
                startActivity(new android.content.Intent(
                        BillActivity.this,
                        BillViewerActivity.class));
            }
        });
        return b;
    }



    /** Green "Saved \u2022 24h valid" pill (v3.0.0 status line). */
    private View savedPill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundResource(R.drawable.ds_pill_green);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));

        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_check);
        ic.setColorFilter(Ds.g800(), PorterDuff.Mode.SRC_IN);
        int s = dp(12);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                s, s);
        ilp.rightMargin = dp(6);
        ic.setLayoutParams(ilp);
        pill.addView(ic);

        TextView t = new TextView(this);
        t.setText(getString(R.string.bill_saved_pill));
        t.setTextSize(11.5f);
        t.setTextColor(Ds.g800());
        Ds.heading(this, t);
        // v3.6.6: single line — Urdu text was wrapping to 2 lines in the pill.
        t.setSingleLine(true);
        pill.addView(t);
        return pill;
    }

    /** Holder card: consumer name, address, company pill. */
    /**
     * v3.6.16: strip the guardian suffix ("W/O ...", "S/O ...", "D/O ...",
     * "H/O ...") — show only the meter owner's name to save space.
     */
    private String stripGuardian(String name) {
        if (name == null) return "";
        // Match " W/O ", " S/O ", " D/O ", " H/O " (case-insensitive) and
        // everything after it.
        String[] parts = name.split(
                "(?i)\\s+[WSDH]/O\\s+");
        return parts.length > 0 ? parts[0].trim() : name.trim();
    }

    private View holderCard(BillInfo b, List<BillSummary.Field> fs) {
        String name = BillSummary.value(fs, "name");
        if (name == null || name.isEmpty()) return null;
        // v3.6.16: show only the meter owner's name (strip W/O, S/O, D/O,
        // H/O guardian part) — saves a line of space (user feedback).
        name = stripGuardian(name);
        LinearLayout c = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextSize(15);
        nm.setTextColor(Ds.ink());
        nm.setLineSpacing(dp(2), 1.5f);
        nm.setMaxLines(2);
        Ds.heading(this, nm);
        col.addView(nm);

        String addr = b.address == null ? "" : b.address.trim();
        String subdiv = b.subDivision == null ? ""
                : b.subDivision.trim();
        if (!subdiv.isEmpty() && !addr.contains(subdiv)) {
            addr = addr.isEmpty() ? subdiv : addr + "\n" + subdiv;
        }
        if (!addr.isEmpty()) {
            TextView ad = new TextView(this);
            ad.setText(addr);
            ad.setTextSize(12.5f);
            ad.setTextColor(Ds.muted());
            ad.setLineSpacing(dp(2), 1.55f);
            ad.setMaxLines(3);
            Ds.body(this, ad, 400);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(4);
            ad.setLayoutParams(alp);
            col.addView(ad);
        }
        row.addView(col);

        TextView pill = new TextView(this);
        pill.setText(disco.displayName);
        pill.setTextSize(10);
        if (Build.VERSION.SDK_INT >= 21) pill.setLetterSpacing(0.06f);
        pill.setTextColor(Ds.deepGreen());
        pill.setBackgroundResource(R.drawable.ds_copill);
        int pp = dp(10);
        pill.setPadding(pp, dp(4), pp, dp(4));
        Ds.heading(this, pill);
        row.addView(pill);
        c.addView(row);
        return c;
    }

    /**
     * v3.0.0 white payable card: TOTAL PAYABLE eyebrow, green 44sp
     * amount, amber due chip + units tag. The status row is OMITTED
     * on purpose — the parser has no genuine status field.
     */
    /**
     * v3.1.1: previous-dues chip, derived automatically from the
     * bill's printed Arrears figure. Amber "PICHLA BAQAYA: Rs X" when
     * arrears &gt; 0, green "KOI BAQAYA NAHI" when zero, nothing when
     * the bill has no arrears figure. This is PREVIOUS dues only —
     * the current bill's paid state is never shown, because the bill
     * document carries no such field and we must not invent it.
     */
    private View duesChip(BillInfo b) {
        ArrearsStatus.Result r = ArrearsStatus.derive(
                b == null ? null : b.arrears);
        if (r.kind == ArrearsStatus.Kind.UNKNOWN) return null;
        boolean has = r.kind == ArrearsStatus.Kind.HAS_ARREARS;

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundResource(has ? R.drawable.ds_pill_amber
                : R.drawable.ds_pill_green);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = dp(10);
        // v3.6.6: align to START so RTL (Urdu) puts it on the right, matching
        // the "total payable" label below.
        plp.gravity = Gravity.START;
        pill.setLayoutParams(plp);

        ImageView ic = new ImageView(this);
        ic.setImageResource(has ? R.drawable.ic_warning
                : R.drawable.ic_check);
        int tc = has ? Color.parseColor("#3A2700") : Ds.g800();
        ic.setColorFilter(tc, PorterDuff.Mode.SRC_IN);
        int s = dp(12);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                s, s);
        ilp.rightMargin = dp(6);
        ic.setLayoutParams(ilp);
        pill.addView(ic);

        TextView t = new TextView(this);
        if (has) {
            t.setText(getString(R.string.arrears_chip_has, r.printed));
        } else {
            t.setText(getString(R.string.arrears_chip_none));
        }
        t.setTextSize(11.5f);
        t.setTextColor(tc);
        Ds.heading(this, t);
        pill.addView(t);
        return pill;
    }

    private View payCard(BillInfo b, List<BillSummary.Field> fs) {
        String payable = BillSummary.value(fs, "payable");
        if (payable == null || payable.isEmpty()) return null;
        String due = BillSummary.value(fs, "due");
        String units = BillSummary.value(fs, "units");

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.ds_card_24);
        c.setPadding(dp(22), dp(22), dp(22), dp(22));
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), dp(12), dp(20), 0);
        c.setLayoutParams(lp);

        // v3.1.1: automatic previous-dues chip at the top of the pay
        // card (from the bill's Arrears figure; absent when unknown).
        View dues = duesChip(b);
        if (dues != null) c.addView(dues);

        TextView eb = new TextView(this);
        eb.setText(getString(R.string.hero_payable));
        eb.setTextSize(11);
        eb.setLetterSpacing(0.16f);
        eb.setTextColor(Ds.faint());
        Ds.heading(this, eb);
        c.addView(eb);

        LinearLayout amt = new LinearLayout(this);
        amt.setOrientation(LinearLayout.HORIZONTAL);
        amt.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(6);
        alp.bottomMargin = dp(12);
        amt.setLayoutParams(alp);

        TextView rs = new TextView(this);
        rs.setText("Rs");
        rs.setTextSize(19);
        rs.setTextColor(Ds.muted());
        rs.setTypeface(Fonts.archivo(this, 700));
        amt.addView(rs);

        TextView am = new TextView(this);
        am.setText("\u00a0" + payable.trim());
        am.setTextSize(44);
        am.setTextColor(Ds.g800());
        if (Build.VERSION.SDK_INT >= 21) am.setLetterSpacing(-0.02f);
        Ds.amount(this, am);
        amt.addView(am);
        c.addView(amt);

        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);

        int days = daysLeft(due);
        if (days >= 0 && due != null && !due.trim().isEmpty()) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundResource(R.drawable.ds_due_chip);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));

            ImageView cal = new ImageView(this);
            cal.setImageResource(R.drawable.ic_calendar);
            cal.setColorFilter(Ds.amberDark(),
                    PorterDuff.Mode.SRC_IN);
            int cs = dp(13);
            LinearLayout.LayoutParams clp =
                    new LinearLayout.LayoutParams(cs, cs);
            clp.rightMargin = dp(6);
            cal.setLayoutParams(clp);
            chip.addView(cal);

            TextView ct = new TextView(this);
            String daysText = days == 0
                    ? getString(R.string.due_today)
                    : getString(R.string.due_days_left, days);
            ct.setText(getString(R.string.due_chip, due.trim(),
                    daysText));
            ct.setTextSize(12.5f);
            ct.setTextColor(Ds.amberDark());
            Ds.heading(this, ct);
            // v3.6.6: single line — long Urdu text was overlapping the units tag.
            ct.setSingleLine(true);
            chip.addView(ct);
            prow.addView(chip);
        }

        TextView unitsTag = new TextView(this);
        unitsTag.setText(units == null || units.isEmpty() ? ""
                : getString(R.string.hero_units_value, units.trim()));
        unitsTag.setTextSize(13);
        unitsTag.setTextColor(Ds.muted());
        unitsTag.setGravity(Gravity.END);
        Ds.body(this, unitsTag, 600);
        unitsTag.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        prow.addView(unitsTag);
        c.addView(prow);
        return c;
    }

    /** Whole days from now until the due date's local midnight. */
    private int daysLeft(String dueDate) {
        long dueMs = BillReminder.parseDueDate(dueDate);
        if (dueMs < 0) return -1;
        double diffDays = (dueMs - System.currentTimeMillis())
                / 86400000.0;
        return (int) Math.ceil(diffDays);
    }

    /**
     * Compact late-fee text from REAL bill fields only (two tiers when
     * the bill printed them, else the single late line). Null when the
     * bill carries no late-fee data — never invented.
     */
    private String compactLate(BillInfo b,
            List<BillSummary.Field> fs) {
        String p1 = trim(b.payableTill25);
        String p2 = trim(b.payableAfter25);
        if (!p1.isEmpty() && !p2.isEmpty()) {
            return "Rs " + p1 + " \u2013 " + p2;
        } else if (!p1.isEmpty()) {
            return "Rs " + p1;
        } else if (!p2.isEmpty()) {
            return "Rs " + p2;
        }
        String sur = BillSummary.value(fs, "lateSurcharge");
        String aft = BillSummary.value(fs, "lateTotal");
        if (sur != null && !sur.isEmpty() && aft != null
                && !aft.isEmpty()) {
            return getString(R.string.bill_sum_late_full, sur, aft);
        } else if (sur != null && !sur.isEmpty()) {
            return getString(R.string.bill_sum_late_only, sur);
        } else if (aft != null && !aft.isEmpty()) {
            return getString(R.string.bill_sum_after_only, aft);
        }
        return null;
    }

    /** Detail card: billing month + meter reading + compact late row. */
    private View detailCard(BillInfo b, List<BillSummary.Field> fs) {
        String month = trim(b.billMonth);
        if (month.isEmpty()) month = trim(b.issueDate);
        String reading = trim(b.currReading);
        String late = compactLate(b, fs);
        if (month.isEmpty() && reading.isEmpty() && late == null) {
            return null;
        }

        LinearLayout c = card();
        c.setPadding(dp(16), dp(4), dp(16), dp(4));
        boolean first = true;
        if (!month.isEmpty()) {
            c.addView(detailRow(getString(R.string.bill_month_label),
                    month, false, false));
            first = false;
        }
        if (!reading.isEmpty()) {
            if (!first) c.addView(dashDivider());
            c.addView(detailRow(
                    getString(R.string.meter_reading_label),
                    reading, true, false));
            first = false;
        }
        if (late != null) {
            if (!first) c.addView(dashDivider());
            c.addView(detailRow(getString(R.string.late_row_label),
                    late, false, true));
        }
        return c;
    }


    /**
     * Secondary actions preserved from the old layout: UNIT CHECK
     * (gated on a usable reading) + refresh.
     */
    private View actionsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(20), dp(14), dp(20), 0);
        row.setLayoutParams(rlp);

        LinearLayout uc = new LinearLayout(this);
        uc.setOrientation(LinearLayout.HORIZONTAL);
        uc.setGravity(Gravity.CENTER);
        uc.setBackgroundResource(R.drawable.ds_btn_outline);
        uc.setMinimumHeight(dp(54));
        uc.setPadding(dp(16), 0, dp(16), 0);
        uc.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView vic = new ImageView(this);
        vic.setImageResource(R.drawable.ic_video);
        vic.setColorFilter(Ds.deepGreen(), PorterDuff.Mode.SRC_IN);
        int vs = dp(18);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                vs, vs);
        vlp.rightMargin = dp(8);
        vic.setLayoutParams(vlp);
        uc.addView(vic);

        TextView ut = new TextView(this);
        ut.setText(getString(R.string.meter_check_btn));
        ut.setTextSize(14.5f);
        ut.setLetterSpacing(0.05f);
        ut.setTextColor(Ds.deepGreen());
        Ds.heading(this, ut);
        uc.addView(ut);

        uc.setClickable(true);
        uc.setFocusable(true);
        uc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openMeterCheck(); }
        });
        row.addView(uc);

        LinearLayout rf = new LinearLayout(this);
        rf.setGravity(Gravity.CENTER);
        rf.setBackgroundResource(R.drawable.ds_dl_btn);
        int rs2 = dp(54);
        LinearLayout.LayoutParams rflp = new LinearLayout.LayoutParams(
                rs2, rs2);
        rflp.leftMargin = dp(10);
        rf.setLayoutParams(rflp);
        ImageView ric = new ImageView(this);
        ric.setImageResource(R.drawable.ic_refresh);
        int is = dp(20);
        ric.setLayoutParams(new LinearLayout.LayoutParams(is, is));
        rf.addView(ric);
        rf.setClickable(true);
        rf.setFocusable(true);
        rf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadBill(true); }
        });
        row.addView(rf);
        return row;
    }

    /** Grows the WebView to its full content height once loaded. */

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

}
