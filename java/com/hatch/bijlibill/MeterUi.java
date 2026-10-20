package com.hatch.bijlibill;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v2.6.0 design-system "Mere Meter" card, shared by Home and the Bills
 * screen: initials avatar, holder name, masked id + company pill,
 * chevron. Tap = re-fetch, long-press = delete (with confirmation).
 */
final class MeterUi {

    private MeterUi() {}

    private static final String METERS_PREFS = "bijli_bill_meters";
    private static final String METERS_KEY = "saved_meters_v1";

    static SavedMeters.Storage store(Context ctx) {
        final SharedPreferences sp = ctx.getSharedPreferences(
                METERS_PREFS, Context.MODE_PRIVATE);
        return new SavedMeters.Storage() {
            @Override public String load() {
                return sp.getString(METERS_KEY, null);
            }
            @Override public void save(String json) {
                sp.edit().putString(METERS_KEY, json).apply();
            }
        };
    }

    /** Initials from the first two words, e.g. "Namoos Tahira" -> "NT". */
    static String initials(String holderName) {
        if (holderName == null) return "?";
        String[] parts = holderName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (sb.length() == 2) break;
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    static View card(final Context ctx, final SavedMeters.Meter m,
            View.OnClickListener onOpen, View.OnLongClickListener onDelete,
            View.OnClickListener onRemove) {
        final Disco md = Disco.fromCode(m.disco);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.ds_meter_card);
        row.setPadding(Ui.dp(ctx, 13), Ui.dp(ctx, 12),
                Ui.dp(ctx, 13), Ui.dp(ctx, 12));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            row.setElevation(Ui.dp(ctx, 2));
        }
        Ds.tealShadow(row);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(ctx, 9);
        row.setLayoutParams(rlp);

