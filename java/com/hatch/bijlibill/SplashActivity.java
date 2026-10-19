package com.hatch.bijlibill;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
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
 * v2.6.0 design-system splash: rich radial green gradient + dot pattern,
 * amber glow behind an amber-gradient squircle with the bolt, Urdu Nastaliq
 * headline + tagline, version foot anchored at the bottom. Gentle float
 * animation on the badge. Layout is truly centered on every screen size.
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
            w.setStatusBarColor(Color.parseColor("#04271E"));
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.splash_bg);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- centered column ----
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(center, clp);

        // ---- icon: real app icon (Play Store icon) ----
        // v3.6.34: user wants the real icon on splash.
        // v3.6.36: transparent-bg version for clean display.
        int iconDp = 120;
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.splash_icon);
        int iconPx = Ui.dp(this, iconDp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                iconPx, iconPx);
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            appIcon.setElevation(Ui.dp(this, 12));
        }
        center.addView(appIcon, alp);

        // v3.6.37: float animation REMOVED — it was clipping the icon
        // edges during up/down movement (user report 2026-10-18).

        // ---- Urdu headline (Nastaliq, 40sp) ----
        TextView title = new TextView(this);
        title.setText(getString(R.string.splash_title));
        title.setTextSize(38);
        title.setTypeface(Fonts.urdu(this));
        title.setLineSpacing(0, 2.0f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        tlp.topMargin = Ui.dp(this, 22);
        center.addView(title, tlp);

        // ---- Urdu tagline (Nastaliq, 21sp, #CFE3D7) ----
        TextView tag = new TextView(this);
        tag.setText(getString(R.string.splash_tagline));
        tag.setTextSize(20);
        tag.setTypeface(Fonts.urdu(this));
        tag.setLineSpacing(0, 2.0f);
        tag.setTextColor(Color.parseColor("#CFE3D7"));
        tag.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = Ui.dp(this, 8);
        center.addView(tag, glp);

        // ---- foot anchored at bottom: "پاکستان کے لیے • v2.6.0" ----
        TextView foot = new TextView(this);
        foot.setText(getString(R.string.for_pakistan_urdu)
                + "  •  v" + versionName());
        foot.setTextSize(12.5f);
        foot.setTypeface(Fonts.urdu(this));
        foot.setTextColor(Color.parseColor("#8FB3A1"));
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
