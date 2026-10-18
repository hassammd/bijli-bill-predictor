package com.hatch.bijlibill;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * v3.2.0 — arms/cancels the DAILY 200-Unit Guard check. Same pattern
 * as DailyReminderAlarm: framework AlarmManager only
 * (setInexactRepeating, RTC_WAKEUP, INTERVAL_DAY); no WorkManager.
 * The receiver re-evaluates everything on every fire and no-ops
 * when the guard is disabled or the meter has no anchor.
 */
public final class GuardReminderAlarm {

    private GuardReminderAlarm() {}

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, GuardReminderReceiver.class);
        i.setAction(GuardReminder.ACTION_GUARD);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx,
                GuardReminder.ALARM_REQUEST_CODE, i, flags);
    }

    /** Next 09:00 local time (same slot as the bill reminder). */
    static long nextNineAm(long nowMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(java.util.Calendar.HOUR_OF_DAY, 9);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMs) {
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    /**
     * Arms the daily guard check. Called from GuardActivity whenever
     * the guard is saved/enabled; the receiver itself decides whether
     * a notification is actually due.
     */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am == null) return;
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    nextNineAm(System.currentTimeMillis()),
                    AlarmManager.INTERVAL_DAY, alarmIntent(ctx));
        } catch (Exception ignored) {
            // A failed schedule must never break the caller.
        }
    }

    /**
     * Arms the daily guard check only when a guard meter is active
     * (anchor + entries saved). Used by BootReceiver so a reboot
     * never leaves a live guard silent — and never arms an alarm
     * for users who never opened the guard.
     *
     * v3.3.0: always-on — the old per-meter "enabled" toggle is
     * gone; activity = anchor + entries.
     */
    public static void scheduleIfEnabled(Context ctx) {
        try {
            String key = GuardStore.lastMeter(ctx);
            if (key != null && !key.isEmpty()
                    && GuardStore.hasAnchor(ctx, key)
                    && !GuardStore.entries(ctx, key).isEmpty()) {
                schedule(ctx);
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    /** POST_NOTIFICATIONS granted (always true below Android 13). */
    public static boolean hasPermission(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 33) return true;
            return ctx.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager
                            .PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Cancels the daily guard check. */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am == null) return;
            am.cancel(alarmIntent(ctx));
        } catch (Exception ignored) {
            // Best effort only.
        }
    }
}
