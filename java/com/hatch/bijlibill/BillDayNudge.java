package com.hatch.bijlibill;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * v3.4.0 — bill-day nudge trigger math. Pure Java, no Android
 * dependencies — headless-unit-testable.
 *
 * After every successful bill fetch we schedule a one-shot alarm a
 * little BEFORE the next bill is expected (reading date + 30 days
 * minus {@link #LEAD_DAYS}), so the nudge lands while the new bill
 * is fresh: "Naya bill aya hoga — fetch karo aur apna accuracy
 * score dekho". That closes the monthly open loop that feeds the
 * v3.3.0 accuracy tracker.
 */
public final class BillDayNudge {

    private BillDayNudge() {}

    /** Assumed billing-cycle length when the bill gives no better. */
    public static final int CYCLE_DAYS = 30;
    /** Fire this many days before the next expected reading date. */
    public static final int LEAD_DAYS = 2;
    /** Local hour the nudge fires. */
    public static final int FIRE_HOUR = 9;

    /**
     * Trigger time in ms for the one-shot nudge, or -1 when the
     * reading date is missing/unparseable or the trigger already
     * lies in the past (a stale bill must never fire immediately).
     *
     * @param readingDateRaw the bill's readingDate as printed
     * @param nowMs          current time in ms
     */
    public static long nudgeTimeMs(String readingDateRaw,
            long nowMs) {
        Date rd = parseReadingDate(readingDateRaw);
        if (rd == null) return -1;
        Calendar c = Calendar.getInstance();
        c.setTime(rd);
        c.add(Calendar.DAY_OF_YEAR, CYCLE_DAYS - LEAD_DAYS);
        c.set(Calendar.HOUR_OF_DAY, FIRE_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long t = c.getTimeInMillis();
        return t > nowMs ? t : -1;
    }

    /**
     * Parses the reading date as printed on the bill. Same format
     * family GuardActivity accepts; null on failure.
     */
    public static Date parseReadingDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] patterns = {"dd-MMM-yy", "dd-MMM-yyyy",
                "dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-dd"};
        String in = raw.trim().toUpperCase(Locale.US);
        for (String pat : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pat,
                        Locale.US);
                f.setLenient(false);
                return f.parse(in);
            } catch (Exception ignored) {
                // Try the next pattern.
            }
        }
        return null;
    }
}
