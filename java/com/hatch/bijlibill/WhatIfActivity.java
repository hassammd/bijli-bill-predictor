package com.hatch.bijlibill;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v3.2.0 — What-if Compare. "Agar 210 units hue to bill kitna?"
 * The user types up to 4 unit values (defaults 190/200/210/250);
 * each is estimated with the exact Meter Check pipeline
 * (MeterCheckCalc.compute -> BillMirrorCalc.mirror, dual
 * calibration included), shown side-by-side with the cliff jump
 * highlighted. For a Protected bill a red banner warns when any
 * slot crosses 200 (subsidy lost). Requires a fetched/cached bill.
 */
public class WhatIfActivity extends BaseActivity {

    public static final String EXTRA_REF = "ref";
    public static final String EXTRA_MODE = "mode"; // "ref" or "cid"
    public static final String EXTRA_DISCO = "disco";

    private static final double[] DEFAULTS = {190, 200, 210, 250};

    private String ref;
    private String mode;
    private Disco disco;
    private LescoBillFetcher.BillInfo bill;

    private LinearLayout content;
    private final List<EditText> slotInputs = new ArrayList<EditText>();
    private LinearLayout resultWrap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ref = getIntent().getStringExtra(EXTRA_REF);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = "ref";
        disco = Disco.fromCode(getIntent().getStringExtra(EXTRA_DISCO));
        if (ref == null) ref = "";

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
        root.addView(appBar());

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv);
        setContentView(root);

        bill = loadBill();
        buildUi();
        if (bill != null) compare();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private View appBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 14),
                Ui.dp(this, 20), Ui.dp(this, 10));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chev_right);
        back.setRotation(180);
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
        title.setText(getString(R.string.whatif_title));
        title.setTextSize(18);
        title.setTextColor(Ds.ink());
        Ds.heading(this, title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 4);
        title.setLayoutParams(tlp);
        bar.addView(title);
        bar.addView(Ds.langToggle(this));
        return bar;
    }

    private void buildUi() {
        content.removeAllViews();

        if (bill == null) {
            LinearLayout card = Ui.card(this);
            TextView t = new TextView(this);
            t.setText(getString(R.string.whatif_need_bill));
            t.setTextSize(15);
            t.setTextColor(Ds.red());
            Ds.body(this, t, 500);
            t.setGravity(Gravity.CENTER);
            card.addView(t);
            card.addView(Ui.spacer(this, 12));
            Button fetch = Ds.outlineBtn(this,
                    getString(R.string.whatif_fetch_btn),
                    R.drawable.ic_edit,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            Intent i = new Intent(WhatIfActivity.this,
                                    BillActivity.class);
                            i.putExtra(BillActivity.EXTRA_REF, ref);
                            i.putExtra(BillActivity.EXTRA_MODE, mode);
                            i.putExtra(BillActivity.EXTRA_DISCO,
                                    disco.code);
                            startActivity(i);
                            finish();
                        }
                    });
            card.addView(fetch);
            content.addView(Ui.padded(this, card));
            return;
        }

        // ---- slots card ----
        LinearLayout slotsCard = Ui.card(this);
        TextView hint = new TextView(this);
        hint.setText(getString(R.string.whatif_hint));
        hint.setTextSize(13.5f);
        hint.setTextColor(Ds.muted());
        Ds.body(this, hint, 400);
        slotsCard.addView(hint);
        slotsCard.addView(Ui.spacer(this, 10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < DEFAULTS.length; i++) {
            EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            et.setText(trimNum(DEFAULTS[i]));
            et.setTextSize(16);
            et.setTextColor(Ds.ink());
            Ds.body(this, et, 500);
            Ds.mono(et);
            et.setGravity(Gravity.CENTER);
            et.setBackgroundResource(R.drawable.ds_input_bg);
            int p = Ui.dp(this, 8);
            et.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            et.setLayoutParams(lp);
            row.addView(et);
            slotInputs.add(et);
        }
        slotsCard.addView(row);
        slotsCard.addView(Ui.spacer(this, 12));
        Button go = Ds.outlineBtn(this,
                getString(R.string.whatif_compare_btn),
                R.drawable.ic_edit,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        compare();
                    }
                });
        slotsCard.addView(go);
        content.addView(Ui.padded(this, slotsCard));

        resultWrap = new LinearLayout(this);
        resultWrap.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultWrap);
        content.addView(Ui.spacer(this, 24));
    }

    /** Runs the 4 estimates and renders the comparison. */
    private void compare() {
        if (bill == null || resultWrap == null) return;
        resultWrap.removeAllViews();

        List<Double> units = new ArrayList<Double>();
        for (EditText et : slotInputs) {
            try {
                double u = Double.parseDouble(et.getText().toString()
                        .trim().replace(",", "."));
                if (u > 0 && u <= 100000) units.add(u);
            } catch (Exception ignored) {
                // Skip an unparseable slot.
            }
        }
        if (units.isEmpty()) return;
        Collections.sort(units);

        int cat = BillMirrorCalc.tariffCat(bill.category);
        boolean prot = cat == Tariff.PROTECTED;
        List<Double> totals = new ArrayList<Double>();

        for (double u : units) {
            // Exact Meter Check pipeline: compute -> mirror.
            MeterCheckCalc.Result r = MeterCheckCalc.compute("0",
                    trimNum(u), bill.category, null, null, null,
                    null, 15);
            BillMirrorCalc.MirrorResult m =
                    BillMirrorCalc.mirror(bill, r.unitsUsed,
                            r.energyEstimate, cat);
            double total = (m != null && m.usable)
                    ? m.estimatedTotal : r.estimatedTotal;
            double energy = (m != null && m.usable)
                    ? m.energyEstimate : r.energyEstimate;
            totals.add(total);
            resultWrap.addView(slotCard(u, energy, total));
        }

        // ---- cliff analysis (Protected bills only) ----
        if (prot) {
            boolean anyOver = false;
            for (double u : units) {
                if (u > 200) { anyOver = true; break; }
            }
            if (anyOver) {
                resultWrap.addView(cliffBanner());
            }
            // Adjacent jumps; the 200-crossing jump is the story.
            double maxJump = -1;
            int maxIdx = -1;
            LinearLayout jumpsCard = Ui.card(this);
            jumpsCard.addView(smallTitle(
                    R.string.whatif_jumps_title));
            jumpsCard.addView(Ui.spacer(this, 6));
            for (int i = 0; i + 1 < units.size(); i++) {
                double jump = totals.get(i + 1) - totals.get(i);
                if (jump > maxJump) {
                    maxJump = jump;
                    maxIdx = i;
                }
                boolean crosses = units.get(i) <= 200
                        && units.get(i + 1) > 200;
                TextView t = new TextView(this);
                t.setText(getString(R.string.whatif_jump,
                        trimNum(units.get(i)),
                        trimNum(units.get(i + 1)),
                        Tariff.money(jump)));
                t.setTextSize(13.5f);
                t.setTextColor(crosses ? Ds.red() : Ds.muted());
                Ds.body(this, t, crosses ? 700 : 400);
                Ds.mono(t);
                jumpsCard.addView(t);
                jumpsCard.addView(Ui.spacer(this, 4));
            }
            if (maxIdx >= 0 && maxJump > 0) {
                TextView big = new TextView(this);
                big.setText(getString(R.string.whatif_max_jump,
                        trimNum(units.get(maxIdx)),
                        trimNum(units.get(maxIdx + 1)),
                        Tariff.money(maxJump)));
                big.setTextSize(14);
                big.setTextColor(Ds.red());
                Ds.body(this, big, 700);
                jumpsCard.addView(Ui.spacer(this, 6));
                jumpsCard.addView(big);
            }
            resultWrap.addView(Ui.padded(this, jumpsCard));
        }

        TextView note = new TextView(this);
        note.setText(getString(R.string.whatif_note_estimate));
        note.setTextSize(11.5f);
        note.setTextColor(Ds.faint());
        Ds.body(this, note, 400);
        note.setGravity(Gravity.CENTER);
        note.setPadding(Ui.dp(this, 16), Ui.dp(this, 4),
                Ui.dp(this, 16), 0);
        resultWrap.addView(note);
    }

    private View slotCard(double units, double energy,
            double total) {
        LinearLayout card = Ui.card(this);
        TextView title = new TextView(this);
        title.setText(getString(R.string.whatif_slot_title,
                trimNum(units)));
        title.setTextSize(16);
        title.setTextColor(Ds.ink());
        Ds.heading(this, title);
        card.addView(title);
        card.addView(Ui.spacer(this, 6));
        card.addView(Ds.dottedRow(this,
                getString(R.string.andaza_lbl_energy),
                Tariff.moneyPlain(energy), false));
        card.addView(Ds.dashDivider(this));
        card.addView(Ds.dottedRow(this,
                getString(R.string.whatif_other_charges),
                Tariff.moneyPlain(total - energy), false));
        card.addView(Ds.dashDivider(this));
        card.addView(Ds.dottedRow(this,
                getString(R.string.andaza_lbl_total),
                Tariff.money(total), true));
        return Ui.padded(this, card);
    }

    private View cliffBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackgroundResource(R.drawable.ds_warn_strip);
        int p = Ui.dp(this, 12);
        banner.setPadding(p, p, p, p);
        TextView t = new TextView(this);
        t.setText(getString(R.string.whatif_cliff_banner));
        t.setTextSize(13.5f);
        t.setTextColor(Ds.red());
        Ds.body(this, t, 600);
        banner.addView(t);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                Ui.dp(this, 16), 0);
        wrap.addView(banner);
        return wrap;
    }

    private TextView smallTitle(int res) {
        TextView t = new TextView(this);
        t.setText(getString(res));
        t.setTextSize(14);
        t.setTextColor(Ds.ink());
        Ds.heading(this, t);
        return t;
    }

    private LescoBillFetcher.BillInfo loadBill() {
        try {
            BillCache.CachedPage cached = BillCache.getHtml(this,
                    disco.code + ":" + mode + ":" + ref);
            if (cached == null) return null;
            LescoBillFetcher.BillPage p =
                    LescoBillFetcher.pageFromHtml(cached.html, ref,
                            cached.fetchPath);
            return (p.parseOk && p.info != null) ? p.info : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** Opens the compare screen for a meter. */
    public static void open(android.content.Context ctx, Disco disco,
            String mode, String ref) {
        Intent i = new Intent(ctx, WhatIfActivity.class);
        i.putExtra(EXTRA_DISCO, disco == null ? "lesco" : disco.code);
        i.putExtra(EXTRA_MODE, mode == null ? "ref" : mode);
        i.putExtra(EXTRA_REF, ref == null ? "" : ref);
        ctx.startActivity(i);
    }
}
