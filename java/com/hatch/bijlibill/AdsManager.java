package com.hatch.bijlibill;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;

/**
 * ============================================================================
 * ADS MANAGER — NO-OP STUB (v3.7.58, 190 — NO-ADS RELEASE)
 * ============================================================================
 * User order, voice call 2026-11-24 ~23:48 PKT: this release ships
 * with NO ads at all; the ads work goes in a LATER release. The
 * v3.7.56 (188) AdMob integration is fully out of this build:
 *
 *   - The ID-holder class is DELETED (it held the real AdMob
 *     app/banner/interstitial IDs — no ad ID exists anywhere in
 *     the source anymore, and none can reach the shipped binary).
 *   - The Google Mobile Ads SDK jars are EXCLUDED from the build
 *     (build.sh ADS_EXCLUDE) — no ad-network class exists in the
 *     APK dex at all (build.sh fails the build if a banner-view
 *     class or any ad-network ID string is found in dex).
 *   - The manifest has no APPLICATION_ID, no AdActivity, no init
 *     provider, no AdService, no AD_ID / ADSERVICES
 *     permissions, no <queries> block.
 *
 * This file stays as a stub ONLY so the 11 existing call sites
 * (init + loadBannerInto + maybeShowInterstitialAfter, in 9
 * activities) keep compiling unchanged — when the later ads
 * release comes, the real AdsManager is restored and the call
 * sites light up again with zero edits. Every method below is a
 * deliberate no-op: nothing initializes, nothing loads, nothing
 * shows, no network, no ID. The banner containers the call sites
 * build stay empty (zero children, no padding applied here), so
 * no gap or placeholder appears on any screen.
 *
 * The v3.7.56 placement rules (audit §2-B) are NOT deleted from
 * history — they live in git (commit 6d309e1) and come back with
 * the real AdsManager in the ads release.
 */
public final class AdsManager {

    private AdsManager() {}

    /** Call once from HomeActivity.onCreate(). No-op in this build. */
    public static void init(Context ctx) {
        // NO-ADS 190: no SDK initialize call — nothing to init.
    }

    /**
     * Banner slot. No-op in this build: the container is left
     * exactly as the call site built it (empty, no padding, no
     * divider) so it takes no space on the screen.
     */
    public static void loadBannerInto(Activity activity,
            ViewGroup container) {
        // NO-ADS 190: no banner view is created, no ad is requested.
    }

    /**
     * Interstitial trigger (call AFTER the bill/result displays).
     * No-op in this build: nothing is preloaded, nothing shows.
     */
    public static void maybeShowInterstitialAfter(
            final Activity activity) {
        // NO-ADS 190: no interstitial exists to show.
    }
}
