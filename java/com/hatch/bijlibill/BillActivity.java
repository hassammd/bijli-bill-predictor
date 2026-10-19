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
            // v3.6.54: new design — dark teal status bar.
            w.setStatusBarColor(Ds.ndPrimaryDark());
            w.getDecorView().setSystemUiVisibility(0);
        }

        // Root: app bar / scrolling content (weight 1) / banner.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // v3.6.54: new design app background.
        root.setBackgroundColor(Ds.ndAppBg());
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
        // v3.6.41: 8dp top to match card spacing.
        bannerBox.setPadding(Ui.dp(this, 16), Ui.dp(this, 8),
                Ui.dp(this, 16), Ui.dp(this, 8));
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
    // App bar — v3.6.54 new design: dark teal, rounded bottom corners
    // ------------------------------------------------------------------



    /** Creates a rounded-corner drawable programmatically. */

    /** Dark teal header bg with rounded BOTTOM corners only. */

    private View appBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // v3.6.55: standard app bar (reverted from v3.6.54 dark teal).
        // Uses new teal colors.
        bar.setBackgroundColor(Ds.ndPrimaryDark());
        bar.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Back button.
        // v3.6.60: light mint background, dark arrow (user feedback).
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chev_right);
        back.setRotation(180);
        back.setColorFilter(Ds.ndPrimaryDark(), PorterDuff.Mode.SRC_IN);
        back.setBackgroundResource(R.drawable.nd_back_btn_light);
        // v3.6.64: 28dp button, 8dp padding = 12dp icon (proportioned).
        int bs = dp(28);
        back.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        clp.rightMargin = dp(12);
        col.setLayoutParams(clp);

        TextView title = new TextView(this);
        title.setText(getString(R.string.bill_title));
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        Ds.heading(this, title);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString("cid".equals(mode)
                ? R.string.bill_id_masked : R.string.bill_ref_masked,
                SavedMeters.maskId(ref)));
        sub.setTextSize(12);
        sub.setTextColor(Color.parseColor("#B0C4C4"));
        Ds.body(this, sub, 400);
        col.addView(sub);
        bar.addView(col);

        bar.addView(Ds.langToggle(this));
        return bar;
    }

    // Small builders (v2.6.0 design)
    // ------------------------------------------------------------------

    /** v3.6.55: standard white card (reverted from v3.6.54). */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.ds_card);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(dp(2));
        Ds.tealShadow(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(8), dp(16), 0);
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
        lp.setMargins(dp(16), dp(8), dp(16), 0);
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
        // v3.6.42: more breathing room — utilize space better.
        r.setPadding(0, dp(12), 0, dp(12));

        TextView k = new TextView(this);
        k.setText(label);
        // v3.6.42: bigger text.
        k.setTextSize(15f);
        k.setTextColor(Ds.muted());
        Ds.body(this, k, 400);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        // v3.6.43: gap between label and value (were sticking together).
        klp.rightMargin = dp(12);
        k.setLayoutParams(klp);
        // v3.6.43: RTL support.
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            k.setTextDirection(
                    android.view.View.TEXT_DIRECTION_LOCALE);
        }
        r.addView(k);

        TextView v = new TextView(this);
        v.setText(value);
        // v3.6.45: all values same style — bold black (user feedback).
        v.setTextSize(15f);
        v.setTextColor(Ds.ink());
        Ds.heading(this, v);
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
        // v3.6.33: ad-gate — interstitial before fetch, then proceed.
        AdsManager.showInterstitialBefore(this, new Runnable() {
            @Override public void run() {
                doLoadBill(forceRefresh);
            }
        });
    }

    private void doLoadBill(final boolean forceRefresh) {
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
        // v3.6.53: line removed from bill page (user feedback).
        if (dailyRemLine == null) return;
        dailyRemLine.setVisibility(View.GONE);
        return;
        /*
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
        */
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
            lp.setMargins(dp(16), dp(8), dp(16), 0);
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

        // v3.6.53: "Rozana yaad-dihani ON" line REMOVED — do not re-add.
        // (dailyRemLine kept null; updateDailyReminderLine is a no-op.)

        if (p.parseOk && p.info != null) {
            BillInfo b = p.info;
            List<BillSummary.Field> fs = BillSummary.fields(b);

            View holder = holderCard(b, fs);
            if (holder != null) inner.addView(holder);
            View pay = payCard(b, fs);
            if (pay != null) inner.addView(pay);
            // v3.6.55: "View Full Bill" button BELOW pay card (v3.6.53 layout).
            View vfb = viewBillBtn(p.html);
            if (vfb != null) inner.addView(vfb);
            // v3.3.0: "Andaza vs Asal Bill" — compare the meter's
            // pending estimate against this fresh bill (no card when
            // the user never ran an estimate).
            addAccuracyCard(inner, b, fs);
            View detail = detailCard(b, fs);
            if (detail != null) inner.addView(detail);

            scheduleDueReminder(b);
            // v3.1.1: DAILY reminder — kept, but the status line is
            // gone (v3.6.53 user feedback). Logic unchanged.
            scheduleDailyReminder(b, fs);
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

        AdsManager.loadBannerInto(this, bannerBox);
    }

    /**
     * v3.6.54: mint status bar — "Saved • 24h valid" + divider +
     * "Reminder: ...". Matches the new design.
     */

    /**
     * v3.6.54: trust badge — mint card with shield icon.
     */

    /**
     * v3.6.9: prominent "View Full Bill" button — opens BillViewerActivity
     * (full-screen WebView with back button).
     */
    private View viewBillBtn(final String html) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        // v3.6.58: gradient button (user request).
        b.setBackgroundResource(R.drawable.nd_btn_solid);
        b.setMinimumHeight(dp(56));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(8), dp(16), 0);
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
        // v3.6.43: single line (Urdu was wrapping to 2 lines).
        t.setSingleLine(true);
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            t.setTextDirection(
                    android.view.View.TEXT_DIRECTION_LOCALE);
        }
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
        // everything after it. v3.6.43: also " So ", " Wo ", " Do ", " Ho "
        // (without slash, as seen in some bills).
        String[] parts = name.split(
                "(?i)\\s+[WSDH]/O\\s+|\\s+[WSDH]o\\s+");
        return parts.length > 0 ? parts[0].trim() : name.trim();
    }

    private View holderCard(BillInfo b, List<BillSummary.Field> fs) {
        String name = BillSummary.value(fs, "name");
        if (name == null || name.isEmpty()) return null;
        // v3.6.16: show only the meter owner's name (strip W/O, S/O, D/O,
        // H/O guardian part) — saves a line of space (user feedback).
        name = stripGuardian(name);
        boolean urdu = LocaleHelper.isUrdu(this);

        LinearLayout c = card();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Name + address column.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nm = new TextView(this);
        nm.setText(name);
        // v3.6.55: 17sp bold (v3.6.53 spec).
        nm.setTextSize(17);
        nm.setTextColor(Ds.ndDarkText());
        Ds.heading(this, nm);
        nm.setMaxLines(2);
        if (urdu && Build.VERSION.SDK_INT >= 17) {
            nm.setTextDirection(View.TEXT_DIRECTION_RTL);
        }
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
            ad.setTextSize(13);
            ad.setTextColor(Ds.ndSecondary());
            ad.setMaxLines(2);
            Ds.body(this, ad, 400);
            if (urdu && Build.VERSION.SDK_INT >= 17) {
                ad.setTextDirection(View.TEXT_DIRECTION_RTL);
            }
            col.addView(ad);
        }
        row.addView(col);

        // LESCO pill on right.
        TextView pill = new TextView(this);
        pill.setText(disco.displayName);
        pill.setTextSize(12);
        pill.setTextColor(Ds.ndPrimaryTeal());
        Ds.heading(this, pill);
        pill.setBackgroundResource(R.drawable.ds_pill_green);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
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
        // v3.6.38: compact.
        plp.bottomMargin = dp(6);
        // v3.6.6: align to START so RTL (Urdu) puts it on the right, matching
        // the "total payable" label below.
        // v3.6.46: explicit END in Urdu (parent is LTR, START=left).
        plp.gravity = LocaleHelper.isUrdu(this)
                ? Gravity.END : Gravity.START;
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

        TextView t = new TextView(this);
        if (has) {
            t.setText(getString(R.string.arrears_chip_has, r.printed));
        } else {
            t.setText(getString(R.string.arrears_chip_none));
        }
        t.setTextSize(11.5f);
        t.setTextColor(tc);
        Ds.heading(this, t);
        // v3.6.68: tick on RIGHT in Urdu (RTL).
        boolean isUrdu = LocaleHelper.isUrdu(this);
        if (isUrdu) {
            pill.addView(t);
            LinearLayout.LayoutParams ilp2 = new LinearLayout.LayoutParams(s, s);
            ilp2.leftMargin = dp(6);
            ic.setLayoutParams(ilp2);
            pill.addView(ic);
        } else {
            pill.addView(ic);
            pill.addView(t);
        }
        return pill;
    }

    /**
     * v3.6.54: new design pay card — document icon + dues status,
     * huge amount, illustration, due chip + units, View Full Bill
     * button INSIDE the card.
     */
    private View payCard(BillInfo b, List<BillSummary.Field> fs) {
        String payable = BillSummary.value(fs, "payable");
        if (payable == null || payable.isEmpty()) return null;
        String due = BillSummary.value(fs, "due");
        String units = BillSummary.value(fs, "units");
        boolean urdu = LocaleHelper.isUrdu(this);
        // v3.6.66: keep English for daysLeft parsing, Urdu for display.
        String dueDisplay = due;
        if (urdu && due != null) dueDisplay = urduDate(due);

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.ds_card_24);
        // v3.6.53: 30dp vertical, 18dp horizontal padding.
        c.setPadding(dp(18), dp(30), dp(18), dp(30));
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(8), dp(16), 0);
        c.setLayoutParams(lp);

        // Dues chip at top.
        View dues = duesChip(b);
        if (dues != null) {
            LinearLayout.LayoutParams dlp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            // v3.6.53: END in Urdu (right side).
            dlp.gravity = urdu ? Gravity.END : Gravity.START;
            dues.setLayoutParams(dlp);
            c.addView(dues);
        }

        // "TOTAL PAYABLE" label.
        TextView eb = new TextView(this);
        eb.setText(getString(R.string.hero_payable));
        eb.setTextSize(11);
        eb.setLetterSpacing(0.08f);
        eb.setTextColor(Ds.ndSecondary());
        Ds.heading(this, eb);
        if (urdu) {
            eb.setGravity(Gravity.END);
            if (Build.VERSION.SDK_INT >= 17) {
                eb.setTextDirection(View.TEXT_DIRECTION_RTL);
            }
        }
        LinearLayout.LayoutParams elp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        eb.setLayoutParams(elp);
        c.addView(eb);

        // Amount: "Rs 18827" HUGE, LEFT-aligned.
        LinearLayout amt = new LinearLayout(this);
        amt.setOrientation(LinearLayout.HORIZONTAL);
        amt.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(4);
        // v3.6.53: 16dp gap between amount and due chip.
        alp.bottomMargin = dp(16);
        amt.setLayoutParams(alp);

        TextView rs = new TextView(this);
        rs.setText("Rs");
        rs.setTextSize(19);
        rs.setTextColor(Ds.ndSecondary());
        rs.setTypeface(Fonts.archivo(this, 700));
        rs.setSingleLine(true);
        amt.addView(rs);

        TextView am = new TextView(this);
        am.setText("\u00a0" + payable.trim());
        am.setTextSize(44);
        am.setTextColor(Ds.ndDarkText());
        am.setTypeface(Fonts.archivo(this, 800));
        am.setSingleLine(true);
        amt.addView(am);
        c.addView(amt);

        // Due chip row.
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
            // v3.6.53: full-width + centered in Urdu, wrap in English.
            if (urdu) {
                chip.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                chip.setGravity(Gravity.CENTER_VERTICAL
                        | Gravity.CENTER_HORIZONTAL);
            }

            ImageView cal = new ImageView(this);
            cal.setImageResource(R.drawable.ic_calendar);
            cal.setColorFilter(Ds.ndPrimaryTeal(),
                    PorterDuff.Mode.SRC_IN);
            int cs = dp(13);
            LinearLayout.LayoutParams clp =
                    new LinearLayout.LayoutParams(cs, cs);
            if (urdu) {
                clp.leftMargin = dp(6);
            } else {
                clp.rightMargin = dp(6);
            }
            cal.setLayoutParams(clp);

            TextView ct = new TextView(this);
            String daysText = days == 0
                    ? getString(R.string.due_today)
                    : getString(R.string.due_days_left, days);
            ct.setText(getString(R.string.due_chip, dueDisplay.trim(),
                    daysText));
            ct.setTextSize(12.5f);
            ct.setTextColor(Ds.ndDarkText());
            Ds.heading(this, ct);
            // v3.6.53: letter spacing Urdu only.
            if (urdu) {
                ct.setLetterSpacing(0.12f);
                ct.setSingleLine(false);
                ct.setMaxLines(2);
                ct.setLineSpacing(dp(3), 1.2f);
                if (Build.VERSION.SDK_INT >= 17) {
                    ct.setTextDirection(View.TEXT_DIRECTION_RTL);
                }
                // Icon on right in Urdu: text first, then icon.
                chip.addView(ct);
                chip.addView(cal);
            } else {
                ct.setSingleLine(true);
                chip.addView(cal);
                chip.addView(ct);
            }
            prow.addView(chip);
        }

        // Units: shown in English, HIDDEN in Urdu (v3.6.53).
        TextView unitsTag = new TextView(this);
        unitsTag.setText(units == null || units.isEmpty() ? ""
                : getString(R.string.hero_units_value, units.trim()));
        unitsTag.setTextSize(13);
        unitsTag.setTextColor(Ds.ndSecondary());
        unitsTag.setGravity(Gravity.END);
        Ds.body(this, unitsTag, 600);
        unitsTag.setPadding(dp(8), 0, dp(4), 0);
        unitsTag.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (urdu) {
            unitsTag.setVisibility(View.GONE);
        }
        prow.addView(unitsTag);
        c.addView(prow);
        return c;
    }


    /**
     * v3.6.54: dark teal "View Full Bill →" button for inside the
     * pay card. Keeps the ad-gate (interstitial before opening).
     */

    /** Whole days from now until the due date's local midnight. */
    /** v3.6.65: Convert "27 OCT 26" → "27 اکتوبر" (dynamic, all months). */
    private String urduDate(String date) {
        if (date == null) return date;
        String[] months = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                           "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        String[] urduMonths = {"جنوری", "فروری", "مارچ", "اپریل", "مئی", "جون",
                               "جولائی", "اگست", "ستمبر", "اکتوبر", "نومبر", "دسمبر"};
        String[] parts = date.trim().split("\\s+");
        if (parts.length < 2) return date;
        String day = parts[0];
        String mon = parts[1].toUpperCase();
        for (int i = 0; i < months.length; i++) {
            if (mon.equals(months[i])) {
                return day + " " + urduMonths[i];
            }
        }
        return date;
    }

    private int daysLeft(String dueDate) {        long dueMs = BillReminder.parseDueDate(dueDate);
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

    /** v3.6.54: "Billing Details" card — header with calendar icon
     * + month pill, icon rows with dashed dividers. */
    private View detailCard(BillInfo b, List<BillSummary.Field> fs) {
        LinearLayout c = card();
        boolean urdu = LocaleHelper.isUrdu(this);

        // v3.6.60: billing month with fallbacks — row MUST show.
        String month = b.billMonth;
        if (month == null || month.isEmpty()) month = b.issueDate;
        if ((month == null || month.isEmpty()) && b.dueDate != null) {
            // Derive "MON YY" from due date.
            try {
                String[] parts = b.dueDate.trim().split("[\\s,]+");
                if (parts.length >= 2) {
                    String m = parts[0].toUpperCase();
                    if (m.length() > 3) m = m.substring(0, 3);
                    String y = parts[parts.length - 1];
                    if (y.length() == 4) y = y.substring(2);
                    month = m + " " + y;
                } else {
                    month = b.dueDate;
                }
            } catch (Exception e) {
                month = b.dueDate;
            }
        }
        if (month == null || month.isEmpty()) {
            // Last resort: current month.
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("MMM yy",
                            java.util.Locale.ENGLISH);
            month = sdf.format(new java.util.Date()).toUpperCase();
        }
        String reading = b.currReading;
        String late = compactLate(b, fs);

        boolean first = true;
        if (month != null && !month.isEmpty()) {
            if (!first) c.addView(dashDivider());
            c.addView(detailRow(
                    getString(R.string.bill_month_label), month, urdu));
            first = false;
        }
        if (reading != null && !reading.isEmpty()) {
            if (!first) c.addView(dashDivider());
            c.addView(detailRow(
                    getString(R.string.meter_reading_label), reading, urdu));
            first = false;
        }
        if (late != null && !late.isEmpty()) {
            if (!first) c.addView(dashDivider());
            c.addView(detailRow(
                    getString(R.string.late_row_label), late, urdu));
            first = false;
        }
        return first ? null : c;
    }

    /** v3.6.55: simple detail row — label left, value right (bold). */
    private View detailRow(String label, String value, boolean urdu) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(10), dp(4), dp(10));

        TextView k = new TextView(this);
        k.setText(label);
        k.setTextSize(15f);
        k.setTextColor(Ds.ndSecondary());
        Ds.body(this, k, 400);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        klp.rightMargin = dp(12);
        k.setLayoutParams(klp);
        if (urdu && Build.VERSION.SDK_INT >= 17) {
            k.setTextDirection(View.TEXT_DIRECTION_RTL);
        }
        r.addView(k);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(15f);
        v.setTextColor(Ds.ndDarkText());
        Ds.heading(this, v);
        if (urdu && Build.VERSION.SDK_INT >= 17) {
            v.setTextDirection(View.TEXT_DIRECTION_RTL);
        }
        r.addView(v);
        return r;
    }

    /** Grows the WebView to its full content height once loaded. */

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

}
