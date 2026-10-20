package com.hatch.bijlibill;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Splash — v3.6.99 redesign (user request 2026-10-20):
 *  - English-only short text ("Bijli Bill" / "Your bill, before it
 *    arrives." / "Made for Pakistan") in BOTH locales (brand splash).
 *  - Current palette: light bg #F3F8F7 (Ds.bg), teal/ink text — the old
 *    dark-green gradient (splash_bg, #04271E) is gone.
 *  - Custom flat illustration (transmission towers + poles + houses,
 *    app palette) as a top band for a professional look.
 *  - App icon (Play Store icon, transparent) stays, 96dp.
 */
public class SplashActivity extends BaseActivity {

    private static final int SPLASH_MS = 1500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v3.6.70: keep splash fullscreen — BaseActivity opts out of
        // edge-to-edge on API 35+, but the splash must stay edge-to-edge.
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        Window w = getWindow();
        if (w != null) {
            // Light splash → light status bar with dark icons.
            w.setStatusBarColor(Ds.bg());
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ds.bg());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- illustration band (top) ----
        ImageView illus = new ImageView(this);
        illus.setImageResource(R.drawable.splash_illus);
        illus.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 250));
        ilp.gravity = Gravity.TOP;
        root.addView(illus, ilp);

        // ---- centered column: icon + title + tagline ----
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        // Nudge below true center so the column clears the illustration.
        clp.topMargin = Ui.dp(this, 110);
        root.addView(center, clp);

        // ---- icon: real app icon (Play Store icon, transparent) ----
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.splash_icon);
        int iconPx = Ui.dp(this, 96);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                iconPx, iconPx);
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            appIcon.setElevation(Ui.dp(this, 12));
        }
        center.addView(appIcon, alp);

        // ---- title: "Bijli Bill" (English, heading font) ----
        TextView title = new TextView(this);
        title.setText(getString(R.string.splash_title));
        title.setTextSize(30);
        Ds.heading(this, title);
        title.setTextColor(Ds.ink());
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        tlp.topMargin = Ui.dp(this, 18);
        center.addView(title, tlp);

        // ---- tagline: short English ----
        TextView tag = new TextView(this);
        tag.setText(getString(R.string.splash_tagline));
        tag.setTextSize(15);
        tag.setTextColor(Ds.muted());
        tag.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = Ui.dp(this, 6);
        center.addView(tag, glp);

        // ---- foot anchored at bottom: "Made for Pakistan • vX.Y.Z" ----
        TextView foot = new TextView(this);
        foot.setText("Made for Pakistan  •  v" + versionName());
        foot.setTextSize(12.5f);
        foot.setTextColor(Ds.faint());
        foot.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        flp.bottomMargin = Ui.dp(this, 44);
        root.addView(foot, flp);

        setContentView(root);

        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                startActivity(new Intent(SplashActivity.this,
                        HomeActivity.class));
                finish();
            }
        }, SPLASH_MS);
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "3.1.1";
        }
    }
}
