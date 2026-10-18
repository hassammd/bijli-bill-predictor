package com.hatch.bijlibill;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v3.2.0 — 200-Unit Guard. Keeps a Protected consumer under the
 * 200-unit cliff: the user logs meter readings through the month,
 * the guard projects the month-end units at the current pace and
 * reports the daily budget that keeps them safe, with a once-daily
 * warning notification when the projection turns bad.
 *
 * Anchor defaults come from the fetched bill (present reading +
 * reading date) and are editable; without a bill everything is
 * manual. Launched from HomeActivity and from the Meter Check
 * result ("Guard mein reading save karo").
 */
public class GuardActivity extends BaseActivity {

    public static final String EXTRA_REF = "ref";
    public static final String EXTRA_MODE = "mode"; // "ref" or "cid"
    public static final String EXTRA_DISCO = "disco";
    /** Optional: prefill the reading input (from Meter Check). */
    public static final String EXTRA_READING = "reading";

    private String ref;
    private String mode;
    private Disco disco;
    private String meterKey;

    private LinearLayout content;
    private EditText anchorReadingEt;
    private TextView anchorDateBtn;
    private EditText readingEt;
    private String anchorDateIso; // yyyy-MM-dd

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ref = getIntent().getStringExtra(EXTRA_REF);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = "ref";
        disco = Disco.fromCode(getIntent().getStringExtra(EXTRA_DISCO));
        if (ref == null) ref = "";
        meterKey = GuardStore.meterKey(disco.code, mode, ref);

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

        // Prefill from the Meter Check result when supplied.
        String pre = getIntent().getStringExtra(EXTRA_READING);

        // Anchor defaults: bill's present reading + reading date.
        double anchorDef = -1;
        String anchorDateDef = GuardReminder.todayIso();
        LescoBillFetcher.BillInfo bill = loadBill();
        if (bill != null) {
            String base = MeterCheckCalc.resolveBase(bill.currReading,
                    bill.prevReading);
            anchorDef = MeterCheckCalc.parseReadingD(base);
            String rd = parseBillDate(bill.readingDate);
            if (rd != null) anchorDateDef = rd;
        }
        if (!GuardStore.hasAnchor(this, meterKey)) {
            if (anchorDef >= 0) {
                GuardStore.setAnchor(this, meterKey, anchorDef,
                        anchorDateDef);
            }
        } else {
            anchorDef = GuardStore.anchorReading(this, meterKey, -1);
            String d = GuardStore.anchorDate(this, meterKey);
            if (d != null && !d.isEmpty()) anchorDateDef = d;
        }
        anchorDateIso = anchorDateDef;

        buildUi(anchorDef, pre);
        refresh();
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
        title.setText(getString(R.string.guard_title));
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

