package com.hatch.bijlibill;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * v2.6.0 design system: the bottom-nav "Bills" tab — the full saved-meter
 * list ("Mere Meter"). Same data and behaviour as the home cards:
 * tap = re-fetch the bill, long-press = delete with confirmation.
 */
public class BillsActivity extends BaseActivity {

    private LinearLayout list;
    private SavedMeters.Storage storage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = MeterUi.store(this);

        Window w = getWindow();
        if (w != null) {
            w.setStatusBarColor(Ds.bg());
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ds.bg());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- app bar: back + title + lang toggle ----
        // v3.6.60: dark green header (matches BillActivity).
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ds.ndPrimaryDark());
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 14),
                Ui.dp(this, 20), Ui.dp(this, 10));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chev_right);
        back.setRotation(180);
        back.setColorFilter(Ds.ndPrimaryDark(),
                android.graphics.PorterDuff.Mode.SRC_IN);
        int bs = Ui.dp(this, 38);
        back.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        back.setBackgroundResource(R.drawable.ds_back_btn);
        back.setPadding(Ui.dp(this, 10), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(getString(R.string.bills_title));
        title.setTextSize(18);
        title.setTextColor(android.graphics.Color.WHITE);
        Ds.heading(this, title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 4);
        // v3.6.11: space between title and language toggle
        tlp.rightMargin = Ui.dp(this, 12);
        title.setLayoutParams(tlp);
        bar.addView(title);
        bar.addView(Ds.langToggle(this));
        root.addView(bar);

        // ---- list ----
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int hp = Ui.dp(this, 20);
        list.setPadding(hp, Ui.dp(this, 6), hp, Ui.dp(this, 24));
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        // v3.6.16: test banner ad (user request — see ad on all pages).
        android.widget.LinearLayout bannerBox =
                new android.widget.LinearLayout(this);
        bannerBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(bannerBox);
        AdsManager.loadBannerInto(this, bannerBox);
        // v3.6.4: bottom nav on every tab (tester feedback #3).
        root.addView(Ds.bottomNav(this, Ds.TAB_BILLS));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refresh();
    }


    private void refresh() {
        list.removeAllViews();
        // v3.6.11: Tools row removed from Bills tab (user request 2026-10-18).
        final List<SavedMeters.Meter> items = SavedMeters.load(storage);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.meters_empty));
            empty.setTextSize(14);
            empty.setTextColor(Ds.muted());
            Ds.body(this, empty, 400);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Ui.dp(this, 24), Ui.dp(this, 40),
                    Ui.dp(this, 24), Ui.dp(this, 40));
            list.addView(empty);
            return;
        }
        for (int idx = 0; idx < items.size(); idx++) {
            final SavedMeters.Meter m = items.get(idx);
            final Disco md = Disco.fromCode(m.disco);
            list.addView(MeterUi.card(this, m,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            openBill(md, m.idType, m.idValue);
                        }
                    },
                    new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            MeterUi.confirmDelete(BillsActivity.this,
                                    storage, m,
                                    new Runnable() {
                                        @Override public void run() {
                                            refresh();
                                        }
                                    });
                            return true;
                        }
                    }));
            // v3.6.30: native ad every 3 bills (test placeholder).
            // Production: real AdMob native ad here.
            if ((idx + 1) % 3 == 0 && idx + 1 < items.size()) {
                list.addView(nativeAdCard());
            }
        }
    }

    /**
     * v3.6.30: native ad placeholder for bills list. In production,
     * replace with real AdMob NativeAd rendering.
     */
    private View nativeAdCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card_24);
        int p = Ui.dp(this, 16);
        card.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Ui.dp(this, 16);
        lp.setMargins(m, Ui.dp(this, 8), m, Ui.dp(this, 8));
        card.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText("AD");
        label.setTextSize(10);
        label.setTextColor(android.graphics.Color.parseColor("#9E9E9E"));
        card.addView(label);

        TextView body = new TextView(this);
        body.setText("TEST NATIVE AD\n" + AdsConfig.NATIVE_AD_UNIT_ID +
                "\n(Production: real native ad here)");
        body.setTextSize(12);
        body.setTextColor(android.graphics.Color.parseColor("#616161"));
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        card.addView(body);

        return card;
    }

    private void openBill(Disco d, String mode, String value) {
        Intent i = new Intent(this, BillActivity.class);
        i.putExtra(BillActivity.EXTRA_DISCO, d.code);
        i.putExtra(BillActivity.EXTRA_MODE, mode);
        i.putExtra(BillActivity.EXTRA_REF, value);
        startActivity(i);
    }
}
