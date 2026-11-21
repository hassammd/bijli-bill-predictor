package com.hatch.bijlibill;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v2.0 About: version, how-it-works 1-2-3, data source note, update check
 * placeholder, premium link.
 * v3.7.24: NON-scrollable one-screen layout (user request) — upper content
 * condensed to short bullet points, disclaimer text unchanged (policy),
 * compact card spacing, actions pinned above the banner so CHECK FOR
 * UPDATES + Premium are always visible without scrolling.
 */
public class AboutActivity extends BaseActivity {

    /** Card with tighter vertical padding (12dp) for the one-screen fit. */
    private LinearLayout compactCard() {
        LinearLayout c = Ui.card(this);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                Ui.dp(this, 16), Ui.dp(this, 12));
        return c;
    }

    /** Card wrapped with an 8dp top gap (Ui.padded uses 12dp). */
    private LinearLayout compactWrap(View card) {
        LinearLayout wrap = Ui.col(this);
        wrap.setPadding(Ui.dp(this, 16), Ui.dp(this, 8),
                Ui.dp(this, 16), 0);
        wrap.addView(card);
        return wrap;
    }

    /** Privacy policy page (same URL as the Play listing). */
    private static final String PRIVACY_URL =
            "https://bijli-bill-privacy.netlify.app/privacy-policy";

    /** Opens this app's Play Store listing (market:// first, web fallback). */
    private void openPlayStore() {
        String pkg = getPackageName();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=" + pkg));
            i.setPackage("com.android.vending");
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                            "https://play.google.com/store/apps/details?id="
                                    + pkg)));
        }
    }

    /** One Settings row: blue icon circle, title + sub, chevron. */
    private View settingRow(int iconRes, int titleRes, int subRes,
            View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 10),
                Ui.dp(this, 4), Ui.dp(this, 10));
        row.setOnClickListener(onClick);

        android.widget.FrameLayout circle =
                new android.widget.FrameLayout(this);
        int cs = Ui.dp(this, 40);
        circle.setLayoutParams(
                new LinearLayout.LayoutParams(cs, cs));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(Ds.greenBg());
        circle.setBackground(bg);
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageResource(iconRes);
        int is = Ui.dp(this, 22);
        android.widget.FrameLayout.LayoutParams ilp =
                new android.widget.FrameLayout.LayoutParams(
                        is, is, Gravity.CENTER);
        ic.setLayoutParams(ilp);
        circle.addView(ic);
        row.addView(circle);

        LinearLayout col = Ui.col(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(this, 12);
        clp.rightMargin = Ui.dp(this, 8);
        col.setLayoutParams(clp);
        col.addView(Ui.tvRes(this, titleRes, 15, true, Ui.dark()));
        col.addView(Ui.tvRes(this, subRes, 12, false, Ui.grey()));
        row.addView(col);

        android.widget.ImageView ch = new android.widget.ImageView(this);
        ch.setImageResource(R.drawable.ic_chev_right);
        int chs = Ui.dp(this, 18);
        ch.setLayoutParams(new LinearLayout.LayoutParams(chs, chs));
        row.addView(ch);
        return row;
    }

    /** 1dp divider between Settings rows. */
    private View settingDivider() {
        View d = new View(this);
        d.setBackgroundColor(Ds.line());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        dlp.leftMargin = Ui.dp(this, 56);
        d.setLayoutParams(dlp);
        return d;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v3.7.24: fixed (non-scroll) content column, weight 1.
        // v3.7.38: content is now a ScrollView (the Settings card
        // added rows — one-screen fit no longer holds on small
        // phones). Actions stay pinned below, as before.
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.pageBg());
        root.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.header(this, R.string.about_title, R.string.about_subtitle,
                new Ui.LangToggle() {
                    @Override public void onToggle() {
                        LocaleHelper.set(AboutActivity.this,
                                LocaleHelper.isUrdu(AboutActivity.this)
                                        ? LocaleHelper.LANG_EN : LocaleHelper.LANG_UR);
                        recreate();
                    }
                }));

        // Version pill.
        LinearLayout verWrap = Ui.col(this);
        verWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        verWrap.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        verWrap.addView(Ui.ratePill(this,
                getString(R.string.about_version) + " · v" + versionName()));
        root.addView(verWrap);

        // v3.7.44: top rows in this order — Rate Us, Share App,
        // Privacy Policy — ALL above "How it works" (user order
        // 2026-11-22, voice call). Same links and actions as
        // before, only the position changed; the old lower
        // Settings card that held Rate Us is gone (no duplicate).
        LinearLayout top = compactCard();
        top.addView(settingRow(R.drawable.ic_star,
                R.string.quick_rate_us, R.string.quick_rate_us_sub,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openPlayStore();
                    }
                }));
        top.addView(settingDivider());
        top.addView(settingRow(R.drawable.ic_share,
                R.string.settings_share, R.string.settings_share_sub,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent s = new Intent(Intent.ACTION_SEND);
                        s.setType("text/plain");
                        s.putExtra(Intent.EXTRA_TEXT,
                                getString(R.string.settings_share_text)
                                        + " https://play.google.com/store/apps/details?id="
                                        + getPackageName());
                        startActivity(Intent.createChooser(s,
                                getString(R.string.settings_share)));
                    }
                }));
        top.addView(settingDivider());
        top.addView(settingRow(R.drawable.ic_privacy,
                R.string.settings_privacy, R.string.settings_privacy_sub,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(PRIVACY_URL)));
                    }
                }));
        root.addView(compactWrap(top));

        // How it works — short bullet points (v3.7.24).
        LinearLayout how = compactCard();
        how.addView(Ui.cardTitle(this, R.string.about_how));
        how.addView(Ui.spacer(this, 2));
        int[] steps = {R.string.about_step1, R.string.about_step2,
                R.string.about_step3};
        for (int s : steps) {
            TextView t = Ui.tvRes(this, s, 13, false, Ui.dark());
            t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            how.addView(t);
        }
        root.addView(compactWrap(how));

        // Data source — short bullet points (v3.7.24).
        LinearLayout src = compactCard();
        src.addView(Ui.cardTitle(this, R.string.about_source_title));
        src.addView(Ui.spacer(this, 2));
        int[] points = {R.string.about_source1, R.string.about_source2};
        for (int s : points) {
            TextView t = Ui.tvRes(this, s, 13, false, Ui.grey());
            t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            src.addView(t);
        }
        root.addView(compactWrap(src));

        // Disclaimer card (Play policy: government info attribution).
        // v3.7.24: text UNCHANGED (policy) — only the size drops 13→12sp
        // so the whole page fits one screen.
        LinearLayout disc = compactCard();
        disc.addView(Ui.cardTitle(this, R.string.about_disclaimer_title));
        disc.addView(Ui.spacer(this, 2));
        TextView discTxt = Ui.tvRes(this, R.string.about_disclaimer_text,
                12, false, Ui.grey());
        disc.addView(discTxt);
        root.addView(compactWrap(disc));

        // v3.7.44: the lower Settings card is gone — Rate Us now
        // lives in the top card (see above); no duplicate row.

        // Actions — pinned below the content column (v3.7.24) so they are
        // visible without scrolling on every screen size.
        LinearLayout actions = Ui.col(this);
        actions.setBackgroundColor(Ui.pageBg());
        actions.setPadding(Ui.dp(this, 16), Ui.dp(this, 10),
                Ui.dp(this, 16), Ui.dp(this, 4));
        // v3.7.1: primary green button (same as app buttons, user QA) —
        // was outlineButton.
        // v3.7.2: Ds.greenBtn — the CURRENT button style (nd_btn_solid
        // #1B62B0 light blue-green, same as VIEW BILL). Ui.primaryButton
        // was the old dark green.
        // v3.7.5: FUNCTIONAL (user request) — tap opens this app's Play
        // Store page so the user can update from there. Play Store app
        // first (market://), web listing as fallback.
        Button upd = Ds.greenBtn(this, getString(R.string.about_update_btn),
                0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String pkg = getPackageName();
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                    "market://details?id=" + pkg));
                    i.setPackage("com.android.vending");
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                    "https://play.google.com/store/apps/details?id="
                                            + pkg)));
                }
            }
        });
        actions.addView(upd);
        actions.addView(Ui.spacer(this, 8));
        Button prem = Ui.ghostButton(this, R.string.premium_btn);
        prem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(AboutActivity.this,
                        PremiumActivity.class));
            }
        });
        actions.addView(prem);
        // v3.6.11: back button removed (user request) — bottom nav handles it.

        // v3.6.4: bottom nav on every tab (tester feedback #3).
        // v3.7.24: page = content (weight 1) + pinned actions + banner + nav.
        android.widget.LinearLayout page = Ui.col(this);
        page.setBackgroundColor(Ui.pageBg());
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(root);
        page.addView(scroll);
        page.addView(actions);
        // v3.6.16: test banner ad (user request — see ad on all pages).
        android.widget.LinearLayout bannerBox =
                new android.widget.LinearLayout(this);
        bannerBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        page.addView(bannerBox);
        AdsManager.loadBannerInto(this, bannerBox);
        page.addView(Ds.bottomNav(this, Ds.TAB_SETTINGS));
        setContentView(page);
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "2.0";
        }
    }
}
