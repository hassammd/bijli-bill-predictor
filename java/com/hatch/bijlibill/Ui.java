package com.hatch.bijlibill;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v1.2 design system — every screen is built through these helpers so the
 * whole app shares one professional look:
 * deep teal (#00695C) + amber (#FFB300), white 12dp cards, 16dp rhythm,
 * 48dp touch targets, dp/sp only, and automatic Noto Nastaliq Urdu for any
 * Urdu text (with extra line spacing — Nastaliq is tall).
 */
public final class Ui {

    private Ui() {}

    // ---------- palette ----------
    public static int teal()       { return Color.parseColor("#00695C"); }
    public static int tealDark()   { return Color.parseColor("#004D40"); }
    public static int tealLight()  { return Color.parseColor("#B2DFDB"); }
    public static int tealPale()   { return Color.parseColor("#E0F2F1"); }
    public static int amber()      { return Color.parseColor("#FFB300"); }
    public static int amberDark()  { return Color.parseColor("#FF8F00"); }
    public static int pageBg()     { return Color.parseColor("#F4F7F6"); }
    public static int dark()       { return Color.parseColor("#212121"); }
    public static int grey()       { return Color.parseColor("#757575"); }
    public static int errorRed()   { return Color.parseColor("#C62828"); }
    public static int errorBg()    { return Color.parseColor("#FFEBEE"); }
    public static int tipBlue()    { return Color.parseColor("#0D47A1"); }
    public static int tipBg()      { return Color.parseColor("#E3F2FD"); }
    // v1.3: amber-tinted smart-tip palette (matches approved mockup)
    public static int tipAmberBg()    { return Color.parseColor("#FFF8E1"); }
    public static int tipAmberBorder(){ return Color.parseColor("#FFE082"); }
    public static int tipAmberText()  { return Color.parseColor("#5D4A00"); }
    public static int tipAmberBold()  { return Color.parseColor("#3D2B00"); }
    public static int greyLight()  { return Color.parseColor("#9E9E9E"); }
    public static int darkText()   { return Color.parseColor("#3D2B00"); }

    // ---------- metrics ----------
    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- containers ----------
    public static LinearLayout col(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    /** Scrollable page root with the app background. */
    public static ScrollView page(Context ctx) {
        ScrollView s = new ScrollView(ctx);
        LinearLayout root = col(ctx);
        root.setBackgroundColor(pageBg());
        s.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        s.setTag(root);
        return s;
    }

    public static LinearLayout pageRoot(ScrollView s) {
        return (LinearLayout) s.getTag();
    }

    /** White 10dp card with subtle elevation. */
    public static LinearLayout card(Context ctx) {
        LinearLayout c = col(ctx);
        c.setBackgroundResource(R.drawable.card_bg);
        int p = dp(ctx, 16);
        c.setPadding(p, p, p, p);
        if (android.os.Build.VERSION.SDK_INT >= 21) c.setElevation(dp(ctx, 3));
        return c;
    }

    /** Card wrapped with the standard 16dp side / 12dp top rhythm. */
    public static LinearLayout padded(Context ctx, View card) {
        LinearLayout wrap = col(ctx);
        wrap.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), 0);
        wrap.addView(card);
        return wrap;
    }

    public static View spacer(Context ctx, int dph) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, dph)));
        return v;
    }

    // ---------- text ----------
    /**
     * Core text builder. Urdu text automatically gets the Nastaliq typeface
     * + extra line spacing; bold is ignored for Urdu (Nastaliq has no bold
     * and fake-bold looks bad). Sizes are sp.
     */
    public static TextView tv(Context ctx, CharSequence text, float sp,
                              boolean bold, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (Fonts.isUrdu(text)) {
            t.setTypeface(Fonts.urdu(ctx));
            t.setLineSpacing(dp(ctx, 5), 1.25f);
        } else if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    public static TextView tvRes(Context ctx, int resId, float sp,
                                 boolean bold, int color) {
        return tv(ctx, ctx.getString(resId), sp, bold, color);
    }

    /** v2.5.0 type scale: 16sp section heading, teal. */
    public static TextView sectionTitle(Context ctx, int resId) {
        return tvRes(ctx, resId, 16, true, teal());
    }

    /** v2.5.0: 14sp body text. */
    public static TextView body(Context ctx, int resId) {
        return tvRes(ctx, resId, 14, false, dark());
    }

    /** v2.5.0: 12sp caption text, grey. */
    public static TextView caption(Context ctx, int resId) {
        return tvRes(ctx, resId, 12, false, grey());
    }

    /** v2.5.0: 20sp bold screen title (for in-content headings). */
    public static TextView title20(Context ctx, int resId) {
        return tvRes(ctx, resId, 20, true, dark());
    }

    /** Small card heading, 13sp dark-teal (matches approved mockup). */
    public static TextView cardTitle(Context ctx, int resId) {
        return tvRes(ctx, resId, 13, true, tealDark());
    }

    /** Small grey field label above an input, 12sp (matches mockup). */
    public static TextView fieldLabel(Context ctx, int resId) {
        TextView t = tvRes(ctx, resId, 12, true, grey());
        t.setPadding(0, 0, 0, dp(ctx, 6));
        return t;
    }

    public static TextView hint(Context ctx, int resId) {
        return tvRes(ctx, resId, 13, false, grey());
    }

    // ---------- buttons (48dp touch targets) ----------
    private static Button baseBtn(Context ctx, CharSequence text, float sp) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(sp);
        b.setMinHeight(dp(ctx, 48));
        b.setAllCaps(false);
        return b;
    }

    /** Full-width primary action button (teal). */
    public static Button primaryButton(Context ctx, int resId) {
        Button b = baseBtn(ctx, ctx.getString(resId), 16);
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        Fonts.applyIfUrdu(ctx, b);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** Full-width accent button (amber, dark text) — e.g. premium upsell. */
    public static Button accentButton(Context ctx, int resId) {
        Button b = baseBtn(ctx, ctx.getString(resId), 15);
        b.setBackgroundResource(R.drawable.btn_accent);
        b.setTextColor(dark());
        b.setTypeface(Typeface.DEFAULT_BOLD);
        Fonts.applyIfUrdu(ctx, b);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** v1.3: amber CALCULATE button with dark bold text (approved mockup). */
    public static Button calculateButton(Context ctx, int resId) {
        Button b = baseBtn(ctx, ctx.getString(resId), 15);
        b.setBackgroundResource(R.drawable.btn_calculate);
        b.setTextColor(darkText());
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(ctx, 52));
        Fonts.applyIfUrdu(ctx, b);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** v1.3: dark UPGRADE button on the premium gradient card. */
    public static Button upgradeButton(Context ctx, int resId) {
        Button b = baseBtn(ctx, ctx.getString(resId), 13);
        b.setBackgroundResource(R.drawable.btn_upgrade);
        b.setTextColor(amber());
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(ctx, 44));
        Fonts.applyIfUrdu(ctx, b);
        return b;
    }

    /** Quiet text button (teal text, transparent bg). */
    public static Button ghostButton(Context ctx, int resId) {        Button b = baseBtn(ctx, ctx.getString(resId), 14);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(teal());
        Fonts.applyIfUrdu(ctx, b);
        return b;
    }

    /** Outline button (white bg, teal border) — e.g. refresh. */
    public static Button outlineButton(Context ctx, int resId) {
        Button b = baseBtn(ctx, ctx.getString(resId), 14);
        b.setBackgroundResource(R.drawable.btn_outline);
        b.setTextColor(teal());
        b.setTypeface(Typeface.DEFAULT_BOLD);
        Fonts.applyIfUrdu(ctx, b);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** Small grey utility button (e.g. CLEAR). */
    public static Button smallButton(Context ctx, int resId) {        Button b = baseBtn(ctx, ctx.getString(resId), 12);
        b.setBackgroundColor(Color.parseColor("#E0E0E0"));
        b.setTextColor(dark());
        b.setMinHeight(dp(ctx, 40));
        Fonts.applyIfUrdu(ctx, b);
        return b;
    }

    // ---------- inputs ----------
    public static EditText numberField(Context ctx, int hintRes) {
        EditText e = new EditText(ctx);
        e.setHint(ctx.getString(hintRes));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextSize(16);
        e.setTextColor(dark());
        e.setHintTextColor(grey());
        e.setBackgroundResource(R.drawable.textfield_bg);
        int p = dp(ctx, 12);
        e.setPadding(p, p, p, p);
        e.setMinHeight(dp(ctx, 52));
        return e;
    }

    /** v1.4: decimal-capable field (FPA Rs/unit, bill amounts). Same styling. */
    public static EditText decimalField(Context ctx, int hintRes) {
        EditText e = numberField(ctx, hintRes);
        e.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    // ---------- app header with pill language toggle ----------
    public interface LangToggle { void onToggle(); }

    /** Holder for the header's title/subtitle so screens can update them. */
    public static final class HeaderViews {
        public final TextView title;
        public final TextView sub;
        HeaderViews(TextView t, TextView s) { title = t; sub = s; }
    }

    /**
     * v1.3 teal header (approved mockup), v2.5.0 type scale: 20sp bold
     * title + 12sp subtitle on the left, amber-pill EN/اردو toggle on
     * the right.
     */
    public static LinearLayout header(final Activity act, int titleRes,
                                      int subtitleRes, final LangToggle toggle) {
        final Context ctx = act;
        LinearLayout h = col(ctx);
        h.setBackgroundColor(teal());
        h.setPadding(dp(ctx, 16), dp(ctx, 20), dp(ctx, 16), dp(ctx, 20));

        LinearLayout top = row(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = tvRes(ctx, titleRes, 20, true, Color.WHITE);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // v3.6.11: right margin for spacing
        ((LinearLayout.LayoutParams) title.getLayoutParams()).rightMargin =
                dp(ctx, 12);
        top.addView(title);
        // v3.6.11: same toggle as other pages (was langPill)
        top.addView(Ds.langToggle(act));
        h.addView(top);

        TextView sub = tvRes(ctx, subtitleRes, 12, false, tealLight());
        sub.setPadding(0, dp(ctx, 4), 0, 0);
        h.addView(sub);
        h.setTag(new HeaderViews(title, sub));
        return h;
    }

    public static void setHeaderTitle(View header, CharSequence t) {
        Object o = header.getTag();
        if (o instanceof HeaderViews) ((HeaderViews) o).title.setText(t);
    }

    public static void setHeaderSubtitle(View header, CharSequence t) {
        Object o = header.getTag();
        if (o instanceof HeaderViews) ((HeaderViews) o).sub.setText(t);
    }

    /**
     * v1.3 pill toggle (approved mockup): rounded container with two
     * segments; the active language sits on an amber pill with dark text.
     * Tapping the inactive segment switches language.
     *
     * @param onLight true on light backgrounds (teal container + teal
     *               inactive text, e.g. the About language card)
     */
    public static LinearLayout langPill(final Activity act, boolean onLight,
                                        final LangToggle toggle) {
        final boolean ur = LocaleHelper.isUrdu(act);
        LinearLayout pill = row(act);
        pill.setBackgroundResource(onLight
                ? R.drawable.toggle_bg_light : R.drawable.toggle_bg);
        int pad = dp(act, 3);
        pill.setPadding(pad, pad, pad, pad);
        pill.setGravity(Gravity.CENTER_VERTICAL);

        TextView en = pillSeg(act, "EN", !ur, onLight);
        TextView urdu = pillSeg(act, act.getString(R.string.lang_urdu), ur, onLight);
        en.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (ur) toggle.onToggle(); }
        });
        urdu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!ur) toggle.onToggle(); }
        });
        pill.addView(en);
        pill.addView(urdu);
        return pill;
    }

    private static TextView pillSeg(Context ctx, CharSequence text,
                                    boolean active, boolean onLight) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12);
        int p = dp(ctx, 9), ph = dp(ctx, 13);
        t.setPadding(ph, p, ph, p);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(ctx, 44));
        t.setMinWidth(dp(ctx, 52));
        if (active) {
            t.setBackgroundResource(R.drawable.toggle_seg_on);
            t.setTextColor(darkText());
            t.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            t.setBackgroundColor(Color.TRANSPARENT);
            t.setTextColor(onLight ? teal() : Color.WHITE);
        }
        if (Fonts.isUrdu(text)) {
            t.setTypeface(Fonts.urdu(ctx));
            t.setLineSpacing(dp(ctx, 2), 1.25f);
        }
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** v1.3: amber circle badge with dark vector bolt (splash + About). */
    public static FrameLayout boltBadge(Context ctx, int dpSize) {
        FrameLayout f = new FrameLayout(ctx);
        int s = dp(ctx, dpSize);
        f.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        ImageView circle = new ImageView(ctx);
        circle.setBackgroundResource(R.drawable.circle_amber);
        circle.setLayoutParams(new FrameLayout.LayoutParams(s, s));
        ImageView bolt = new ImageView(ctx);
        bolt.setImageResource(R.drawable.ic_bolt);
        int b = s / 2;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                b, b, Gravity.CENTER);
        bolt.setLayoutParams(lp);
        f.addView(circle);
        f.addView(bolt);
        return f;
    }

    /** v1.3: amber-tinted smart-tip card (approved mockup). */
    public static TextView tipCard(Context ctx) {
        TextView t = new TextView(ctx);
        t.setBackgroundResource(R.drawable.tip_card_bg);
        t.setTextSize(12);
        t.setTextColor(tipAmberText());
        t.setLineSpacing(dp(ctx, 4), 1.5f);
        int p = dp(ctx, 12);
        t.setPadding(p, p, p, p);
        return t;
    }

    /** v1.3: teal-pale rounded pill, e.g. the rates version line. */
    public static TextView ratePill(Context ctx, CharSequence text) {
        TextView t = tv(ctx, text, 12, true, teal());
        t.setBackgroundResource(R.drawable.toggle_bg_light);
        t.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Thin horizontal divider line. */
    public static View divider(Context ctx, int color, int dpH) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, dpH)));
        return v;
    }

    // ---------- table rows (weight-based, no clipping) ----------
    private static TextView cell(Context ctx, CharSequence text, float sp,
                                 boolean bold, int color, float weight, int gravity) {
        TextView t = tv(ctx, text, sp, bold, color);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        t.setGravity(gravity);
        t.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    public static LinearLayout tableHeader(Context ctx, int... resIds) {
        LinearLayout r = row(ctx);
        r.setBackgroundColor(teal());
        float[] w = {1.5f, 1f, 1.3f, 1.5f};
        for (int i = 0; i < resIds.length; i++) {
            r.addView(cell(ctx, ctx.getString(resIds[i]), 13, true,
                    Color.WHITE, w[i], Gravity.CENTER));
        }
        return r;
    }

    public static LinearLayout tableRow(Context ctx, String[] cols, boolean alt) {
        LinearLayout r = row(ctx);
        if (alt) r.setBackgroundColor(Color.parseColor("#F7FAF9"));
        float[] w = {1.5f, 1f, 1.3f, 1.5f};
        for (int i = 0; i < cols.length; i++) {
            r.addView(cell(ctx, cols[i], 13, false, dark(), w[i],
                    i == 0 ? Gravity.START : Gravity.CENTER));
        }
        return r;
    }

    /** Label/value row (weight-based, wraps on narrow screens). */
    public static LinearLayout moneyRow(Context ctx, CharSequence label,
                                        CharSequence value, boolean boldValue) {
        LinearLayout r = row(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = tv(ctx, label, 14, false, dark());
        l.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = tv(ctx, value, 14, boldValue, dark());
        v.setGravity(Gravity.END);
        r.addView(l);
        r.addView(v);
        r.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
        return r;
    }

    /** Friendly empty-state text, centered. */
    public static TextView emptyState(Context ctx, int resId) {
        TextView t = tvRes(ctx, resId, 14, false, grey());
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 8), dp(ctx, 16), dp(ctx, 8), dp(ctx, 16));
        return t;
    }

    /**
     * v2.5.0 selectable chip (disco selector, badges): 48dp touch
     * target, rounded 24dp background; selected = teal fill + white
     * text, unselected = pale outline + dark text, disabled = grey.
     */
    public static TextView chip(Context ctx, CharSequence text,
                                boolean selected, boolean enabled) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(ctx, 48));
        int hp = dp(ctx, 18), vp = dp(ctx, 10);
        t.setPadding(hp, vp, hp, vp);
        t.setClickable(enabled);
        t.setFocusable(enabled);
        if (!enabled) {
            t.setBackgroundResource(R.drawable.chip_disabled);
            t.setTextColor(greyLight());
        } else if (selected) {
            t.setBackgroundResource(R.drawable.chip_selected);
            t.setTextColor(Color.WHITE);
            t.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            t.setBackgroundResource(R.drawable.chip_idle);
            t.setTextColor(dark());
        }
        if (Fonts.isUrdu(text)) {
            t.setTypeface(Fonts.urdu(ctx));
            t.setLineSpacing(dp(ctx, 2), 1.25f);
        }
        return t;
    }

    /** v2.5.0 small non-clickable badge (e.g. the disco tag on a row). */
    public static TextView badge(Context ctx, CharSequence text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.chip_idle);
        t.setTextColor(tealDark());
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int hp = dp(ctx, 10), vp = dp(ctx, 5);
        t.setPadding(hp, vp, hp, vp);
        return t;
    }
}
