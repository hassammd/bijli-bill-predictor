package com.hatch.bijlibill;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * App language (English / اردو), persisted in SharedPreferences.
 * v2.5.0: default is ALWAYS English (user request) — the saved choice
 * is still honored, and the EN/اردو toggle stays on every screen.
 */
public final class LocaleHelper {

    public static final String LANG_EN = "en";
    public static final String LANG_UR = "ur";

    private static final String PREFS = "bijli_bill_prefs";
    private static final String KEY_LANG = "app_lang";

    private LocaleHelper() {}

    /** Pure policy: the out-of-box language. Headless-testable via
     *  {@link LangPolicy#defaultLang()}. */
    static String defaultLang(String deviceLang) {
        return LangPolicy.defaultLang();
    }

    public static String get(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY_LANG, null);
        if (s != null) return s;
        return defaultLang(Locale.getDefault().getLanguage());
    }

    public static void set(Context ctx, String lang) {
        // v3.6.4: commit() (synchronous) instead of apply() — guarantees the
        // new language is persisted BEFORE activity.recreate() re-reads it in
        // attachBaseContext. Fixes "Urdu switch does nothing" (tester feedback).
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANG, lang).commit();
    }

    public static boolean isUrdu(Context ctx) {
        return LANG_UR.equals(get(ctx));
    }

    /** Returns a context with the app locale applied. */
    public static Context apply(Context ctx) {
        Locale loc = new Locale(get(ctx));
        Locale.setDefault(loc);
        Configuration cfg = new Configuration(ctx.getResources().getConfiguration());
        cfg.setLocale(loc);
        return ctx.createConfigurationContext(cfg);
    }
}
