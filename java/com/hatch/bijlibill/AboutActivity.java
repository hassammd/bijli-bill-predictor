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
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = Ui.page(this);
        LinearLayout root = Ui.pageRoot(scroll);

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
        verWrap.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);
        verWrap.addView(Ui.ratePill(this,
                getString(R.string.about_version) + " · v" + versionName()));
        root.addView(verWrap);

        // How it works.
        LinearLayout how = Ui.card(this);
        how.addView(Ui.cardTitle(this, R.string.about_how));
        how.addView(Ui.spacer(this, 4));
        int[] steps = {R.string.about_step1, R.string.about_step2,
                R.string.about_step3};
        for (int s : steps) {
            TextView t = Ui.tvRes(this, s, 14, false, Ui.dark());
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            how.addView(t);
        }
        root.addView(Ui.padded(this, how));

        // Data source.
        LinearLayout src = Ui.card(this);
        src.addView(Ui.cardTitle(this, R.string.about_source_title));
        src.addView(Ui.spacer(this, 4));
        src.addView(Ui.tvRes(this, R.string.about_source_text, 13, false,
                Ui.grey()));
        root.addView(Ui.padded(this, src));

        // Disclaimer card (Play policy: government info attribution).
        LinearLayout disc = Ui.card(this);
        disc.addView(Ui.cardTitle(this, R.string.about_disclaimer_title));
        disc.addView(Ui.spacer(this, 4));
        TextView discTxt = Ui.tvRes(this, R.string.about_disclaimer_text,
                13, false, Ui.grey());
        disc.addView(discTxt);
        root.addView(Ui.padded(this, disc));

        // Actions.
        LinearLayout actions = Ui.col(this);
        actions.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), 0);
        Button upd = Ui.outlineButton(this, R.string.about_update_btn);
        upd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(AboutActivity.this)
                        .setMessage(R.string.about_update_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show();
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
        actions.addView(Ui.spacer(this, 4));
        // v3.6.11: back button removed (user request) — bottom nav handles it.
        root.addView(actions);
        root.addView(Ui.spacer(this, 24));

        // v3.6.4: bottom nav on every tab (tester feedback #3). The page is a
        // ScrollView, so wrap: scroll (weight 1) + fixed bottom nav.
        android.widget.LinearLayout page = Ui.col(this);
        scroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(scroll);
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
