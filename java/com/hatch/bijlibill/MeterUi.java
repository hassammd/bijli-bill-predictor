package com.hatch.bijlibill;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
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
            View.OnClickListener onOpen, View.OnLongClickListener onDelete) {
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
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(ctx, 9);
        row.setLayoutParams(rlp);

        // avatar
        FrameLayout avatar = new FrameLayout(ctx);
        int as = Ui.dp(ctx, 40);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(as, as));
        boolean alt = (m.holderName != null
                && (m.holderName.hashCode() & 1) == 1);
        avatar.setBackgroundResource(
                alt ? R.drawable.ds_avatar_alt : R.drawable.ds_avatar);
        TextView ini = new TextView(ctx);
        ini.setText(initials(m.holderName));
        ini.setTextSize(13.5f);
        ini.setTypeface(Fonts.archivo(ctx, 800));
        ini.setTextColor(0xFFFFFFFF);
        ini.setGravity(Gravity.CENTER);
        ini.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        avatar.addView(ini);
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

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onOpen);
        row.setOnLongClickListener(onDelete);
        return row;
    }

    static void confirmDelete(final Context ctx,
            final SavedMeters.Storage s, final SavedMeters.Meter m,
            final Runnable onDone) {
        final Disco md = Disco.fromCode(m.disco);
        final String display = SavedMeters.maskId(m.idValue)
                + ("cid".equals(m.idType)
                        ? " · " + ctx.getString(R.string.saved_cid_suffix)
                        : "");
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.delete_title)
                .setMessage(ctx.getString(R.string.delete_msg,
                        m.holderName, display))
                .setPositiveButton(R.string.yes,
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d,
                            int which) {
                        SavedMeters.remove(s, m.disco, m.idValue);
                        android.widget.Toast.makeText(ctx,
                                R.string.deleted,
                                android.widget.Toast.LENGTH_SHORT).show();
                        if (onDone != null) onDone.run();
                    }
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }
}
