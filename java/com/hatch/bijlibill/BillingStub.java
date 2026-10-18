package com.hatch.bijlibill;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Google Play Billing wiring STUB for the "Go Premium" upgrade.
 *
 * CURRENT STATE: stubbed. The purchase button calls launchPurchaseFlow(),
 * which today explains that billing activates at Play Console publish time.
 * All UI, feature gating points (BillingStub.isPremium) and the SKU constant
 * are already wired — enabling real billing later is mechanical.
 *
 * TO ENABLE (full steps in README.txt):
 *   1. Publish the app's Play Console listing (needs the $25 developer account).
 *   2. Play Console -> Monetize -> In-app products -> create product with ID
 *      below (PREMIUM_SKU), type "Non-consumable", set price.
 *   3. Add dependency: com.android.billingclient:billing:7.x (Gradle) and
 *      implement BillingClient with queryProductDetails + launchBillingFlow.
 *   4. Replace isPremium() to check real purchases; persist with
 *      setPremium() below (already the single write path).
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

    /** Wired to the "Buy Premium" button. Today: explains pending state. */
    public static void launchPurchaseFlow(final Activity activity) {
        // PRODUCTION SWAP: billingClient.launchBillingFlow(activity, params)
        // with PREMIUM_SKU; on success call setPremium(activity, true).
        android.widget.TextView msgView = new android.widget.TextView(activity);
        msgView.setText(activity.getString(R.string.billing_msg, PREMIUM_SKU));
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
