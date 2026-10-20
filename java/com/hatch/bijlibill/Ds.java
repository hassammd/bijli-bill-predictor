package com.hatch.bijlibill;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;

/**
 * v2.6.0 design-system helpers: Archivo for headings/buttons/labels,
 * Plus Jakarta Sans for body, Noto Nastaliq Urdu whenever the text
 * itself is Urdu (design's .urdu rule wins over the Latin stack).
 * Always call AFTER setText().
 */
final class Ds {

    private Ds() {}

    static int bg()        { return Color.parseColor("#F3F8F7"); }
    static int ink()       { return Color.parseColor("#123D46"); }
    static int muted()     { return Color.parseColor("#71858B"); }
    static int line()      { return Color.parseColor("#E1EFEC"); }
    static int deepGreen() { return Color.parseColor("#0D766E"); }
    static int g800()      { return Color.parseColor("#064E50"); }
    static int g900()      { return Color.parseColor("#064E50"); }
    static int amber()     { return Color.parseColor("#FFC83D"); }
    static int amberDark() { return Color.parseColor("#3A2700"); }
    static int navIdle()   { return Color.parseColor("#A9BCB2"); }
    static int red()       { return Color.parseColor("#D92D20"); }
    static int g600()      { return Color.parseColor("#0D766E"); }
    static int stepTodo()  { return Color.parseColor("#E1EFEC"); }
    static int greenBg()     { return Color.parseColor("#DDF5EF"); }
    static int faint()       { return Color.parseColor("#71858B"); }

