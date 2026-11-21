package com.hatch.bijlibill;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.7.43 — Pending Bills list, opened by tapping the Home
 * "Total Payable" card (user order 2026-11-21, voice call):
 * every unpaid saved bill with company, bill month, amount,
 * due date + days left. Due within 3 days is red; past due
 * shows a red "Overdue" mark + the after-due amount (when the
 * bill printed one). Tap a row = open that bill's detail.
 * Nothing pending -&gt; green tick + "All bills paid".
 */
public class PendingBillsActivity extends BaseActivity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        if (w != null) {
            w.setStatusBarColor(Ds.ndBlueAccent());
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
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ds.ndBlueAccent());
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 14),
                Ui.dp(this, 20), Ui.dp(this, 10));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chev_right);
        back.setRotation(180);
        back.setColorFilter(Ds.ndPrimaryDark(),
                android.graphics.PorterDuff.Mode.SRC_IN);
        int bs = Ui.dp(this, 28);
        back.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        back.setBackgroundResource(R.drawable.nd_back_btn_light);
        back.setPadding(Ui.dp(this, 8), Ui.dp(this, 8),
                Ui.dp(this, 8), Ui.dp(this, 8));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(getString(R.string.pending_bills_title));
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        Ds.heading(this, title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 12);
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
        root.addView(Ds.bottomNav(this, Ds.TAB_HOME));
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
        List<TotalPayable.Record> recs =
                new ArrayList<TotalPayable.Record>();
        for (LastBillStore.Record r : LastBillStore.loadAll(this)) {
            recs.add(new TotalPayable.Record(r.amount, r.state,
                    r.disco, r.mode, r.ref, r.billMonth, r.dueDate,
                    r.afterDue));
        }
        List<TotalPayable.Record> pending =
                TotalPayable.pendingSorted(recs);
        long nowMs = System.currentTimeMillis();
        if (pending.isEmpty()) {
            list.addView(allPaidView());
            return;
        }
        for (TotalPayable.Record p : pending) {
            list.addView(pendingRow(p, nowMs));
        }
    }

    /** v3.7.43: green tick + "All bills paid" when nothing pending. */
    private View allPaidView() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(Ui.dp(this, 24), Ui.dp(this, 48),
                Ui.dp(this, 24), Ui.dp(this, 24));

        ImageView tick = new ImageView(this);
        tick.setImageResource(R.drawable.ic_check_white);
        int cs = Ui.dp(this, 56);
        tick.setLayoutParams(
                new LinearLayout.LayoutParams(cs, cs));
        tick.setBackgroundResource(R.drawable.ds_circle_green);
        tick.setPadding(Ui.dp(this, 14), Ui.dp(this, 14),
                Ui.dp(this, 14), Ui.dp(this, 14));
        empty.addView(tick);

        TextView t = new TextView(this);
        t.setText(getString(R.string.pending_all_paid));
        t.setTextSize(18);
        t.setTextColor(Color.parseColor("#1B1B1B"));
        Ds.heading(this, t);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 16);
        t.setLayoutParams(tlp);
        empty.addView(t);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.bills_pending, 0));
        sub.setTextSize(14);
        sub.setTextColor(Ds.muted());
        Ds.body(this, sub, 400);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 6);
        sub.setLayoutParams(slp);
        empty.addView(sub);
        return empty;
    }

    /** One pending bill: company + month, amount, ref, due line,
     *  and (when overdue) the after-due amount. Tap = open bill. */
    private View pendingRow(final TotalPayable.Record p,
            final long nowMs) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card_24);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 8),
                Ui.dp(this, 16), Ui.dp(this, 8));
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openBill(Disco.fromCode(p.disco), p.mode, p.ref);
            }
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        String month = p.billMonth == null ? "" : p.billMonth.trim();
        title.setText(Disco.fromCode(p.disco).displayName
                + (month.isEmpty() ? "" : " \u00B7 " + month));
        title.setTextSize(14);
        title.setTextColor(Color.parseColor("#1B1B1B"));
        Ds.heading(this, title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tlp);
        top.addView(title);
        TextView amt = new TextView(this);
        amt.setText("Rs. "
                + (p.amountPrinted == null ? ""
                        : p.amountPrinted.trim()));
        amt.setTextSize(16);
        amt.setTextColor(Color.parseColor("#1B62B0"));
        amt.setTypeface(Fonts.archivo(this, 800));
        top.addView(amt);
        card.addView(top);

        if (p.ref != null && !p.ref.trim().isEmpty()) {
            TextView ref = new TextView(this);
            ref.setText(getString(R.string.bill_ref_masked,
                    p.ref.trim()));
            ref.setTextSize(12);
            ref.setTextColor(Ds.muted());
            Ds.body(this, ref, 400);
            card.addView(ref);
        }

        // ---- due line: due date + days left; red when due soon
        //      (<= 3 days) or overdue. ----
        TextView dueTv = new TextView(this);
        dueTv.setTextSize(12.5f);
        Ds.body(this, dueTv, 400);
        Integer d = TotalPayable.daysLeft(p.dueDate, nowMs);
        String due = p.dueDate == null ? "" : p.dueDate.trim();
        boolean overdue = d != null && d.intValue() < 0;
        if (d == null) {
            dueTv.setText(due.isEmpty()
                    ? getString(R.string.pending_no_due)
                    : getString(R.string.strip_due_date) + " " + due);
            dueTv.setTextColor(Ds.muted());
        } else if (overdue) {
            dueTv.setText(getString(-d.intValue() == 1 ? R.string.pending_overdue_by_day
                            : R.string.pending_overdue_by,
                    -d.intValue()));
            dueTv.setTextColor(Color.parseColor("#C62828"));
            dueTv.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (d.intValue() == 0) {
            dueTv.setText(getString(R.string.due_chip, due,
                    getString(R.string.due_today)));
            dueTv.setTextColor(Color.parseColor("#C62828"));
        } else if (d.intValue() <= TotalPayable.DUE_SOON_DAYS) {
            dueTv.setText(getString(R.string.due_chip, due,
                    getString(d.intValue() == 1 ? R.string.due_day_left
                            : R.string.due_days_left, d.intValue())));
            dueTv.setTextColor(Color.parseColor("#C62828"));
        } else {
            dueTv.setText(getString(R.string.due_chip, due,
                    getString(d.intValue() == 1 ? R.string.due_day_left
                            : R.string.due_days_left, d.intValue())));
            dueTv.setTextColor(Ds.muted());
        }
        card.addView(dueTv);

        // ---- overdue: after-due amount, only when the bill
        //      printed one (never invented). ----
        if (overdue && p.afterDue != null
                && !p.afterDue.trim().isEmpty()) {
            TextView late = new TextView(this);
            late.setText(getString(R.string.pending_after_due,
                    p.afterDue.trim()));
            late.setTextSize(12.5f);
            late.setTextColor(Color.parseColor("#C62828"));
            Ds.body(this, late, 400);
            card.addView(late);
        }
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