        // avatar: light circle + person icon (v3.6.90, user request).
        // v3.6.91: different light bg per card (user request).
        FrameLayout avatar = new FrameLayout(ctx);
        int as = Ui.dp(ctx, 44);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(as, as));
        int[] bgs = {
                R.drawable.ds_avatar_ddf5ef,  // mint
                R.drawable.ds_avatar_e8e4f8,  // lavender
                R.drawable.ds_avatar_fde8d8,  // peach
                R.drawable.ds_avatar_d8eafd,  // blue
                R.drawable.ds_avatar_fdd8e8,  // pink
                R.drawable.ds_avatar_fdf3d8,  // yellow
        };
        int bi = 0;
        if (m.holderName != null) {
            bi = Math.abs(m.holderName.hashCode()) % bgs.length;
        }
        avatar.setBackgroundResource(bgs[bi]);
        android.widget.ImageView piv = new android.widget.ImageView(ctx);
        piv.setImageResource(R.drawable.ic_person);
        int ps = Ui.dp(ctx, 24);
        FrameLayout.LayoutParams pilp = new FrameLayout.LayoutParams(ps, ps);
        pilp.gravity = Gravity.CENTER;
        piv.setLayoutParams(pilp);
        avatar.addView(piv);
        row.addView(avatar);

        // info column
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = Ui.dp(ctx, 13);
        ilp.rightMargin = Ui.dp(ctx, 8);
        info.setLayoutParams(ilp);

        TextView name = new TextView(ctx);
        name.setText(m.holderName);
        name.setTextSize(13);
        name.setTextColor(Ds.ink());
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ds.body(ctx, name, 600);
        info.addView(name);

        LinearLayout sub = new LinearLayout(ctx);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(ctx, 6);
        sub.setLayoutParams(slp);

        TextView masked = new TextView(ctx);
        String display = SavedMeters.maskId(m.idValue)
                + ("cid".equals(m.idType) ? " · "
                        + ctx.getString(R.string.saved_cid_suffix) : "");
        masked.setText(display);
        masked.setTextSize(11.5f);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            masked.setLetterSpacing(0.03f);
        }
        masked.setTypeface(Typeface.MONOSPACE);
        masked.setTextColor(Ds.muted());
        sub.addView(masked);

        TextView pill = new TextView(ctx);
        pill.setText(md.displayName);
        pill.setTextSize(10);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            pill.setLetterSpacing(0.06f);
        }
        pill.setTypeface(Fonts.archivo(ctx, 800));
        pill.setTextColor(Ds.deepGreen());
        pill.setBackgroundResource(R.drawable.ds_copill);
        int pp = Ui.dp(ctx, 10);
        pill.setPadding(pp, Ui.dp(ctx, 4), pp, Ui.dp(ctx, 4));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = Ui.dp(ctx, 8);
        pill.setLayoutParams(plp);
        sub.addView(pill);
        info.addView(sub);
        row.addView(info);

        ImageView chev = new ImageView(ctx);
        chev.setImageResource(R.drawable.ic_chev_right);
        chev.setColorFilter(0xFFB6C4BB,
                android.graphics.PorterDuff.Mode.SRC_IN);
        int cs = Ui.dp(ctx, 18);
        chev.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        row.addView(chev);

        // v3.7.19 (user): visible REMOVE option — trash button at the
        // right end of every meter card. Tap = small Yes/No popup
        // (confirmDelete). Long-press delete bhi maujood hai.
        if (onRemove != null) {
            FrameLayout del = new FrameLayout(ctx);
            del.setBackgroundResource(R.drawable.pop_circle_red);
            int ds = Ui.dp(ctx, 34);
            LinearLayout.LayoutParams dlp =
                    new LinearLayout.LayoutParams(ds, ds);
            dlp.leftMargin = Ui.dp(ctx, 10);
            del.setLayoutParams(dlp);
            del.setClickable(true);
            del.setFocusable(true);
            del.setOnClickListener(onRemove);
            ImageView tiv = new ImageView(ctx);
            tiv.setImageResource(R.drawable.ic_trash);
            int ts = Ui.dp(ctx, 18);
            FrameLayout.LayoutParams tlp2 =
                    new FrameLayout.LayoutParams(ts, ts);
            tlp2.gravity = Gravity.CENTER;
            tiv.setLayoutParams(tlp2);
            del.addView(tiv);
            row.addView(del);
        }

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onOpen);
        row.setOnLongClickListener(onDelete);
        return row;
    }

    static void confirmDelete(final Context ctx,
            final SavedMeters.Storage s, final SavedMeters.Meter m,
            final Runnable onDone) {
        final String display = SavedMeters.maskId(m.idValue)
                + ("cid".equals(m.idType)
                        ? " \u00b7 " + ctx.getString(R.string.saved_cid_suffix)
                        : "");
        // v3.7.19 (user): system dialog ki jagah chhota sa apna
        // Yes/No popup — app design, red Yes button.
        final android.app.Dialog dlg = new android.app.Dialog(ctx);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
        }
        dlg.setCancelable(true);
        int d = (int) (ctx.getResources().getDisplayMetrics().density + 0.5f);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ds_card_24);
        card.setPadding(22 * d, 22 * d, 22 * d, 20 * d);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout icBox = new FrameLayout(ctx);
        icBox.setBackgroundResource(R.drawable.pop_circle_red);
        icBox.setLayoutParams(new LinearLayout.LayoutParams(52 * d, 52 * d));
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(R.drawable.ic_trash);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(26 * d, 26 * d);
        ilp.gravity = Gravity.CENTER;
        ic.setLayoutParams(ilp);
        icBox.addView(ic);
        card.addView(icBox);

        TextView title = new TextView(ctx);
        title.setText(R.string.delete_title);
        title.setTextSize(17);
        title.setTextColor(Ds.ink());
        title.setGravity(Gravity.CENTER);
        Ds.heading(ctx, title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = 12 * d; tlp.bottomMargin = 6 * d;
        title.setLayoutParams(tlp);
        card.addView(title);

        TextView msg = new TextView(ctx);
        msg.setText(ctx.getString(R.string.delete_msg, m.holderName, display));
        msg.setTextSize(13.5f);
        msg.setTextColor(Ds.muted());
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(2 * d, 1.3f);
        Ds.body(ctx, msg, 400);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.bottomMargin = 18 * d;
        msg.setLayoutParams(mlp);
        card.addView(msg);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        btns.addView(popBtn(ctx, d, R.string.no, false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { dlg.dismiss(); }
                }));
        View gap = new View(ctx);
        gap.setLayoutParams(new LinearLayout.LayoutParams(10 * d, 1));
        btns.addView(gap);
        btns.addView(popBtn(ctx, d, R.string.yes, true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        SavedMeters.remove(s, m.disco, m.idValue);
                        android.widget.Toast.makeText(ctx,
                                R.string.deleted,
                                android.widget.Toast.LENGTH_SHORT).show();
                        dlg.dismiss();
                        if (onDone != null) onDone.run();
                    }
                }));
        card.addView(btns);

        dlg.setContentView(card);
        if (dlg.getWindow() != null) {
            int w = (int) (ctx.getResources().getDisplayMetrics().widthPixels
                    * 0.82);
            dlg.getWindow().setLayout(w,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    /** v3.7.19: chhota pill button for the delete popup (No = light, Yes = red). */
    private static View popBtn(Context ctx, int d, int textRes,
            boolean danger, View.OnClickListener onClick) {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(danger
                ? R.drawable.pop_btn_red : R.drawable.pop_btn_light);
        b.setMinimumHeight(44 * d);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(ctx);
        t.setText(textRes);
        t.setTextSize(14.5f);
        t.setTextColor(danger ? Color.WHITE : Ds.ink());
        Ds.heading(ctx, t);
        b.addView(t);
        return b;
    }
}
