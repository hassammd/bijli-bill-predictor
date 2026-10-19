package com.hatch.bijlibill;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** All activities extend this so the saved app locale applies before UI build. */
public class BaseActivity extends Activity {
    private String resumeLang;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.apply(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v3.6.69: status bar light teal on all pages (user request).
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(
                    android.graphics.Color.parseColor("#11998E"));
        }
        // v3.6.45: if language changed while this activity was in
        // background, recreate to apply it (global language sync).
        String current = LocaleHelper.get(this);
        if (resumeLang != null && !resumeLang.equals(current)) {
            recreate();
        }
        resumeLang = current;
    }

    /**
     * v3.5.0 (API 36): Android 15+ enforces edge-to-edge — the status and
     * navigation bars are transparent and content draws behind them. Pad the
     * root view by the system-bar insets so headers/banners are never hidden
     * under the bars. On API < 35 the legacy opaque bars are untouched.
     */
    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (Build.VERSION.SDK_INT >= 35 && view != null) {
            final int pl = view.getPaddingLeft();
            final int pr = view.getPaddingRight();
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets sb =
                            insets.getInsets(WindowInsets.Type.statusBars());
                    android.graphics.Insets nb =
                            insets.getInsets(WindowInsets.Type.navigationBars());
                    top = sb.top;
                    bottom = nb.bottom;
                }
                v.setPadding(pl, top, pr, bottom);
                return insets;
            });
        }
    }
}
