package com.hatch.bijlibill;

/**
 * ============================================================================
 * ADS CONFIG — *** REAL ADMOB IDS (live since 2026-11-24) ***
 * ============================================================================
 * How to get real IDs (full steps in README.txt):
 *   1. Create an AdMob account at apps.admob.com
 *   2. Add your app -> AdMob gives you an APP ID (ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY)
 *   3. Create one Banner ad unit and one Interstitial ad unit -> you get AD UNIT IDs
 *      (ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY)
 *   4. Paste them below, replacing the TEST ids.
 *   5. Put the APP ID in AndroidManifest.xml -> <meta-data
 *      android:name="com.google.android.gms.ads.APPLICATION_ID" .../>
 *   6. In AdsManager.java, swap the TEST-MODE blocks for the real MobileAds
 *      calls (each swap point is marked "PRODUCTION SWAP").
 *
 * The APP ID, BANNER and INTERSTITIAL values below are the REAL AdMob
 * IDs (account pub-8117548332904163, created 2026-11-24). The NATIVE
 * value is still Google's official TEST ID — native ads are not used
 * by the app, so it is never loaded.
 */
public final class AdsConfig {

    /** Master switch — set false to disable all ads (e.g. for premium users). */
    public static final boolean ADS_ENABLED = true;

    /** Real AdMob APP ID (created 2026-11-24, account pub-8117548332904163). */
    public static final String ADMOB_APP_ID = "ca-app-pub-8117548332904163~2883931517"; // REAL

    /** Real BANNER unit (created 2026-11-24). */
    public static final String BANNER_AD_UNIT_ID = "ca-app-pub-8117548332904163/5352900926"; // REAL

    /** Real INTERSTITIAL unit (created 2026-11-24). */
    public static final String INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8117548332904163/2016291143"; // REAL

    /** Google test NATIVE unit. Replace with your real native ad unit ID. */
    public static final String NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"; // TEST

    /** Show an interstitial every Nth successful calculation. */
    public static final int INTERSTITIAL_EVERY_N_CALCULATIONS = 3;

    private AdsConfig() {}
}
