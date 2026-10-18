package com.hatch.bijlibill;

/**
 * v2.5.0: pure language policy, headless-testable.
 * The user asked for English as the out-of-box language; the saved
 * choice (LocaleHelper) is still honored afterwards.
 */
public final class LangPolicy {

    private LangPolicy() {}

    /** Out-of-box app language — always "en" (user request, v2.5.0). */
    public static String defaultLang() {
        return "en";
    }
}
