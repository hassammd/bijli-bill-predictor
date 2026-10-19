package com.hatch.bijlibill;

/**
 * ============================================================================
 * ADS CONFIG — *** REPLACE THESE WITH YOUR REAL ADMOB IDS BEFORE PUBLISH ***
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
 * The values below are Google's OFFICIAL TEST IDs — safe to ship in a debug
 * build; they always show test ads, never real ones.
 */
public final class AdsConfig {

    /** Master switch — set false to disable all ads (e.g. for premium users). */
    public static final boolean ADS_ENABLED = true;

    /** Google test APP ID. Replace with your real AdMob App ID before release. */
    public static final String ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"; // TEST

    /** Google test BANNER unit. Replace with your real banner ad unit ID. */
    public static final String BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"; // TEST

    /** Google test INTERSTITIAL unit. Replace with your real interstitial ad unit ID. */
    public static final String INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"; // TEST

    /** Google test NATIVE unit. Replace with your real native ad unit ID. */
    public static final String NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"; // TEST

    /** Show an interstitial every Nth successful calculation. */
    public static final int INTERSTITIAL_EVERY_N_CALCULATIONS = 3;

    private AdsConfig() {}
}
