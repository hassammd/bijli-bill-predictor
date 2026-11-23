package com.hatch.bijlibill;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * Ad framework for the app — REAL Google Mobile Ads SDK
 * (play-services-ads 23.6.0), wired behind the existing call sites.
 *
 * PLACEMENT RULES (zero-risk audit §2-B, 2026-11-24 — these are
 * the law for every ad in the app):
 *   - Banner: one slot per screen, standard AdSize.BANNER, clearly
 *     labelled (the SDK draws its own "Ad" badge; the slot also
 *     carries an accessibility label). The slot sits at least
 *     16dp above the bottom nav with a divider line between the
 *     banner and the nav — never flush against the nav tabs.
 *     If an ad fails to load, the slot collapses (no empty box).
 *   - Interstitial: NEVER before the user's action. No gate on
 *     bill fetch, full-bill view, or unit-check result. An
 *     interstitial may show ONLY after the bill/result has
 *     displayed (see maybeShowInterstitialAfter), and at most
 *     ONE per session with a minimum 5-minute gap between shows.
 *     Never on app open, never on back press, never on exit.
 *   - The interstitial is PRELOADED at init; if nothing is loaded
 *     when an "after" point is reached, nothing shows — the user
 *     is never made to wait for an ad.
 *
 * IDs come from AdsConfig (Google TEST ids in this phase; real ids
 * are a config-only swap later — no code change needed).
 */
public final class AdsManager {

    private static final String PREFS = "bijli_bill_ads";
    private static final String KEY_LAST_INTERSTITIAL =
            "last_interstitial_ms";

    /** Audit §2-B #15: minimum gap between interstitial shows. */
    private static final long INTERSTITIAL_MIN_GAP_MS = 5 * 60 * 1000;

    private static boolean initialized = false;
    private static InterstitialAd mInterstitialAd = null;
    private static boolean sLoadingInterstitial = false;
    /** Audit §2-B #15: at most ONE interstitial per session. */
    private static boolean sShownThisSession = false;

    private AdsManager() {}

    /** Call once from HomeActivity.onCreate(). */
    public static void init(Context ctx) {
        if (initialized) return;
        initialized = true;
        final Context appCtx = ctx.getApplicationContext();
        MobileAds.initialize(appCtx,
                new OnInitializationCompleteListener() {
                    @Override public void onInitializationComplete(
                            InitializationStatus status) {
                        preloadInterstitial(appCtx);
                    }
                });
    }

    /** Loads the next interstitial in the background. Safe to call
     *  any time; no-ops while a load is in flight or one is ready. */
    private static void preloadInterstitial(Context ctx) {
        if (sLoadingInterstitial || mInterstitialAd != null) return;
        sLoadingInterstitial = true;
        InterstitialAd.load(ctx.getApplicationContext(),
                AdsConfig.INTERSTITIAL_AD_UNIT_ID,
                new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override public void onAdLoaded(InterstitialAd ad) {
                        sLoadingInterstitial = false;
                        mInterstitialAd = ad;
                    }

                    @Override public void onAdFailedToLoad(LoadAdError err) {
                        sLoadingInterstitial = false;
                        mInterstitialAd = null;
                    }
                });
    }

    /**
     * The ONLY interstitial trigger. Call AFTER the bill or the
     * unit-check result has displayed — never before the action.
     * Shows at most one interstitial per session and only if at
     * least 5 minutes passed since the last one. If no ad is
     * preloaded yet, it starts a background load and shows
     * nothing — the user is never blocked on an ad.
     */
    public static void maybeShowInterstitialAfter(final Activity activity) {
        if (!AdsConfig.ADS_ENABLED || BillingStub.isPremium(activity)) return;
        if (sShownThisSession) return;
        SharedPreferences p = activity.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = p.getLong(KEY_LAST_INTERSTITIAL, 0);
        if (now - last < INTERSTITIAL_MIN_GAP_MS) return;
        if (mInterstitialAd == null) {
            preloadInterstitial(activity);
            return;
        }
        final InterstitialAd ad = mInterstitialAd;
        mInterstitialAd = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdDismissedFullScreenContent() {
                // Session cap: no reload — one interstitial per session.
            }

            @Override public void onAdFailedToShowFullScreenContent(
                    AdError adError) {
                // Nothing to retry this session.
            }
        });
        sShownThisSession = true;
        p.edit().putLong(KEY_LAST_INTERSTITIAL, now).apply();
        ad.show(activity);
    }

    /**
     * Renders the banner slot with a real AdView. The container
     * gets 16dp of space below the ad plus a divider line, so the
     * banner is never flush against the bottom nav (audit §2-B
     * #13). A failed load collapses the slot. A previously loaded
     * AdView in the same container is destroyed first (the
     * MeterCheck and Bill screens re-render into one bannerBox).
     */
    public static void loadBannerInto(Activity activity, ViewGroup container) {
        Object old = container.getTag();
        if (old instanceof AdView) {
            ((AdView) old).destroy();
        }
        container.removeAllViews();
        int d = (int) (activity.getResources().getDisplayMetrics().density
                + 0.5f);
        container.setPadding(16 * d, 6 * d, 16 * d, 16 * d);
        if (!AdsConfig.ADS_ENABLED || BillingStub.isPremium(activity)) return;

        final ViewGroup box = container;
        AdView adView = new AdView(activity);
        adView.setAdSize(AdSize.BANNER);
        adView.setAdUnitId(AdsConfig.BANNER_AD_UNIT_ID);
        adView.setContentDescription(activity.getString(R.string.ad_label));
        adView.setAdListener(new AdListener() {
            @Override public void onAdLoaded() {
                box.setVisibility(View.VISIBLE);
            }

            @Override public void onAdFailedToLoad(LoadAdError error) {
                // No ad available — collapse the slot instead of
                // leaving an empty box on the screen.
                box.setVisibility(View.GONE);
            }
        });
        container.addView(adView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Divider between the banner and the bottom nav (audit §2-B #13).
        View divider = new View(activity);
        divider.setBackgroundColor(Color.parseColor("#DDDDDD"));
        int oneDp = Math.max(1, d);
        container.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, oneDp));
        container.setTag(adView);
        adView.loadAd(new AdRequest.Builder().build());
    }
}
