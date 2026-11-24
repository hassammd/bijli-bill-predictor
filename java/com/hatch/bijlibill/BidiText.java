package com.hatch.bijlibill;

/**
 * v3.7.57: bidi-safe composition for Urdu (RTL) text that embeds
 * Latin/digit runs — dates like "26 NOV 26", day counts, amounts,
 * masked reference numbers.
 *
 * Problem: the Unicode bidi algorithm reorders a Latin run inside
 * RTL text by its surroundings, so "آخری تاریخ 26 NOV 26 • 2 دن
 * باقی ہیں" does not read as one sentence — the date and the day
 * count jump to the wrong places. The fix is dynamic (no hardcoded
 * sentence): every maximal Latin/digit run is wrapped in Unicode
 * isolates (U+2066 LRI … U+2069 PDI), which pin the run's internal
 * order and its position in the sentence for ANY values.
 *
 * Pure Java — no android imports — so the headless JVM tests
 * (TestBillFetch) can assert the exact composed strings.
 */
public final class BidiText {

    /** LEFT-TO-RIGHT ISOLATE — opens an isolated LTR run. */
    public static final char LRI = '\u2066';
    /** POP DIRECTIONAL ISOLATE — closes the nearest isolate. */
    public static final char PDI = '\u2069';

    private BidiText() {}

    /**
     * Wraps one Latin/digit run in LRI … PDI. Null-safe (null -> "").
     * In an LTR (English) paragraph the isolates are invisible, so
     * the same call sites are safe for both locales.
     */
    public static String isolate(String s) {
        if (s == null) return "";
        return String.valueOf(LRI) + s + PDI;
    }

    /** True for a character that belongs inside a Latin/digit run. */
    private static boolean isRunChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '*';
    }

    /**
     * True for a separator that may sit INSIDE a run (space, dot,
     * comma, slash, colon, dash) — only when another run character
     * follows, so "26 NOV 26", "4,637" and "1,234.50" stay one run
     * while a trailing separator does not get swallowed.
     */
    private static boolean isRunSeparator(char c, char next) {
        if (!isRunChar(next)) return false;
        return c == ' ' || c == '.' || c == ',' || c == '/'
                || c == ':' || c == '-';
    }

    /** True for an isolate / direction-control character. */
    private static boolean isIsolateControl(char c) {
        return c == '\u2066' || c == '\u2067' || c == '\u2068'
                || c == '\u2069' || c == '\u200E' || c == '\u200F';
    }

    /**
     * Wraps every maximal Latin/digit run in the text in LRI … PDI.
     * RTL (Urdu) words, punctuation and emoji pass through untouched.
     * Idempotent: runs already inside isolates are left alone, so
     * applying it twice changes nothing.
     */
    public static String isolateLatinRuns(String text) {
        if (text == null) return "";
        int n = text.length();
        StringBuilder out = new StringBuilder(n + 16);
        int depth = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isIsolateControl(c)) {
                if (c == '\u2066' || c == '\u2067' || c == '\u2068') {
                    depth++;
                } else if (c == '\u2069' && depth > 0) {
                    depth--;
                }
                out.append(c);
                i++;
                continue;
            }
            if (depth > 0) {
                out.append(c);
                i++;
                continue;
            }
            if (isRunChar(c)) {
                int start = i;
                int j = i;
                while (j < n) {
                    char cj = text.charAt(j);
                    if (isRunChar(cj)) {
                        j++;
                        continue;
                    }
                    if (j + 1 < n && isRunSeparator(cj, text.charAt(j + 1))) {
                        j++;
                        continue;
                    }
                    break;
                }
                out.append(LRI);
                out.append(text, start, j);
                out.append(PDI);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
