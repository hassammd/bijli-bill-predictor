package com.hatch.bijlibill;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Google Play Billing wiring STUB for the "Go Premium" upgrade.
 *
 * CURRENT STATE: stubbed, and the purchase UI is REMOVED (v3.7.55,
 * zero-risk audit). The old About-screen PREMIUM button promised a
 * paid purchase, but real billing was never wired — and the app has
 * been LIVE on Play since 21 Nov 2026, so the old claim that billing
 * would switch on at publish was false. There is no purchase entry
 * point anywhere in the app until real billing is implemented.
 * All feature gating points (BillingStub.isPremium) and the SKU
 * constant stay wired — enabling real billing later is mechanical.
 *
 * TO ENABLE (full steps in README.txt):
 *   1. Play Console -> Monetize -> In-app products -> create product
 *      with ID below (PREMIUM_SKU), type "Non-consumable", set price.
 *   2. Add dependency: com.android.billingclient:billing:7.x (Gradle)
 *      and implement BillingClient with queryProductDetails +
 *      launchBillingFlow.
 *   3. Replace isPremium() to check real purchases; persist with
 *      setPremium() below (already the single write path).
 *   4. Re-add a purchase entry point (About screen) ONLY once the
 *      flow above is tested end to end.
 */
public final class BillingStub {

    /** Must match the in-app product ID you create in Play Console. */
    public static final String PREMIUM_SKU = "bijli_bill_premium";

    private static final String PREFS = "bijli_bill_billing";
    private static final String KEY_PREMIUM = "is_premium";

    private BillingStub() {}

    /** Single read path — every premium gate in the app calls this. */
    public static boolean isPremium(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PREMIUM, false);
    }

    /** Single write path — real billing integration calls this on purchase. */
    public static void setPremium(Context ctx, boolean premium) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PREMIUM, premium).apply();
    }

    /** v3.7.55: no purchase UI calls this — kept for when real
     *  billing is wired. Today it only explains that premium is
     *  unavailable (the old text promised billing "at publish
     *  time", but the app has been published since Nov 2026). */
    public static void launchPurchaseFlow(final Activity activity) {
        // PRODUCTION SWAP: billingClient.launchBillingFlow(activity, params)
        // with PREMIUM_SKU; on success call setPremium(activity, true).
        android.widget.TextView msgView = new android.widget.TextView(activity);
        msgView.setText(activity.getString(R.string.billing_msg));
        msgView.setTextSize(14);
        float density = activity.getResources().getDisplayMetrics().density;
        int px48 = (int) (48 * density + 0.5f);
        int px24 = (int) (24 * density + 0.5f);
        int px8 = (int) (8 * density + 0.5f);
        msgView.setPadding(px48, px24, px48, px8);
        Fonts.applyIfUrdu(activity, msgView);
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.billing_title))
                .setView(msgView)
                .setPositiveButton(activity.getString(R.string.ok), null)
                .show();
    }
}