    /** v3.6.60: teal-tinted shadow for cards (user feedback). API 28+. */
    static void tealShadow(android.view.View v) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineSpotShadowColor(Color.parseColor("#0D766E"));
        }
    }
    static int stepNum()   { return Color.parseColor("#3A2700"); }
    static int blueBg()    { return Color.parseColor("#EAF1FD"); }
    static int blueBorder(){ return Color.parseColor("#CCD9F5"); }
    static int blueText()  { return Color.parseColor("#243B6B"); }
    static int redBg()     { return Color.parseColor("#FDECEA"); }

    // v3.6.54: New bill page design colors (2026-10-19)
    static int ndPrimaryDark() { return Color.parseColor("#064E50"); }
    static int ndPrimaryTeal() { return Color.parseColor("#0D766E"); }
    static int ndTealAccent() { return Color.parseColor("#11998E"); }
    static int ndAppBg()     { return Color.parseColor("#F3F8F7"); }
    static int ndCardWhite() { return Color.parseColor("#FFFFFF"); }
    static int ndMintGreen() { return Color.parseColor("#DDF5EF"); }
    static int ndDarkText()  { return Color.parseColor("#123D46"); }
    static int ndSecondary() { return Color.parseColor("#71858B"); }
    static int ndAmber()     { return Color.parseColor("#FFC83D"); }
    static int ndLightBorder(){ return Color.parseColor("#E1EFEC"); }
    static int redBorder() { return Color.parseColor("#F5C6C0"); }
    static int redText()   { return Color.parseColor("#7A271A"); }
    static int progTrack() { return Color.parseColor("#E7EDE9"); }
    static int linkBlue()  { return Color.parseColor("#2F5FD0"); }
    static int tipGreen()  { return Color.parseColor("#2E7D32"); }

    /** Headings, buttons, labels, chips, nav: Archivo 800 (or Nastaliq). */
    static void heading(Context ctx, TextView v) {
        v.setTypeface(Fonts.isUrdu(v.getText())
                ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 800));
    }

    /** Big amounts (hero figures): Archivo 900 Black (or Nastaliq). */
    static void amount(Context ctx, TextView v) {
        v.setTypeface(Fonts.isUrdu(v.getText())
                ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 900));
    }

    /**
     * Reference numbers and meter readings: system monospace.
     * (User rule: koi aur font use mat karo — monospace is not a
     * bundled font, it is the platform fixed-width face.)
     */
    static void mono(TextView v) {
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    /**
     * Body text: Plus Jakarta Sans, 400 or 500 only (user rule).
     * Heavier requested weights clamp down to 500.
     */
    /** v3.0.0: body = Plus Jakarta Sans 400 / 500 / 600 only. */
    static void body(Context ctx, TextView v, int weight) {
        int w = weight >= 600 ? 600 : weight >= 500 ? 500 : 400;
        v.setTypeface(Fonts.isUrdu(v.getText())
                ? Fonts.urdu(ctx) : Fonts.jakarta(ctx, w));
    }

    /**
     * Segmented EN/اردو toggle (design): tapping the inactive side
     * switches language and recreates the activity.
     */
    static android.view.View langToggle(
            final android.app.Activity activity) {
        final boolean isUrdu = LocaleHelper.isUrdu(activity);
        // v3.7.12: smaller still (user on v3.7.11: width kam mehsoos
        // nahi hui). Segment 50→42dp, height 34→30dp; total track
        // 106→90dp.
        final int segW = Ui.dp(activity, 42);
        final int trackH = Ui.dp(activity, 30);
        final int pad = Ui.dp(activity, 3);

        // v3.7.7: SLIDING pill toggle (user: toggle jerk karta hai, pill
        // left-right liquid flow ki tarah smooth move kare). Before: two
        // static buttons — bg swap + instant recreate = hard jerk.
        // Now: one amber pill glides under the labels (320ms overshoot =
        // liquid settle), THEN language switches with a fade transition.
        final android.widget.FrameLayout track =
                new android.widget.FrameLayout(activity);
        track.setBackgroundResource(R.drawable.ds_lang_bg);
        track.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
        // Minimum sizes (not LayoutParams): some parents override the
        // toggle's LayoutParams with WRAP_CONTENT — the pill math must
        // survive that.
        track.setMinimumWidth(segW * 2 + pad * 2);
        track.setMinimumHeight(trackH);

        final android.view.View pill = new android.view.View(activity);
        android.widget.FrameLayout.LayoutParams plp =
                new android.widget.FrameLayout.LayoutParams(
                        segW, trackH - pad * 2);
        plp.leftMargin = pad;
        plp.topMargin = pad;
        pill.setLayoutParams(plp);
        pill.setBackgroundResource(R.drawable.ds_lang_on);
        pill.setTranslationX(isUrdu ? segW : 0);
        track.addView(pill);

        android.widget.LinearLayout labels =
                new android.widget.LinearLayout(activity);
        labels.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        labels.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, trackH));
        // v3.7.9: row padding = pill inset, so each label sits exactly
        // over its pill zone (v3.7.12 sizes: 3..45 / 45..87).
        labels.setPadding(pad, 0, pad, 0);
        final TextView en = langLabel(activity, "EN", !isUrdu, segW);
        final TextView ur = langLabel(activity, "اردو", isUrdu, segW);
        labels.addView(en);
        labels.addView(ur);
        track.addView(labels);

        final boolean[] busy = {false};
        android.view.View.OnClickListener onTap =
                new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (busy[0]) return;
                final boolean targetUrdu = (v == ur);
                if (targetUrdu == LocaleHelper.isUrdu(activity)) return;
                busy[0] = true;
                // Label colors swap at animation start (instant feedback).
                en.setTextColor(targetUrdu ? muted() : amberDark());
                ur.setTextColor(targetUrdu ? amberDark() : muted());
                pill.animate()
                        .translationX(targetUrdu ? segW : 0)
                        .setDuration(320)
                        .setInterpolator(
                                new android.view.animation.OvershootInterpolator(0.9f))
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                LocaleHelper.set(activity,
                                        targetUrdu ? LocaleHelper.LANG_UR
                                                : LocaleHelper.LANG_EN);
                                activity.recreate();
                                activity.overridePendingTransition(
                                        android.R.anim.fade_in,
                                        android.R.anim.fade_out);
                            }
                        })
                        .start();
            }
        };
        en.setOnClickListener(onTap);
        ur.setOnClickListener(onTap);
        return track;
    }

    private static TextView langLabel(Context ctx, String text,
            boolean active, int widthPx) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setTypeface(Fonts.archivo(ctx, 800));
        b.setGravity(android.view.Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setSingleLine(true);
        // v3.7.9: FIXED width — weight-based columns inside a
        // wrap-content FrameLayout collapsed the اردو label to ~0 width
        // and wrapped it glyph-by-glyph (user screenshot). Every size in
        // this toggle is now an explicit dp; nothing depends on weights.
        b.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                widthPx, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        b.setTextColor(active ? amberDark() : muted());
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    // ------------------------------------------------------------------
    // v2.6.0 Unit Check / Estimate components (design CSS: .btn-green,
    // .btn-outline, .steps, .pill.green, .alert-card, .prog, .linklike,
    // .detail-row.leader, .illus).
    // ------------------------------------------------------------------

    /**
     * Primary green button: 58dp, 18dp radius, gradient, Archivo 800,
     * 15sp, optional left icon. Design: .btn.btn-green.
     */
    static android.widget.Button greenBtn(Context ctx, String text,
            int iconRes, android.view.View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Fonts.isUrdu(text)
                ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 800));
        if (android.os.Build.VERSION.SDK_INT >= 21
                && !Fonts.isUrdu(text)) {
            b.setLetterSpacing(0.05f);
        }
        b.setBackgroundResource(R.drawable.nd_btn_solid);
        b.setMinHeight(Ui.dp(ctx, 54));
        b.setGravity(android.view.Gravity.CENTER);
        if (iconRes != 0) {
            b.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
            b.setCompoundDrawablePadding(Ui.dp(ctx, 10));
        }
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.setElevation(Ui.dp(ctx, 2));
        }
        b.setOnClickListener(l);
        return b;
    }

    /**
     * Outline button: white, 1.5dp line border, 18dp radius, g800
     * text+icon. Design: .btn.btn-outline.
     */
    static android.widget.Button outlineBtn(Context ctx, String text,
            int iconRes, android.view.View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        b.setTextColor(g800());
        b.setTypeface(Fonts.isUrdu(text)
                ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 800));
        if (android.os.Build.VERSION.SDK_INT >= 21
                && !Fonts.isUrdu(text)) {
            b.setLetterSpacing(0.05f);
        }
        b.setBackgroundResource(R.drawable.ds_btn_outline);
        b.setMinHeight(Ui.dp(ctx, 54));
        b.setGravity(android.view.Gravity.CENTER);
        if (iconRes != 0) {
            b.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
            b.setCompoundDrawablePadding(Ui.dp(ctx, 10));
        }
        b.setOnClickListener(l);
        return b;
    }

    /**
     * v3.0.0: slim warning strip — #FDF0EE bg, 14dp radius, red icon,
     * 13sp/600 #8A2B20 text (line-height ~1.5). Conditional warnings only.
     */
    static android.widget.LinearLayout warnStrip(Context ctx, int iconRes,
            CharSequence htmlText) {
        android.widget.LinearLayout row =
                new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.ds_warn_strip);
        row.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14),
                Ui.dp(ctx, 12));
        android.widget.ImageView ic = new android.widget.ImageView(ctx);
        ic.setImageResource(iconRes);
        ic.setColorFilter(Color.parseColor("#D92D20"),
                android.graphics.PorterDuff.Mode.SRC_IN);
        int is = Ui.dp(ctx, 20);
        android.widget.LinearLayout.LayoutParams ilp =
                new android.widget.LinearLayout.LayoutParams(is, is);
        ilp.rightMargin = Ui.dp(ctx, 10);
        ic.setLayoutParams(ilp);
        row.addView(ic);
        TextView t = new TextView(ctx);
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            t.setText(android.text.Html.fromHtml(htmlText.toString(),
                    android.text.Html.FROM_HTML_MODE_LEGACY));
        } else {
            t.setText(android.text.Html.fromHtml(htmlText.toString()));
        }
        t.setTextSize(13);
        t.setTextColor(Color.parseColor("#8A2B20"));
        t.setLineSpacing(0, 1.5f);
        body(ctx, t, 600);
        t.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        return row;
    }

    /** "← BACK" green text link, centered. */
    static TextView backLink(Context ctx,
            android.view.View.OnClickListener l) {
        TextView t = new TextView(ctx);
        t.setText(ctx.getString(R.string.nav_back));
        t.setTextSize(13.5f);
        t.setTextColor(deepGreen());
        t.setTypeface(Fonts.isUrdu(t.getText())
                ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 700));
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    /**
     * 3-step progress indicator. states: 0 = todo, 1 = done, 2 = now.
     * Design: .steps / .step (.n 34dp circle, .t 11px label).
     */
    static android.widget.LinearLayout steps(Context ctx,
            String[] labels, int[] states) {
        android.widget.LinearLayout row =
                new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                // Connector line: green when the step to its right is
                // done (design: .step.done::before).
                android.view.View line = new android.view.View(ctx);
                android.widget.LinearLayout.LayoutParams llp =
                        new android.widget.LinearLayout.LayoutParams(
                                0, Ui.dp(ctx, 2), 1f);
                llp.topMargin = Ui.dp(ctx, 22);
                line.setLayoutParams(llp);
                line.setBackgroundColor(
                        states[i] == 1 ? g600() : stepTodo());
                row.addView(line);
            }
            android.widget.LinearLayout col =
                    new android.widget.LinearLayout(ctx);
            col.setOrientation(
                    android.widget.LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, android.view.ViewGroup.LayoutParams
                                    .WRAP_CONTENT, 1f);
            col.setLayoutParams(clp);

            android.widget.FrameLayout circleBox =
                    new android.widget.FrameLayout(ctx);
            int box = Ui.dp(ctx, 46);
            circleBox.setLayoutParams(
                    new android.widget.LinearLayout.LayoutParams(
                            box, box));
            if (states[i] == 2) {
                // Active glow ring (design: amber 6px ring).
                android.view.View ring = new android.view.View(ctx);
                ring.setLayoutParams(new android.widget.FrameLayout
                        .LayoutParams(box, box));
                ring.setBackgroundResource(R.drawable.ds_step_ring);
                circleBox.addView(ring);
            }
            android.widget.FrameLayout circle =
                    new android.widget.FrameLayout(ctx);
            int cs = Ui.dp(ctx, 28);
            android.widget.FrameLayout.LayoutParams ccp =
                    new android.widget.FrameLayout.LayoutParams(cs, cs);
            ccp.gravity = android.view.Gravity.CENTER;
            circle.setLayoutParams(ccp);
            circle.setBackgroundResource(states[i] == 1
                    ? R.drawable.ds_step_done
                    : states[i] == 2 ? R.drawable.ds_step_now
                    : R.drawable.ds_step_todo);
            if (states[i] == 1) {
                android.widget.ImageView check =
                        new android.widget.ImageView(ctx);
                android.widget.FrameLayout.LayoutParams ilp =
                        new android.widget.FrameLayout.LayoutParams(
                                Ui.dp(ctx, 15), Ui.dp(ctx, 15));
                ilp.gravity = android.view.Gravity.CENTER;
                check.setLayoutParams(ilp);
                check.setImageResource(R.drawable.ic_check_white);
                circle.addView(check);
            } else {
                TextView n = new TextView(ctx);
                n.setText(String.valueOf(i + 1));
                n.setTextSize(12);
                n.setTextColor(states[i] == 2 ? stepNum() : muted());
                n.setTypeface(Fonts.archivo(ctx, 800));
                n.setGravity(android.view.Gravity.CENTER);
                n.setLayoutParams(new android.widget.FrameLayout
                        .LayoutParams(
                                android.view.ViewGroup.LayoutParams
                                        .MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams
                                        .MATCH_PARENT));
                circle.addView(n);
            }
            circleBox.addView(circle);
            col.addView(circleBox);

            TextView lab = new TextView(ctx);
            lab.setText(labels[i]);
            lab.setTextSize(10.5f);
            lab.setTextColor(states[i] == 0 ? faint() : g800());
            lab.setTypeface(Fonts.isUrdu(labels[i])
                    ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 700));
            lab.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams llab =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams
                                    .WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams
                                    .WRAP_CONTENT);
            llab.topMargin = Ui.dp(ctx, 7);
            lab.setLayoutParams(llab);
            col.addView(lab);
            row.addView(col);
        }
        return row;
    }

    /**
     * Green info pill with a monospace value tail, e.g.
     * "Bill wali reading: 4,625". Design: .pill.green.
     */
    static TextView greenPillMono(Context ctx, String prefix,
            String monoValue) {
        TextView t = new TextView(ctx);
        android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder();
        sb.append(prefix);
        int vStart = sb.length();
        sb.append(" ");
        sb.append(monoValue);
        sb.setSpan(new android.text.style.StyleSpan(
                android.graphics.Typeface.BOLD), vStart, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.TypefaceSpan("monospace"),
                vStart, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sb);
        t.setTextSize(11.5f);
        t.setTextColor(deepGreen());
        t.setTypeface(Fonts.isUrdu(prefix)
                ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 700));
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundResource(R.drawable.ds_pill_green);
        int px = Ui.dp(ctx, 12);
        t.setPadding(px, Ui.dp(ctx, 6), px, Ui.dp(ctx, 6));
        return t;
    }

    /**
     * Blue/red info card with an icon and rich text.
     * Design: .alert-card(.blue/.red).
     */
    static android.widget.LinearLayout alertCard(Context ctx,
            boolean red, int iconRes, int iconTint,
            CharSequence text) {
        android.widget.LinearLayout card =
                new android.widget.LinearLayout(ctx);
        card.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        card.setBackgroundResource(red
                ? R.drawable.ds_alert_red : R.drawable.ds_alert_blue);
        int p = Ui.dp(ctx, 15);
        card.setPadding(p, p, p, p);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView ic =
                new android.widget.ImageView(ctx);
        int is = Ui.dp(ctx, 20);
        android.widget.LinearLayout.LayoutParams ilp =
                new android.widget.LinearLayout.LayoutParams(is, is);
        ilp.rightMargin = Ui.dp(ctx, 13);
        ic.setLayoutParams(ilp);
        ic.setImageResource(iconRes);
        if (iconTint != 0) ic.setColorFilter(iconTint);
        card.addView(ic);

        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTextColor(red ? redText() : blueText());
        t.setLineSpacing(0, 1.6f);
        body(ctx, t, 400);
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams
                                .WRAP_CONTENT, 1f);
        t.setLayoutParams(tlp);
        card.addView(t);
        return card;
    }

    /** Amber-gradient progress bar (0..1). Design: .prog. */
    static android.view.View progBar(Context ctx, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        android.widget.LinearLayout track =
                new android.widget.LinearLayout(ctx);
        track.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        track.setBackgroundResource(R.drawable.ds_prog_track);
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams
                                .MATCH_PARENT,
                        Ui.dp(ctx, 8));
        tlp.topMargin = Ui.dp(ctx, 10);
        track.setLayoutParams(tlp);
        android.view.View fill = new android.view.View(ctx);
        android.widget.LinearLayout.LayoutParams flp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams
                                .MATCH_PARENT, f);
        fill.setLayoutParams(flp);
        fill.setBackgroundResource(R.drawable.ds_prog_fill);
        track.addView(fill);
        android.view.View rest = new android.view.View(ctx);
        rest.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams
                                .MATCH_PARENT, 1f - f));
        track.addView(rest);
        return track;
    }

    /**
     * Receipt-style dotted-leader row: label … value.
     * Design: .detail-row .leader .dots.
     */
    static android.widget.LinearLayout dottedRow(Context ctx,
            CharSequence label, String value, boolean total) {
        android.widget.LinearLayout row =
                new android.widget.LinearLayout(ctx);
        row.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pv = Ui.dp(ctx, 13);
        row.setPadding(Ui.dp(ctx, 2), pv, Ui.dp(ctx, 2), pv);

        TextView k = new TextView(ctx);
        k.setText(label);
        k.setTextSize(14);
        k.setTextColor(total ? g800() : muted());
        body(ctx, k, total ? 500 : 500);
        if (total) {
            k.setTypeface(Fonts.isUrdu(label.toString())
                    ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 800));
        }
        row.addView(k);

        android.view.View dots = new android.view.View(ctx);
        android.widget.LinearLayout.LayoutParams dlp =
                new android.widget.LinearLayout.LayoutParams(0,
                        Ui.dp(ctx, 2), 1f);
        int dm = Ui.dp(ctx, 8);
        dlp.leftMargin = dm;
        dlp.rightMargin = dm;
        dots.setLayoutParams(dlp);
        dots.setBackgroundResource(R.drawable.ds_dots);
        row.addView(dots);

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(total ? 16 : 14);
        v.setTextColor(total ? g800() : ink());
        if (total) {
            v.setTypeface(Fonts.archivo(ctx, 800));
        } else {
            body(ctx, v, 500);
            v.setTypeface(v.getTypeface(),
                    android.graphics.Typeface.BOLD);
        }
        row.addView(v);
        return row;
    }

    /** Dashed divider between dotted rows (design: dashed border). */
    static android.view.View dashDivider(Context ctx) {
        android.view.View d = new android.view.View(ctx);
        d.setLayoutParams(new android.widget.LinearLayout
                .LayoutParams(
                        android.view.ViewGroup.LayoutParams
                                .MATCH_PARENT,
                        Ui.dp(ctx, 1)));
        d.setBackgroundResource(R.drawable.ds_dash_line);
        return d;
    }

    /**
     * Blue text link with a leading icon, e.g. "Ye ANDAZA kyun hai?".
     * Design: a.linklike.
     */
    static android.widget.LinearLayout linkLike(Context ctx,
            String text, int iconRes, int color,
            android.view.View.OnClickListener l) {
        android.widget.LinearLayout row =
                new android.widget.LinearLayout(ctx);
        row.setOrientation(
                android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        android.widget.ImageView ic =
                new android.widget.ImageView(ctx);
        int is = Ui.dp(ctx, 15);
        android.widget.LinearLayout.LayoutParams ilp =
                new android.widget.LinearLayout.LayoutParams(is, is);
        ilp.rightMargin = Ui.dp(ctx, 6);
        ic.setLayoutParams(ilp);
        ic.setImageResource(iconRes);
        ic.setColorFilter(color);
        row.addView(ic);
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTextColor(color);
        t.setTypeface(Fonts.isUrdu(text)
                ? Fonts.urdu(ctx) : Fonts.archivo(ctx, 700));
        row.addView(t);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(l);
        int pv = Ui.dp(ctx, 4);
        row.setPadding(0, pv, 0, pv);
        return row;
    }

    /**
     * 96dp rounded illustration tile with a centered icon.
     * Design: .illus.
     */
    static android.widget.FrameLayout illus(Context ctx, int iconRes,
            int iconTint) {
        android.widget.FrameLayout f =
                new android.widget.FrameLayout(ctx);
        int s = Ui.dp(ctx, 76);
        f.setLayoutParams(new android.widget.LinearLayout
                .LayoutParams(s, s));
        f.setBackgroundResource(R.drawable.ds_illus);
        android.widget.ImageView ic =
                new android.widget.ImageView(ctx);
        int is = Ui.dp(ctx, 36);
        android.widget.FrameLayout.LayoutParams ilp =
                new android.widget.FrameLayout.LayoutParams(is, is);
        ilp.gravity = android.view.Gravity.CENTER;
        ic.setLayoutParams(ilp);
        ic.setImageResource(iconRes);
        if (iconTint != 0) ic.setColorFilter(iconTint);
        f.addView(ic);
        return f;
    }

    /**
     * White design card (22dp radius, 1dp line border, 18dp padding,
     * soft elevation). Design: .card.
     */
    static android.widget.LinearLayout card(Context ctx) {
        android.widget.LinearLayout c =
                new android.widget.LinearLayout(ctx);
        c.setOrientation(android.widget.LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.ds_card);
        int p = Ui.dp(ctx, 16);
        c.setPadding(p, p, p, p);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            c.setElevation(Ui.dp(ctx, 2));
        }
        tealShadow(c);
        return c;
    }

    /**
     * Design app bar: back button + title/subtitle + EN/اردو toggle.
     * Design: .appbar (backbtn 40dp, h2 19sp 800, p 12.5sp muted).
     */
    static android.widget.LinearLayout appBar(
            final android.app.Activity act,
            android.view.View.OnClickListener onBack,
            String titleText, String subText, boolean subMono) {
        final Context ctx = act;
        android.widget.LinearLayout bar =
                new android.widget.LinearLayout(ctx);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = Ui.dp(ctx, 20);
        bar.setPadding(p, Ui.dp(ctx, 14), p, Ui.dp(ctx, 10));
        // v3.6.69: light teal header (was dark green, user request).
        bar.setBackgroundColor(ndTealAccent());

        android.widget.ImageView back =
                new android.widget.ImageView(ctx);
        back.setImageResource(R.drawable.ic_chev_right);
        back.setRotation(180);
        // v3.6.61: dark arrow on white bg (was white-on-white, invisible).
        back.setColorFilter(ndPrimaryDark(),
                android.graphics.PorterDuff.Mode.SRC_IN);
        // v3.6.62: mint circular bg, 32dp (standardized, user request).
        // v3.6.64: 28dp, 8dp padding (proportioned with BillActivity).
        int bs = Ui.dp(ctx, 28);
        back.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(bs, bs));
        back.setBackgroundResource(R.drawable.nd_back_btn_light);
        int bp = Ui.dp(ctx, 8);
        back.setPadding(bp, bp, bp, bp);
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(onBack);
        bar.addView(back);

        android.widget.LinearLayout col =
                new android.widget.LinearLayout(ctx);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams clp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(ctx, 12);
        col.setLayoutParams(clp);

        TextView title = new TextView(ctx);
        title.setText(titleText);
        title.setTextSize(18);
        title.setTextColor(android.graphics.Color.WHITE);
        heading(ctx, title);
        col.addView(title);

        // v3.6.68: hide subtitle if null/empty.
        if (subText != null && !subText.trim().isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(subText);
            sub.setTextSize(12);
            sub.setTextColor(android.graphics.Color.parseColor("#B0C4C4"));
            if (subMono) mono(sub); else body(ctx, sub, 400);
            col.addView(sub);
        }
        bar.addView(col);

        android.widget.LinearLayout spacer =
                new android.widget.LinearLayout(ctx);
        spacer.setLayoutParams(
                new android.widget.LinearLayout.LayoutParams(
                        0, 1, 1f));
        bar.addView(spacer);
        bar.addView(langToggle(act));
        return bar;
    }

    // ------------------------------------------------------------------
    // v3.6.4: shared bottom navigation (tester feedback #3 — "bottom nav
    // sirf Home pe nazar aati hai"). Same look as HomeActivity.bottomNav(),
    // usable from any activity. activeTab: 0=home, 1=bills, 2=unit, 3=settings.
    // Navigation uses REORDER_TO_FRONT so tabs never stack duplicates.
    // ------------------------------------------------------------------
    static final int TAB_HOME = 0;
    static final int TAB_BILLS = 1;
    static final int TAB_UNIT = 2;
    static final int TAB_SETTINGS = 3;

    static View bottomNav(final Activity activity, final int activeTab) {
        return bottomNav(activity, activeTab, 8);
    }

    /**
     * v3.6.26: 8dp minimal for ALL — user pointed out bottom padding IS
     * the white space. No more guessing.
     */
    static View bottomNav(final Activity activity, final int activeTab,
            final int extraBottomDp) {
        final Context ctx = activity;
        LinearLayout nav = new LinearLayout(ctx);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackgroundColor(Color.WHITE);

        View divider = new View(ctx);
        divider.setBackgroundColor(Ds.line());
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1)));
        nav.addView(divider);

        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8),
                Ui.dp(ctx, extraBottomDp));

        row.addView(navTab(ctx, R.drawable.ic_nav_home, R.string.nav_home,
                activeTab == TAB_HOME, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (activeTab == TAB_HOME) {
                            if (activity instanceof HomeActivity) {
                                ((HomeActivity) activity).scrollToTop();
                            }
                        } else {
                            goTab(activity, HomeActivity.class);
                        }
                    }
                }));
        row.addView(navTab(ctx, R.drawable.ic_nav_bill, R.string.nav_bills,
                activeTab == TAB_BILLS, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (activeTab != TAB_BILLS) goTab(activity, BillsActivity.class);
                    }
                }));
        row.addView(navTab(ctx, R.drawable.ic_nav_unit, R.string.nav_unit,
                activeTab == TAB_UNIT, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // v3.6.5: go DIRECTLY to Meter Check from any tab
                        // (user request — no detour via Home).
                        if (activity instanceof HomeActivity) {
                            ((HomeActivity) activity).openMeterCheck();
                        } else if (activeTab != TAB_UNIT) {
                            openMeterCheckDirect(activity);
                        }
                    }
                }));
        row.addView(navTab(ctx, R.drawable.ic_nav_settings, R.string.nav_settings,
                activeTab == TAB_SETTINGS, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (activeTab != TAB_SETTINGS) goTab(activity, AboutActivity.class);
                    }
                }));
        nav.addView(row);
        return nav;
    }

    private static void goTab(Activity from, Class<?> cls) {
        Intent i = new Intent(from, cls);
        i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        from.startActivity(i);
    }

    /**
     * v3.6.5: launch MeterCheckActivity directly from any tab. Reads the same
     * last-bill state HomeActivity uses (SharedPreferences "bijli_bill_refs");
     * shows the standard "no bill" toast when nothing is cached.
     */
    private static void openMeterCheckDirect(Activity activity) {
        try {
            android.content.SharedPreferences sp = activity.getSharedPreferences(
                    "bijli_bill_refs", Context.MODE_PRIVATE);
            String mode = sp.getString("metercheck_last_mode", null);
            String ref = sp.getString("metercheck_last_ref", null);
            String discoCode = sp.getString("metercheck_last_disco", "lesco");
            if (mode != null && ref != null
                    && BillCache.getHtml(activity, discoCode + ":" + mode + ":" + ref) != null) {
                Intent i = new Intent(activity, MeterCheckActivity.class);
                i.putExtra(MeterCheckActivity.EXTRA_DISCO, discoCode);
                i.putExtra(MeterCheckActivity.EXTRA_MODE, mode);
                i.putExtra(MeterCheckActivity.EXTRA_REF, ref);
                i.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(i);
                return;
            }
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(activity, R.string.meter_no_bill,
                android.widget.Toast.LENGTH_LONG).show();
    }

    private static View navTab(Context ctx, int iconRes, int labelRes,
            boolean active, View.OnClickListener onClick) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // v3.6.5: top dash indicator removed (user request) — active state
        // shows ONLY via color change (deep green icon + label).

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        // v3.6.98: active icon = amber + slightly larger (bold effect, user:
        // "thoda sa bold taake active pata chale"); active LABEL stays
        // green, idle icons keep their own stroke color.
        int is = Ui.dp(ctx, active ? 25 : 22);
        icon.setLayoutParams(new LinearLayout.LayoutParams(is, is));
        if (active) {
            icon.setColorFilter(Ds.amber(), PorterDuff.Mode.SRC_IN);
        }
        item.addView(icon);

        TextView label = new TextView(ctx);
        label.setText(ctx.getString(labelRes));
        label.setTextSize(10.5f);
        label.setTextColor(active ? Ds.deepGreen() : Ds.navIdle());
        Ds.heading(ctx, label);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(ctx, 4);
        label.setLayoutParams(llp);
        item.addView(label);

        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(onClick);
        return item;
    }
}
