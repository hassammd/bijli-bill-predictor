package com.hatch.bijlibill;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Ad framework for the app.
 *
 * CURRENT MODE: TEST-MODE (no AdMob SDK bundled — see note below).
 * The full wiring is real: call sites, frequency logic, config-driven IDs,
 * manifest APPLICATION_ID meta-data. Only the final SDK binding is stubbed.
 *
 * WHY TEST-MODE: this project uses the lightweight manual build pipeline
 * (javac + d8 + aapt2, no Gradle). The play-services-ads AAR has many
 * transitive dependencies (ads-lite, basement, measurement, …) that make a
 * manual AAR merge fragile. So v1 ships with this clean slot framework:
 * banner container + interstitial trigger points, driven by AdsConfig's
 * official Google TEST ids. Going production is a ~5-minute job:
 *
 *   PRODUCTION SWAP (in this file, each marked below):
 *   1. Add to build: implementation 'com.google.android.gms:play-services-ads:23.x'
 *   2. init():            MobileAds.initialize(context, status -> {});
 *   3. loadBannerInto():   AdView ad = new AdView(activity);
 *                          ad.setAdSize(AdSize.BANNER);
 *                          ad.setAdUnitId(AdsConfig.BANNER_AD_UNIT_ID);
 *                          container.addView(ad);
 *                          ad.loadAd(new AdRequest.Builder().build());
 *   4. Interstitial:      InterstitialAd.load(activity, AdsConfig.INTERSTITIAL_AD_UNIT_ID,
 *                          new AdRequest.Builder().build(), callback -> { mAd = …; });
 *                          then mAd.show(activity) at the trigger point.
 *   5. Replace test IDs in AdsConfig.java + manifest meta-data with real ones.
 */
public final class AdsManager {

    private static final String PREFS = "bijli_bill_ads";
    private static final String KEY_CALC_COUNT = "calc_count";
    private static boolean initialized = false;

    private AdsManager() {}

    /** Call once from MainActivity.onCreate(). */
    public static void init(Context ctx) {
        if (initialized) return;
        initialized = true;
        // PRODUCTION SWAP: MobileAds.initialize(ctx, status -> {});
    }

    /**
     * Renders the banner slot. In production this creates a real AdView;
     * in test-mode it renders a clearly-labeled placeholder so layout,
     * sizing and placement can be verified without the SDK.
     */
    public static void loadBannerInto(Activity activity, ViewGroup container) {
        container.removeAllViews();
        // v3.6.17: consistent padding on the container — banner height
        // same on all pages (user feedback).
        int d = (int) (activity.getResources().getDisplayMetrics().density
                + 0.5f);
        container.setPadding(16 * d, 6 * d, 16 * d, 6 * d);
        if (!AdsConfig.ADS_ENABLED || BillingStub.isPremium(activity)) return;

        // PRODUCTION SWAP: replace the block below with the real AdView code
        // from the class javadoc (uses AdsConfig.BANNER_AD_UNIT_ID).
        TextView test = new TextView(activity);
        test.setText("TEST AD  \u2022  banner slot (" + AdsConfig.BANNER_AD_UNIT_ID + ")");
        test.setTextSize(11);
        test.setTextColor(Color.parseColor("#616161"));
        test.setGravity(Gravity.CENTER);
        test.setBackgroundColor(Color.parseColor("#E8EAF6"));
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density + 0.5f);
        test.setPadding(pad, (int) (pad * 2.2), pad, (int) (pad * 2.2));
        container.addView(test, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Call after every successful bill calculation. Shows an interstitial
     * every AdsConfig.INTERSTITIAL_EVERY_N_CALCULATIONS calculations.
     */
    public static void onCalculationDone(Activity activity) {
        if (!AdsConfig.ADS_ENABLED || BillingStub.isPremium(activity)) return;
        SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int n = p.getInt(KEY_CALC_COUNT, 0) + 1;
        p.edit().putInt(KEY_CALC_COUNT, n).apply();
        if (n % AdsConfig.INTERSTITIAL_EVERY_N_CALCULATIONS == 0) {
            showInterstitial(activity);
        }
    }

    private static void showInterstitial(final Activity activity) {
        // PRODUCTION SWAP: if (mInterstitialAd != null) { mInterstitialAd.show(activity); reload…; return; }
        // Test-mode: a dialog standing in for the interstitial, so the trigger
        // point and frequency are verifiable end-to-end.
        TextView body = new TextView(activity);
        body.setText("TEST INTERSTITIAL AD\n\n" + AdsConfig.INTERSTITIAL_AD_UNIT_ID +
                "\n\n(Production: real AdMob interstitial yahan show hoga.)");
        body.setGravity(Gravity.CENTER);
        body.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density + 0.5f);
        body.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(activity)
                .setTitle("Sponsored")
                .setView(body)
                .setPositiveButton("Close", null)
                .show();
    }
}