    private void buildUi(double anchorDef, String prefillReading) {
        content.removeAllViews();

        // ---- status card ----
        LinearLayout statusCard = Ui.card(this);
        statusCard.setTag("status_card");
        content.addView(Ui.padded(this, statusCard));

        // ---- anchor card ----
        LinearLayout anchorCard = Ui.card(this);
        anchorCard.addView(cardTitle(R.string.guard_anchor_title));
        anchorCard.addView(Ui.spacer(this, 8));
        anchorCard.addView(fieldLabel(R.string.guard_anchor_reading));
        anchorReadingEt = new EditText(this);
        styleInput(anchorReadingEt);
        anchorReadingEt.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (anchorDef >= 0) {
            anchorReadingEt.setText(trimNum(anchorDef));
        }
        anchorCard.addView(anchorReadingEt);
        anchorCard.addView(Ui.spacer(this, 10));
        anchorCard.addView(fieldLabel(R.string.guard_anchor_date));
        anchorDateBtn = new TextView(this);
        styleDateBtn(anchorDateBtn);
        anchorCard.addView(anchorDateBtn);
        anchorDateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAnchorDate(); }
        });
        anchorCard.addView(Ui.spacer(this, 10));
        Button anchorSave = Ds.outlineBtn(this,
                getString(R.string.guard_anchor_save), 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        saveAnchor();
                    }
                });
        anchorCard.addView(anchorSave);
        content.addView(Ui.padded(this, anchorCard));

        // ---- reading card ----
        LinearLayout readCard = Ui.card(this);
        readCard.addView(cardTitle(R.string.guard_reading_title));
        readCard.addView(Ui.spacer(this, 8));
        readingEt = new EditText(this);
        styleInput(readingEt);
        readingEt.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        readingEt.setHint(getString(R.string.guard_reading_hint));
        if (prefillReading != null && !prefillReading.isEmpty()) {
            readingEt.setText(prefillReading);
        }
        readCard.addView(readingEt);
        readCard.addView(Ui.spacer(this, 10));
        Button save = Ds.outlineBtn(this,
                getString(R.string.guard_reading_save),
                R.drawable.ic_edit,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        saveReading();
                    }
                });
        readCard.addView(save);
        content.addView(Ui.padded(this, readCard));

        // ---- history card ----
        LinearLayout histCard = Ui.card(this);
        histCard.setTag("history_card");
        content.addView(Ui.padded(this, histCard));
        content.addView(Ui.spacer(this, 24));
    }

    /** Rebuilds the status + history cards from storage. */
    private void refresh() {
        LinearLayout statusCard = (LinearLayout)
                content.findViewWithTag("status_card");
        LinearLayout histCard = (LinearLayout)
                content.findViewWithTag("history_card");
        if (statusCard == null || histCard == null) return;
        statusCard.removeAllViews();
        histCard.removeAllViews();

        double anchor = GuardStore.anchorReading(this, meterKey, -1);
        String aDate = GuardStore.anchorDate(this, meterKey);
        List<GuardStore.Entry> entries =
                GuardStore.entries(this, meterKey);
        double latest = anchor;
        if (!entries.isEmpty()) {
            latest = entries.get(entries.size() - 1).reading;
        }

        if (!(anchor >= 0) || aDate == null || aDate.isEmpty()) {
            // v3.6.0: zero-input Guard — project from the last
            // fetched bill's pace instead of showing nothing.
            GuardCalc.Projection auto = autoProjection();
            if (auto != null) {
                renderStatus(statusCard, auto, true);
            } else {
                TextView t = new TextView(this);
                t.setText(getString(R.string.guard_no_anchor));
                t.setTextSize(14);
                t.setTextColor(Ds.muted());
                Ds.body(this, t, 400);
                statusCard.addView(t);
            }
        } else {
            String todayIso = GuardReminder.todayIso();
            int daysElapsed =
                    GuardReminder.daysBetween(aDate, todayIso);
            GuardCalc.Projection pr = GuardCalc.project(
                    latest - anchor, daysElapsed,
                    GuardCalc.DEFAULT_CYCLE_DAYS);
            renderStatus(statusCard, pr, false);
        }
        renderHistory(histCard, entries);
    }

    /**
     * v3.6.0 — zero-input Guard: projection derived from the last
     * fetched/cached bill's pace. Null when no bill is cached.
     * A real reading-based projection (refresh's else-branch)
     * always takes priority over this estimate.
     */
    private GuardCalc.Projection autoProjection() {
        try {
            LescoBillFetcher.BillInfo bill = loadBill();
            if (bill == null) return null;
            int units = bill.unitsAsInt();
            if (!(units > 0)) return null;
            String rd = GuardReminder.parseBillDate(bill.readingDate);
            String todayIso = GuardReminder.todayIso();
            int daysElapsed = rd == null ? 0 : Math.max(0,
                    GuardReminder.daysBetween(rd, todayIso));
            return GuardCalc.autoProjectFromBill(units,
                    GuardCalc.DEFAULT_CYCLE_DAYS, daysElapsed);
        } catch (Exception e) {
            return null;
        }
    }

    private void renderStatus(LinearLayout card,
            GuardCalc.Projection p, boolean estimate) {
        // v3.6.0: estimates are always labeled as andaza.
        if (estimate) {
            TextView note = new TextView(this);
            note.setText(getString(R.string.guard_estimate_note));
            note.setTextSize(12.5f);
            note.setTextColor(Ds.amber());
            Ds.body(this, note, 600);
            card.addView(note);
            card.addView(Ui.spacer(this, 8));
        }
        // v3.4.0: streak header — flame + consecutive weeks (v3.6.0),
        // with a celebration line on milestones.
        int streak = GuardStore.streakCount(this, meterKey);
        if (streak > 0) {
            TextView sk = new TextView(this);
            sk.setText(getString(R.string.streak_line, streak));
            sk.setTextSize(15);
            sk.setTextColor(Ds.ink());
            Ds.body(this, sk, 700);
            card.addView(sk);
            if (StreakCalc.isMilestone(streak)) {
                TextView ms = new TextView(this);
                ms.setText(getString(
                        streak == StreakCalc.MILESTONE_4W
                                ? R.string.streak_milestone_4w
                                : R.string.streak_milestone_12w));
                ms.setTextSize(13);
                ms.setTextColor(Ds.g600());
                Ds.body(this, ms, 600);
                card.addView(ms);
            }
            card.addView(Ui.spacer(this, 8));
        }

        int color;
        int strRes;
        if (p.status == GuardCalc.Status.DANGER) {
            color = Ds.red();
            strRes = R.string.guard_status_danger;
        } else if (p.status == GuardCalc.Status.WARNING) {
            color = Ds.amber();
            strRes = R.string.guard_status_warning;
        } else {
            color = Ds.g600();
            strRes = R.string.guard_status_safe;
        }

        TextView st = new TextView(this);
        st.setText(getString(strRes));
        st.setTextSize(15);
        st.setTextColor(color);
        Ds.body(this, st, 600);
        card.addView(st);
        card.addView(Ui.spacer(this, 6));

        // Big projection number.
        TextView proj = new TextView(this);
        proj.setText(getString(R.string.guard_projection,
                trimNum1(p.projectedUnits)));
        proj.setTextSize(30);
        proj.setTextColor(Ds.ink());
        Ds.amount(this, proj);
        card.addView(proj);
        card.addView(Ui.spacer(this, 4));

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.guard_used_so_far,
                trimNum1(p.usedUnits), p.daysElapsed));
        sub.setTextSize(13);
        sub.setTextColor(Ds.muted());
        Ds.body(this, sub, 400);
        card.addView(sub);
        card.addView(Ui.spacer(this, 10));

        // Progress bar: used / 200.
        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(2000);
        bar.setProgress((int) Math.min(2000,
                Math.round(p.usedUnits * 10)));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 12)));
        try {
            bar.getProgressDrawable().setColorFilter(color,
                    PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {
            // Unthemed progress bar is still readable.
        }
        card.addView(bar);
        card.addView(Ui.spacer(this, 10));

        TextView budget = new TextView(this);
        if (p.remainingDays > 0 && p.usedUnits < GuardCalc.UNIT_LIMIT) {
            budget.setText(getString(R.string.guard_daily_budget,
                    trimNum1(p.dailyBudget), p.remainingDays));
        } else if (p.usedUnits >= GuardCalc.UNIT_LIMIT) {
            budget.setText(getString(R.string.guard_over_limit));
        } else {
            budget.setText(getString(R.string.guard_cycle_over));
        }
        budget.setTextSize(14);
        budget.setTextColor(Ds.ink());
        Ds.body(this, budget, 600);
        card.addView(budget);
        card.addView(Ui.spacer(this, 4));

        TextView avg = new TextView(this);
        avg.setText(getString(R.string.guard_daily_avg,
                trimNum1(p.dailyAvg)));
        avg.setTextSize(12.5f);
        avg.setTextColor(Ds.muted());
        Ds.body(this, avg, 400);
        card.addView(avg);
    }

    private void renderHistory(LinearLayout card,
            List<GuardStore.Entry> entries) {
        card.addView(cardTitle(R.string.guard_history_title));
        card.addView(Ui.spacer(this, 6));
        if (entries.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(getString(R.string.guard_history_empty));
            t.setTextSize(13);
            t.setTextColor(Ds.faint());
            Ds.body(this, t, 400);
            card.addView(t);
            return;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            final GuardStore.Entry e = entries.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(e.dateIso + "  ·  " + trimNum(e.reading));
            t.setTextSize(14);
            t.setTextColor(Ds.ink());
            Ds.body(this, t, 500);
            Ds.mono(t);
            t.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(t);
            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextSize(16);
            del.setTextColor(Ds.red());
            del.setPadding(Ui.dp(this, 12), Ui.dp(this, 6),
                    Ui.dp(this, 4), Ui.dp(this, 6));
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    deleteEntry(e.dateIso);
                }
            });
            row.addView(del);
            card.addView(row);
            card.addView(Ds.dashDivider(this));
        }
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    private void saveAnchor() {
        double r;
        try {
            r = Double.parseDouble(anchorReadingEt.getText()
                    .toString().trim().replace(",", "."));
        } catch (Exception e) {
            r = -1;
        }
        if (!(r >= 0) || anchorDateIso == null) return;
        GuardStore.setAnchor(this, meterKey, r, anchorDateIso);
        GuardStore.setLastMeter(this, meterKey);
        GuardReminderAlarm.schedule(this);
        refresh();
    }

    private void saveReading() {
        double r;
        try {
            r = Double.parseDouble(readingEt.getText().toString()
                    .trim().replace(",", "."));
        } catch (Exception e) {
            r = -1;
        }
        if (!(r >= 0)) return;
        double anchor = GuardStore.anchorReading(this, meterKey, -1);
        if (!(anchor >= 0)) return; // anchor first
        if (r < anchor) return; // reading below anchor: ignore
        GuardStore.addEntry(this, meterKey, GuardReminder.todayIso(),
                r);
        GuardStore.setLastMeter(this, meterKey);
        // v3.6.0: weekly check-in streak — saving a reading
        // records one check-in for the calendar week (idempotent
        // within the week).
        String todayIso = GuardReminder.todayIso();
        StreakCalc.Result sr = StreakCalc.checkin(
                GuardStore.streakLast(this, meterKey),
                GuardStore.streakCount(this, meterKey), todayIso);
        GuardStore.saveStreak(this, meterKey, sr.lastIso, sr.count);
        GuardReminderAlarm.schedule(this);
        readingEt.setText("");
        refresh();
    }

    private void deleteEntry(String dateIso) {
        List<GuardStore.Entry> list =
                GuardStore.entries(this, meterKey);
        List<GuardStore.Entry> kept =
                new java.util.ArrayList<GuardStore.Entry>();
        for (GuardStore.Entry e : list) {
            if (!e.dateIso.equals(dateIso)) kept.add(e);
        }
        // Rewrite via GuardStore (entries are append-only otherwise).
        GuardStore.rewriteEntries(this, meterKey, kept);
        refresh();
    }

    private void pickAnchorDate() {
        Calendar c = Calendar.getInstance();
        Date d = GuardReminder.parseIso(anchorDateIso);
        if (d != null) {
            c.setTime(d);
        }
        DatePickerDialog dlg = new DatePickerDialog(this,
                new DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(DatePicker view,
                            int y, int m, int day) {
                        Calendar cc = Calendar.getInstance();
                        cc.set(y, m, day, 0, 0, 0);
                        cc.set(Calendar.MILLISECOND, 0);
                        anchorDateIso =
                                GuardReminder.isoOf(cc.getTime());
                        styleDateBtn(anchorDateBtn);
                    }
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH));
        dlg.show();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    /**
     * The bill prints the reading date in its own format; accept a
     * few common ones, else null (caller falls back to today).
     */
    private static String parseBillDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] patterns = {"dd-MMM-yy", "dd-MMM-yyyy", "dd/MM/yyyy",
                "dd-MM-yyyy", "yyyy-MM-dd"};
        for (String pat : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pat,
                        Locale.US);
                f.setLenient(false);
                Date d = f.parse(raw.trim().toUpperCase(Locale.US));
                return GuardReminder.isoOf(d);
            } catch (Exception ignored) {
                // Try the next pattern.
            }
        }
        return null;
    }

    private TextView cardTitle(int res) {
        TextView t = new TextView(this);
        t.setText(getString(res));
        t.setTextSize(15);
        t.setTextColor(Ds.ink());
        Ds.heading(this, t);
        return t;
    }

    private TextView fieldLabel(int res) {
        TextView t = new TextView(this);
        t.setText(getString(res));
        t.setTextSize(12.5f);
        t.setTextColor(Ds.muted());
        Ds.body(this, t, 500);
        return t;
    }

    private void styleInput(EditText et) {
        et.setTextSize(16);
        et.setTextColor(Ds.ink());
        Ds.body(this, et, 500);
        Ds.mono(et);
        et.setBackgroundResource(R.drawable.ds_input_bg);
        int p = Ui.dp(this, 12);
        et.setPadding(p, p, p, p);
    }

    private void styleDateBtn(TextView v) {
        v.setText(anchorDateIso == null ? "----/--/--" : anchorDateIso);
        v.setTextSize(16);
        v.setTextColor(Ds.ink());
        Ds.body(this, v, 500);
        Ds.mono(v);
        v.setBackgroundResource(R.drawable.ds_input_bg);
        int p = Ui.dp(this, 12);
        v.setPadding(p, p, p, p);
        v.setClickable(true);
        v.setFocusable(true);
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static String trimNum1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /** Opens the guard for a meter (Home / Meter Check entry). */
    public static void open(android.content.Context ctx, Disco disco,
            String mode, String ref, String reading) {
        Intent i = new Intent(ctx, GuardActivity.class);
        i.putExtra(EXTRA_DISCO, disco == null ? "lesco" : disco.code);
        i.putExtra(EXTRA_MODE, mode == null ? "ref" : mode);
        i.putExtra(EXTRA_REF, ref == null ? "" : ref);
        if (reading != null) i.putExtra(EXTRA_READING, reading);
        ctx.startActivity(i);
    }
}
