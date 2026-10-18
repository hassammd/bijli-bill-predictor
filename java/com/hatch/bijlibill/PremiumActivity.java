package com.hatch.bijlibill;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v2.0 "Go Premium" screen. Feature list + purchase button wired to
 * BillingStub.launchPurchaseFlow() — real Play Billing activates at
 * Play Console publish time.
 */
public class PremiumActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = Ui.page(this);
        LinearLayout root = Ui.pageRoot(scroll);
        root.setPadding(Ui.dp(this, 0), 0, Ui.dp(this, 0), Ui.dp(this, 24));

        root.addView(Ui.header(this, R.string.premium_title, R.string.app_tagline,
                new Ui.LangToggle() {
                    @Override public void onToggle() {
                        LocaleHelper.set(PremiumActivity.this,
                                LocaleHelper.isUrdu(PremiumActivity.this)
                                        ? LocaleHelper.LANG_EN : LocaleHelper.LANG_UR);
                        recreate();
                    }
                }));
        root.addView(Ui.spacer(this, 4));

        TextView sub = Ui.tvRes(this, R.string.premium_sub, 15, false, Ui.grey());
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                Ui.dp(this, 16), Ui.dp(this, 4));
        root.addView(sub);

        LinearLayout card = Ui.card(this);
        int[] feats = {R.string.feat_noads, R.string.feat_refs,
                R.string.feat_priority};
        for (int f : feats) {
            TextView t = Ui.tvRes(this, f, 15, false, Ui.dark());
            t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            card.addView(t);
        }
        root.addView(Ui.padded(this, card));

        LinearLayout buyWrap = Ui.col(this);
        buyWrap.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0);
        Button buy = Ui.accentButton(this, R.string.buy_premium);
        final PremiumActivity self = this;
        buy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BillingStub.launchPurchaseFlow(self);
            }
        });
        buyWrap.addView(buy);
        buyWrap.addView(Ui.spacer(this, 10));
        TextView note = Ui.tvRes(this, R.string.premium_note, 12, false, Ui.grey());
        note.setGravity(Gravity.CENTER);
        buyWrap.addView(note);
        buyWrap.addView(Ui.spacer(this, 8));
        Button back = Ui.ghostButton(this, R.string.back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        buyWrap.addView(back);
        root.addView(buyWrap);

        setContentView(scroll);
    }
}
