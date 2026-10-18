package com.hatch.bijlibill;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * v3.2.0 — 200-Unit Guard notification policy + date math. Pure Java,
 * no Android dependencies — headless-unit-testable. The receiver
 * (GuardReminderReceiver) only calls {@link #shouldNotify} and the
 * date helpers; all Android notification code lives there.
 */
public final class GuardReminder {

    private GuardReminder() {}

    /** Broadcast action for the daily guard check. */
    public static final String ACTION_GUARD =
            "com.hatch.bijlibill.ACTION_GUARD_CHECK";
    /** Alarm request code (distinct from the bill reminder's). */
    public static final int ALARM_REQUEST_CODE = 1007;

    /**
     * True when a notification may fire: the guard is WARNING or
     * DANGER and no guard notification went out today yet.
     */
    public static boolean shouldNotify(GuardCalc.Status status,
            String lastNotifiedIso, String todayIso) {
        if (status != GuardCalc.Status.WARNING
                && status != GuardCalc.Status.DANGER) {
            return false;
        }
        if (todayIso == null) return false;
        return !todayIso.equals(
                lastNotifiedIso == null ? "" : lastNotifiedIso);
    }

    /** Today's date as yyyy-MM-dd in the device timezone. */
    public static String todayIso() {
        return isoOf(new Date());
    }

    /** Formats a Date as yyyy-MM-dd. */
    public static String isoOf(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd",
                Locale.US);
        return f.format(d);
    }

    /**
     * Whole days from fromIso to toIso (both yyyy-MM-dd); negative
     * when toIso is earlier. Unparseable input -> 0.
     */
    public static int daysBetween(String fromIso, String toIso) {
        Date a = parseIso(fromIso);
        Date b = parseIso(toIso);
        if (a == null || b == null) return 0;
        long ms = b.getTime() - a.getTime();
        return (int) (ms / 86400000L);
    }

    /** Parses yyyy-MM-dd strictly; null on failure. */
    public static Date parseIso(String iso) {
        if (iso == null) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd",
                    Locale.US);
            f.setLenient(false);
            f.setTimeZone(TimeZone.getDefault());
            return f.parse(iso.trim());
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * v3.6.0 — the bill prints the reading date in its own format;
     * accepts a few common ones, else null (caller falls back).
     * Shared by GuardActivity and the weekly-report receiver.
     */
    public static String parseBillDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] patterns = {"dd-MMM-yy", "dd-MMM-yyyy", "dd/MM/yyyy",
                "dd-MM-yyyy", "yyyy-MM-dd"};
        for (String pat : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pat,
                        Locale.US);
                f.setLenient(false);
                Date d = f.parse(raw.trim().toUpperCase(Locale.US));
                return isoOf(d);
            } catch (Exception ignored) {
                // Try the next pattern.
            }
        }
        return null;
    }
}
