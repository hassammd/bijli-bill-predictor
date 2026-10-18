package com.hatch.bijlibill;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * v2.3.0 due-date reminder — pure Java date math, no Android
 * dependencies. Headless-unit-testable (see TestBillFetch.runV230Tests).
 *
 * The Android wiring (AlarmManager, BroadcastReceiver,
 * POST_NOTIFICATIONS) lives in BillActivity / ReminderReceiver; this
 * class only parses "22 SEP 26" and computes "2 days before, 9:00 AM".
 */
public final class BillReminder {

    private BillReminder() {}

    // ---- SharedPreferences keys (used by BillActivity) ----
    public static final String PREFS = "bill_reminders";
    public static final String KEY_REF = "rem_ref";
    public static final String KEY_MODE = "rem_mode";
    public static final String KEY_DUE_TEXT = "rem_due_text";
    public static final String KEY_TIME = "rem_time";
    public static final String KEY_NOTIF_ASKED = "rem_notif_asked";

    private static final String[] MONTHS = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    /**
     * Parses "22 SEP 26" (also "22-SEP-26", "22/SEP/26", lowercase)
     * into epoch millis at local midnight. 2-digit year means 20xx.
     * Returns -1 on any failure.
     */
    public static long parseDueDate(String dueDate) {
        if (dueDate == null) return -1;
        String[] raw = dueDate.trim().toUpperCase().split("[^A-Z0-9]+");
        List<String> p = new ArrayList<String>();
        for (String s : raw) {
            if (s != null && !s.isEmpty()) p.add(s);
        }
        if (p.size() != 3) return -1;
        int day;
        int year;
        try {
            day = Integer.parseInt(p.get(0));
            year = Integer.parseInt(p.get(2));
        } catch (NumberFormatException e) {
            return -1;
        }
        int mon = -1;
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(p.get(1))) {
                mon = i;
                break;
            }
        }
        if (mon < 0 || day < 1 || day > 31 || year < 0 || year > 99) {
            return -1;
        }
        Calendar c = Calendar.getInstance();
        c.setLenient(false);
        try {
            c.set(2000 + year, mon, day, 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis(); // forces strict validation
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The reminder moment: 2 days before the due date, 09:00 local.
     * Returns -1 when that moment is already past (nothing to
     * schedule) or the due date is invalid.
     */
    public static long reminderTimeMs(long dueDateMs) {
        if (dueDateMs < 0) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dueDateMs);
        c.add(Calendar.DAY_OF_MONTH, -2);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long t = c.getTimeInMillis();
        return t > System.currentTimeMillis() ? t : -1;
    }

    /**
     * Short label for the on-screen line, e.g. "20 SEP" for
     * "Reminder lag gaya: %1$s, subah 9 baje".
     */
    public static String reminderLabel(long reminderMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(reminderMs);
        int d = c.get(Calendar.DAY_OF_MONTH);
        String m = MONTHS[c.get(Calendar.MONTH)];
        return (d < 10 ? "0" + d : String.valueOf(d)) + " " + m;
    }
}
