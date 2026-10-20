package com.hatch.bijlibill;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v3.7.24: fixed (non-scroll) content column, weight 1.
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.pageBg());
        root.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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

        // Actions — pinned below the content column (v3.7.24) so they are
        // visible without scrolling on every screen size.
        LinearLayout actions = Ui.col(this);
        actions.setBackgroundColor(Ui.pageBg());
        actions.setPadding(Ui.dp(this, 16), Ui.dp(this, 10),
                Ui.dp(this, 16), Ui.dp(this, 4));
        // v3.7.1: primary green button (same as app buttons, user QA) —
        // was outlineButton.
        // v3.7.2: Ds.greenBtn — the CURRENT button style (nd_btn_solid
        // #11998E light teal-green, same as VIEW BILL). Ui.primaryButton
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
        page.addView(root);
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
