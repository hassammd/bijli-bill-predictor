package com.hatch.bijlibill;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

/**
 * Bundled Noto Nastaliq Urdu font (res/font + assets copy for API 24/25,
 * where Resources.getFont() is unavailable). Applied to every TextView whose
 * text contains Urdu/Arabic-script characters. Android TextView handles bidi
 * natively, so logical-order Urdu renders correctly.
 */
public final class Fonts {

    private static Typeface urdu;
    private static final java.util.Map<String, Typeface> latinCache =
            new java.util.HashMap<String, Typeface>();

    private Fonts() {}

    /**
     * v2.6.0 design system: Archivo for headings
     * (700 Bold / 800 ExtraBold / 900 Black available).
     */
    public static Typeface archivo(Context ctx, int weight) {
        String file;
        if (weight >= 900) file = "fonts/Archivo-Black.ttf";
        else if (weight >= 800) file = "fonts/Archivo-ExtraBold.ttf";
        else file = "fonts/Archivo-Bold.ttf";
        return latin(ctx, file);
    }

    /**
     * v2.6.0 design system: Plus Jakarta Sans for body
     * (400/500/600/700/800 available).
     */
    public static Typeface jakarta(Context ctx, int weight) {
        String file;
        if (weight >= 800) file = "fonts/PlusJakartaSans-ExtraBold.ttf";
        else if (weight >= 700) file = "fonts/PlusJakartaSans-Bold.ttf";
        else if (weight >= 600) file = "fonts/PlusJakartaSans-SemiBold.ttf";
        else if (weight >= 500) file = "fonts/PlusJakartaSans-Medium.ttf";
        else file = "fonts/PlusJakartaSans-Regular.ttf";
        return latin(ctx, file);
    }

    private static Typeface latin(Context ctx, String assetPath) {
        Typeface t = latinCache.get(assetPath);
        if (t == null) {
            try {
                t = Typeface.createFromAsset(ctx.getAssets(), assetPath);
            } catch (Exception e) {
                t = Typeface.DEFAULT;
            }
            latinCache.put(assetPath, t);
        }
        return t;
    }

    public static Typeface urdu(Context ctx) {
        if (urdu == null) {
            try {
                urdu = Typeface.createFromAsset(ctx.getAssets(),
                        "fonts/NotoNastaliqUrdu.ttf");
            } catch (Exception e) {
                urdu = Typeface.DEFAULT;
            }
        }
        return urdu;
    }

    /** True if the text contains any Urdu/Arabic-script character. */
    public static boolean isUrdu(CharSequence s) {
        if (s == null) return false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF)
                    || (c >= 0xFB50 && c <= 0xFDFF)
                    || (c >= 0xFE70 && c <= 0xFEFF)) {
                return true;
            }
        }
        return false;
    }

    /** Applies the Nastaliq typeface to the view if its text is Urdu. */
    public static void applyIfUrdu(Context ctx, TextView v) {
        if (isUrdu(v.getText())) {
            v.setTypeface(urdu(ctx));
        }
    }
}
