package com.hatch.bijlibill;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * v3.4.0 — Guard check-in streak (v3.6.0: WEEKLY — one Guard reading
 * saved in a calendar week counts as one check-in). Pure Java, no
 * Android dependencies — headless-unit-testable. The activity layer
 * owns persistence (GuardStore); this class is only the transition
 * math.
 *
 * Rule: checking in twice in the same week is idempotent; a
 * check-in in the week right after the last one extends the
 * streak; any longer gap (or a first/garbled last date) restarts
 * at 1. Weeks start on Monday.
 */
public final class StreakCalc {

    private StreakCalc() {}

    /** Milestone week counts that earn a celebration line. */
    public static final int MILESTONE_4W = 4;
    public static final int MILESTONE_12W = 12;

    /** Streak state after a check-in. */
    public static final class Result {
        /** yyyy-MM-dd of the latest check-in. */
        public String lastIso = "";
        public int count;
    }

    /**
     * Records a check-in for {@code todayIso} (yyyy-MM-dd) given the
     * stored {@code lastIso}/{@code count}. Never throws; garbage
     * input restarts the streak at 1.
     */
    public static Result checkin(String lastIso, int count,
            String todayIso) {
        Result r = new Result();
        Date today = parseIso(todayIso);
        if (today == null) {
            r.lastIso = lastIso == null ? "" : lastIso;
            r.count = Math.max(0, count);
            return r;
        }
        String todayWeek = weekKey(today);
        if (todayWeek.equals(weekKey(parseIso(lastIso)))) {
            // Same calendar week re-check-in: idempotent.
            r.lastIso = todayIso;
            r.count = Math.max(1, count);
            return r;
        }
        if (isNextWeek(parseIso(lastIso), today)) {
            r.lastIso = todayIso;
            r.count = Math.max(1, count) + 1;
            return r;
        }
        r.lastIso = todayIso;
        r.count = 1;
        return r;
    }

    /** True when the count just hit a celebration milestone. */
    public static boolean isMilestone(int count) {
        return count == MILESTONE_4W || count == MILESTONE_12W;
    }

    /** Strict yyyy-MM-dd parse; null on failure. */
    static Date parseIso(String iso) {
        if (iso == null) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd",
                    Locale.US);
            f.setLenient(false);
            return f.parse(iso.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Monday (yyyy-MM-dd) of the week containing {@code d}; weeks
     * start on Monday. "" on null. Calendar arithmetic (not a fixed
     * 7*86400000 ms) so DST transitions can't break it.
     */
    static String weekKey(Date d) {
        if (d == null) return "";
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int back = (dow - Calendar.MONDAY + 7) % 7;
        c.add(Calendar.DAY_OF_YEAR, -back);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(c.getTime());
    }

    /**
     * True when {@code last} falls in the calendar week immediately
     * before {@code today}'s week.
     */
    private static boolean isNextWeek(Date last, Date today) {
        if (last == null || today == null) return false;
        Calendar c = Calendar.getInstance();
        c.setTime(today);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int back = (dow - Calendar.MONDAY + 7) % 7;
        c.add(Calendar.DAY_OF_YEAR, -back - 7);
        String prevMonday = new SimpleDateFormat("yyyy-MM-dd",
                Locale.US).format(c.getTime());
        return prevMonday.equals(weekKey(last));
    }
}
