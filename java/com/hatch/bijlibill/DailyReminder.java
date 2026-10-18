package com.hatch.bijlibill;

import java.util.Calendar;

/**
 * v3.1.1 — DAILY bill-payment reminder logic. Pure Java, no Android
 * dependencies; headless-unit-testable.
 *
 * Rule: after a bill is fetched, a daily ~09:00 local notification
 * fires while the bill counts as UNPAID and its due date is today,
 * in the future, or at most 7 days past (then the alarm stops
 * itself). Days-remaining = dueDate - today (whole days).
 *
 * HONESTY NOTE (ground truth, verified 2026-09-20): the fetched bill
 * document carries NO paid/unpaid status field, and the portal
 * Status was absent from everything our fetcher receives — so the
 * Android wiring treats every fetched bill as unpaid (paid=false).
 * The `paid` parameter exists so the rule stays testable and so a
 * real portal Status can be plugged in later without changing the
 * rule. Nothing here invents a paid state.
 *
 * The Android wiring (AlarmManager.setInexactRepeating,
 * BroadcastReceivers, POST_NOTIFICATIONS) lives in
 * DailyReminderAlarm / DailyReminderReceiver / BootReceiver.
 */
public final class DailyReminder {

    private DailyReminder() {}

    // ---- SharedPreferences ----
    public static final String PREFS = "daily_bill_reminders";
    public static final String KEY_REF = "daily_ref";
    public static final String KEY_MODE = "daily_mode";
    public static final String KEY_DISCO = "daily_disco";
    public static final String KEY_DUE_TEXT = "daily_due_text";
    public static final String KEY_DUE_MS = "daily_due_ms"; // local midnight
    public static final String KEY_PAYABLE = "daily_payable"; // as printed

    /** AlarmManager identity. */
    public static final int ALARM_REQUEST_CODE = 2001;
    public static final String ACTION_DAILY =
            "com.hatch.bijlibill.DAILY_BILL_REMINDER";

    /** Stop notifying this many days after the due date. */
    public static final int OVERDUE_STOP_DAYS = 7;

    /** Which copy variant the notification should use. */
    public enum Variant { FUTURE, TODAY, OVERDUE }

    /**
     * v3.1.2 — WHY a daily reminder is or isn't armed. Pure decision
     * inputs, no Android calls, so the scheduling conditions are
     * headless-unit-testable and the UI can show the exact reason.
     *
     * v3.3.0: the reminder is ALWAYS ON — there is no user toggle
     * anymore, so the OFF_BY_USER state is gone. Armed iff a bill is
     * stored, the notification permission is granted, and the due
     * date window is still open.
     */
    public enum ArmState {
        /** Alarm should be (or stay) armed. */
        ARMED,
        /** POST_NOTIFICATIONS not granted (Android 13+). */
        NO_PERMISSION,
        /** No bill state stored (no due date / parse failed). */
        NO_BILL,
        /** Due date more than OVERDUE_STOP_DAYS in the past. */
        WINDOW_CLOSED
    }

    /**
     * Decides the arm state from pure inputs.
     *
     * @param permGranted POST_NOTIFICATIONS granted (always true
     *                    below Android 13)
     * @param dueDateMs   persisted due-date millis, or -1 when the
     *                    bill had no (parseable) due date
     * @param todayMs     now
     */
    public static ArmState armState(boolean permGranted,
            long dueDateMs, long todayMs) {
        if (!permGranted) return ArmState.NO_PERMISSION;
        if (dueDateMs < 0) return ArmState.NO_BILL;
        // Ground truth: the bill carries no paid-status signal, so a
        // fetched bill counts as unpaid (paid=false).
        if (!shouldNotify(false, dueDateMs, todayMs)) {
            return ArmState.WINDOW_CLOSED;
        }
        return ArmState.ARMED;
    }

    /** Local midnight of the given instant. */
    public static long midnight(long instantMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(instantMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * Whole days from today's local midnight to the due date's local
     * midnight. Positive = days left, 0 = due today, negative =
     * overdue.
     */
    public static int daysUntilDue(long dueDateMs, long todayMs) {
        long diff = midnight(dueDateMs) - midnight(todayMs);
        return (int) Math.round(diff / 86400000.0);
    }

    /**
     * True when a notification should fire: not paid, due date known,
     * and not more than OVERDUE_STOP_DAYS past due.
     */
    public static boolean shouldNotify(boolean paid, long dueDateMs,
            long todayMs) {
        if (paid || dueDateMs < 0) return false;
        return daysUntilDue(dueDateMs, todayMs) >= -OVERDUE_STOP_DAYS;
    }

    /** Copy variant for a days-until-due value. */
    public static Variant variant(int daysUntilDue) {
        if (daysUntilDue > 0) return Variant.FUTURE;
        if (daysUntilDue == 0) return Variant.TODAY;
        return Variant.OVERDUE;
    }

    /**
     * Next 09:00 local: today when `now` is before 09:00, otherwise
     * tomorrow at 09:00.
     */
    /**
     * v3.6.29: changed from 9 AM to 8 PM — user feedback: most people
     * are free at home in the evening.
     */
    public static long nextEightPm(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, 20);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMs) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    /** @deprecated use {@link #nextEightPm(long)} */
    @Deprecated
    public static long nextNineAm(long nowMs) {
        return nextEightPm(nowMs);
    }
}
