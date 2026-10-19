package com.hatch.bijlibill;

import android.app.Activity;
import android.graphics.Color;
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
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * v3.6.9: full-screen bill viewer (user decision — like competitor apps).
 * The bill WebView lives here ALONE (no parent ScrollView), so scrolling,
 * pinch-zoom and pan all work natively and perfectly in every direction.
 * Opened from BillActivity's "View Bill" button; back button returns.
 */
public class BillViewerActivity extends Activity {

    /** HTML handed over by BillActivity (too large for an Intent extra). */
    private static String pendingHtml;
    private static String pendingTitle;
    private static String pendingRef;

    public static void setBill(String html, String title, String ref) {
        pendingHtml = html;
        pendingTitle = title;
        pendingRef = ref;
    }

    /** Backwards-compatible overload. */
    public static void setBill(String html, String title) {
        setBill(html, title, null);
    }

    private WebView webView;
    private ProgressBar loader;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** v3.6.15: status bar height for top padding. */
    private int getStatusBarHeight() {
        int h = 0;
        int id = getResources().getIdentifier("status_bar_height",
                "dimen", "android");
        if (id > 0) {
            h = getResources().getDimensionPixelSize(id);
        }
        return h;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v3.6.69: status bar light teal (user request).
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(
                    android.graphics.Color.parseColor("#11998E"));
        }
        // v3.6.35: ad-gate before viewing full bill.
        // v3.6.42: skip ad on recreate (e.g. language toggle) — the
        // ad was breaking the toggle flow.
        if (savedInstanceState != null) {
            doOnCreate(savedInstanceState);
        } else {
            AdsManager.showInterstitialBefore(this, new Runnable() {
                @Override public void run() {
                    doOnCreate(savedInstanceState);
                }
            });
        }
    }

    private void doOnCreate(Bundle savedInstanceState) {
        // v3.6.13: don't null these — the activity recreates on language
        // toggle, and we need the bill HTML to survive.
        final String html = pendingHtml;
        final String title = pendingTitle;
        final String ref = pendingRef;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // ---- Header (same as BillActivity, but NO language toggle) ----
        int d = (int) (getResources().getDisplayMetrics().density + 0.5f);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // v3.6.15: top padding includes status bar height — header was
        // stuck to the top edge (user feedback).
        int sbh = getStatusBarHeight();
        bar.setPadding(dp(20), dp(14) + sbh, dp(20), dp(10));
        // v3.6.68: dark green header (like other pages).
        bar.setBackgroundColor(Ds.ndTealAccent());

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chev_right);
        back.setRotation(180);
        // v3.6.68: dark arrow on mint (standardized).
        back.setColorFilter(Ds.ndPrimaryDark(),
                android.graphics.PorterDuff.Mode.SRC_IN);
        int bs = dp(28);
        back.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        back.setBackgroundResource(R.drawable.nd_back_btn_light);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView tt = new TextView(this);
        tt.setText(title != null ? title
                : getString(R.string.bill_title));
        tt.setTextSize(18);
        // v3.6.68: white on dark green.
        tt.setTextColor(Color.WHITE);
        Ds.heading(this, tt);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ttlp.leftMargin = dp(12);
        tt.setLayoutParams(ttlp);
        // v3.6.15: single-line title, no ref subtitle — header was
        // overcrowded (user feedback). Ref visible on previous screen.
        bar.addView(tt);

        LinearLayout spacer = new LinearLayout(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                0, 1, 1f));
        bar.addView(spacer);

        // v3.6.13: language toggle (same as other pages — user request)
        android.view.View toggle = Ds.langToggle(this);
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp2.rightMargin = dp(8);
        toggle.setLayoutParams(tlp2);
        bar.addView(toggle);

        // Download button (in header, properly placed)
        ImageView dl = new ImageView(this);
        dl.setImageResource(R.drawable.ic_download);
        // v3.6.68: dark on mint (standardized).
        dl.setColorFilter(Ds.ndPrimaryDark(),
                android.graphics.PorterDuff.Mode.SRC_IN);
        int dls = dp(28);
        dl.setLayoutParams(new LinearLayout.LayoutParams(dls, dls));
        dl.setBackgroundResource(R.drawable.nd_back_btn_light);
        dl.setPadding(dp(8), dp(8), dp(8), dp(8));
        dl.setClickable(true);
        dl.setFocusable(true);
        dl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { downloadBill(ref); }
        });
        bar.addView(dl);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Loader (while the bill renders) ----
        loader = new ProgressBar(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                dp(48), dp(48));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = dp(60);
        loader.setLayoutParams(llp);

        // ---- Bill in a card (paper-like, no weird empty space) ----
        // v3.6.18: WebView fills the card and scrolls/zooms natively —
        // pinch-zoom + pan work (user feedback: zoom/pan was broken).
        // No outer ScrollView (was conflicting with WebView touch).
        // v3.6.14: distinct gray background so the white bill card pops
        // like a paper document (document-viewer look).
        root.setBackgroundColor(Color.parseColor("#E3E6E4"));

        // v3.6.22: ScrollView + natural-height WebView (user: "height fix
        // na kro"). WebView WRAP_CONTENT — bill jitna lamba, utna. Outer
        // ScrollView vertical scroll karta hai. Zoom enabled hai.
        android.widget.ScrollView scroll =
                new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, slp);

        LinearLayout cardWrap = new LinearLayout(this);
        cardWrap.setOrientation(LinearLayout.VERTICAL);
        cardWrap.setBackgroundResource(R.drawable.ds_card_24);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            cardWrap.setElevation(dp(3));
        }
        int m = dp(16);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.setMargins(m, m, m, m);
        cardWrap.setLayoutParams(clp2);
        cardWrap.setPadding(0, 0, 0, 0);
        scroll.addView(cardWrap);
        cardWrap.addView(loader);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(120);
        // v3.6.22: zoom enabled, natural height (not fixed).
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        webView.setVisibility(View.INVISIBLE);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.post(new Runnable() {
                    @Override public void run() {
                        loader.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
        // v3.6.22: WRAP_CONTENT — natural bill height, height fix nahi.
        // v3.6.29: free pan when zoomed — when WebView scale > 1, block
        // ScrollView from stealing touch (was causing stuck/janky pan).
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (webView.getScale() > 1.01f) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                } else {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        });
        cardWrap.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (html != null && !html.isEmpty()) {
            final WebView wv = webView;
            BillWebRenderer.render(
                    new BillWebRenderer.WebViewLike() {
                        @Override public void loadDataWithBaseURL(
                                String baseUrl, String data, String mimeType,
                                String encoding, String historyUrl) {
                            wv.loadDataWithBaseURL(baseUrl, data, mimeType,
                                    encoding, historyUrl);
                        }
                    }, html);
        } else {
            loader.setVisibility(View.GONE);
        }

        // v3.6.16: test banner ad (user request — see ad on all pages).
        LinearLayout bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bannerBox);
        AdsManager.loadBannerInto(this, bannerBox);
        // v3.6.28: viewer 50dp (user fine-tune: 48dp se thora aur).
        // Other pages use 8dp default — proven perfect.
        root.addView(Ds.bottomNav(this, Ds.TAB_BILLS, 50));
    }

    /**
     * v3.6.9: download bill as PDF (moved from BillActivity — the WebView
     * lives here now). Renders the full document page-by-page (A4) into a
     * PrintedPdfDocument under Downloads/BijliBill.
     */
    private void downloadBill(final String ref) {
        if (webView == null) {
            android.widget.Toast.makeText(this,
                    R.string.download_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int vw = webView.getWidth();
            if (vw <= 0) vw = dp(360);
            webView.measure(
                    View.MeasureSpec.makeMeasureSpec(vw,
                            View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,
                            View.MeasureSpec.UNSPECIFIED));
            int contentH = webView.getMeasuredHeight();
            if (contentH <= 0) {
                try {
                    contentH = (int) (webView.getContentHeight()
                            * webView.getScale());
                } catch (Exception ignored) {
                }
            }
            if (contentH <= 0) {
                throw new IllegalStateException("empty page");
            }
            webView.layout(0, 0, vw, contentH);

            int pageH = (int) (vw * 842.0 / 595.0); // A4 ratio
            int pages = BillPdf.pageCount(contentH, pageH);
            android.print.pdf.PrintedPdfDocument doc =
                    new android.print.pdf.PrintedPdfDocument(this,
                            new android.print.PrintAttributes.Builder()
                                    .setMediaSize(android.print
                                            .PrintAttributes.MediaSize
                                            .ISO_A4)
                                    .setResolution(new android.print
                                            .PrintAttributes.Resolution(
                                            "pdf", "pdf", 300, 300))
                                    .setMinMargins(android.print
                                            .PrintAttributes.Margins
                                            .NO_MARGINS)
                                    .build());
            for (int i = 0; i < pages; i++) {
                android.print.pdf.PrintedPdfDocument.Page page =
                        doc.startPage(
                                new android.print.pdf.PrintedPdfDocument
                                        .PageInfo.Builder(vw, pageH, i)
                                        .create());
                android.graphics.Canvas canvas = page.getCanvas();
                canvas.save();
                canvas.translate(0, -i * pageH);
                webView.draw(canvas);
                canvas.restore();
                doc.finishPage(page);
            }
            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream();
            doc.writeTo(bos);
            doc.close();
            webView.requestLayout();

            String digits = ref == null ? ""
                    : ref.replaceAll("[^0-9]", "");
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd",
                    java.util.Locale.US).format(new java.util.Date());
            String name = "BijliBill-" + digits + "-" + stamp + ".pdf";

            java.io.File dir;
            if (Build.VERSION.SDK_INT >= 29) {
                dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS),
                        "BijliBill");
            } else {
                dir = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(),
                        "Download/BijliBill");
            }
            if (!dir.exists()) dir.mkdirs();
            java.io.File out = new java.io.File(dir, name);
            java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(out);
            fos.write(bos.toByteArray());
            fos.close();

            android.widget.Toast.makeText(this,
                    getString(R.string.download_saved, out.getName()),
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    R.string.download_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ViewGroup p = (ViewGroup) webView.getParent();
            if (p != null) p.removeView(webView);
            webView.destroy();
            webView = null;
        }
        // v3.6.13: clear static bill only when truly finishing (not on
        // recreate from language toggle).
        if (isFinishing()) {
            pendingHtml = null;
            pendingTitle = null;
            pendingRef = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
