package com.hatch.bijlibill;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;

import java.util.Calendar;

/**
 * v3.4.0 — weekly usage-report alarm (v3.6.0: every Sunday ~10:00).
 * Framework AlarmManager only (setInexactRepeating, RTC_WAKEUP,
 * 7-day interval); no WorkManager/AndroidX. Always-on: the
 * receiver re-evaluates everything on every fire and no-ops only
 * when there is no guard data AND no cached bill — never spam.
 * There is deliberately NO off switch (v3.3.0+ philosophy).
 */
public final class WeeklyReportAlarm {

    private WeeklyReportAlarm() {}

    /** Broadcast action for the weekly report. */
    public static final String ACTION_WEEKLY =
            "com.hatch.bijlibill.ACTION_WEEKLY_REPORT";
    /** Alarm request code (distinct from the other alarms). */
    public static final int ALARM_REQUEST_CODE = 1011;

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ACTION_WEEKLY);
        // Explicit component via class name (no compile-time
        // dependency on the receiver — keeps the headless test
        // compile free of the activity stack).
        i.setClassName(ctx,
                "com.hatch.bijlibill.WeeklyReportReceiver");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST_CODE, i,
                flags);
    }

    /** Next Sunday 10:00 local time (public for headless tests). */
    public static long nextSundayTenAm(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, 10);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int add = (Calendar.SUNDAY - c.get(Calendar.DAY_OF_WEEK)
                + 7) % 7;
        if (add == 0 && c.getTimeInMillis() <= nowMs) add = 7;
        c.add(Calendar.DAY_OF_YEAR, add);
        return c.getTimeInMillis();
    }

    /**
     * Arms the weekly report. Always-on (v3.3.0+ philosophy): the
     * receiver itself decides whether there is anything worth
     * reporting, so this is safe to call on every boot and app
     * start without spamming users who never opened the guard.
     */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am == null) return;
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    nextSundayTenAm(System.currentTimeMillis()),
                    7 * AlarmManager.INTERVAL_DAY, alarmIntent(ctx));
        } catch (Exception ignored) {
            // A failed schedule must never break the caller.
        }
    }

    /** POST_NOTIFICATIONS granted (always true below Android 13). */
    public static boolean hasPermission(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 33) return true;
            return ctx.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Cancels the weekly report. */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent(ctx));
        } catch (Exception ignored) {
        }
    }
}
