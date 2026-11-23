package com.hatch.bijlibill;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Splash — v3.7.52 redesign (user request 2026-11-23):
 *  - Professional + simple: the top illustration band is gone; clean
 *    light bg #F4F8F7 (Ds.bg), centered new icon 120dp + title +
 *    tagline, slim progress indicator near the bottom.
 *  - Animation (framework APIs only): icon fades in + scales
 *    0.85 -> 1.0 over ~700ms (DecelerateInterpolator); title and
 *    tagline fade in with a small +16dp -> 0 rise, staggered ~150ms
 *    after the icon. Total splash ~1500ms, fade transition to Home.
 *  - English-only short text ("Bijli Bill Check Pakistan" / "Your bill, before it
 *    arrives." / "Made for Pakistan") in BOTH locales (brand splash).
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

        // ---- centered column: icon + title + tagline ----
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(center, clp);

        // ---- icon: new app icon (v3.7.52, enlarged), 120dp ----
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.splash_icon);
        int iconPx = Ui.dp(this, 120);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                iconPx, iconPx);
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            appIcon.setElevation(Ui.dp(this, 12));
        }
        // v3.7.52: entrance animation starts from invisible + 0.85x.
        appIcon.setAlpha(0f);
        appIcon.setScaleX(0.85f);
        appIcon.setScaleY(0.85f);
        center.addView(appIcon, alp);

        // ---- title: "Bijli Bill Check Pakistan" (English, heading font) ----
        // 25 chars: 22sp + single line so it fits on one line (~276dp at 22sp,
        // safe on 320dp+ screens); was 30sp for the short "Bijli Bill".
        TextView title = new TextView(this);
        title.setText(getString(R.string.splash_title));
        title.setTextSize(22);
        title.setSingleLine(true);
        Ds.heading(this, title);
        title.setTextColor(Ds.ink());
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        tlp.topMargin = Ui.dp(this, 18);
        title.setAlpha(0f);
        title.setTranslationY(Ui.dp(this, 16));
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
        tag.setAlpha(0f);
        tag.setTranslationY(Ui.dp(this, 16));
        center.addView(tag, glp);

        // ---- slim indeterminate progress indicator, near bottom ----
        ProgressBar spinner = new ProgressBar(this, null,
                android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            spinner.setIndeterminateTintList(
                    ColorStateList.valueOf(Ds.ndBlueAccent()));
        }
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = Ui.dp(this, 96);
        root.addView(spinner, slp);

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

        // ---- v3.7.52 entrance animation ----
        AnimatorSet iconAnim = new AnimatorSet();
        iconAnim.playTogether(
                ObjectAnimator.ofFloat(appIcon, "alpha", 0f, 1.0f),
                ObjectAnimator.ofFloat(appIcon, "scaleX", 0.85f, 1.0f),
                ObjectAnimator.ofFloat(appIcon, "scaleY", 0.85f, 1.0f));
        iconAnim.setDuration(700);
        iconAnim.setInterpolator(new DecelerateInterpolator());

        AnimatorSet titleAnim = new AnimatorSet();
        titleAnim.playTogether(
                ObjectAnimator.ofFloat(title, "alpha", 0f, 1.0f),
                ObjectAnimator.ofFloat(title, "translationY",
                        Ui.dp(this, 16), 0f));
        titleAnim.setDuration(500);
        titleAnim.setStartDelay(150);

        AnimatorSet tagAnim = new AnimatorSet();
        tagAnim.playTogether(
                ObjectAnimator.ofFloat(tag, "alpha", 0f, 1.0f),
                ObjectAnimator.ofFloat(tag, "translationY",
                        Ui.dp(this, 16), 0f));
        tagAnim.setDuration(500);
        tagAnim.setStartDelay(300);

        AnimatorSet entrance = new AnimatorSet();
        entrance.playTogether(iconAnim, titleAnim, tagAnim);
        entrance.start();

        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                startActivity(new Intent(SplashActivity.this,
                        HomeActivity.class));
                overridePendingTransition(android.R.anim.fade_in,
                        android.R.anim.fade_out);
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
