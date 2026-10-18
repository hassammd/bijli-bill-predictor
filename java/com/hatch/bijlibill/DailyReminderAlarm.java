package com.hatch.bijlibill;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;

/**
 * v3.1.1 — arms/cancels the DAILY bill reminder alarm. Framework
 * AlarmManager only (setInexactRepeating, RTC_WAKEUP, INTERVAL_DAY);
 * no WorkManager/AndroidX. Shared by BillActivity (after a fetch)
 * and BootReceiver (after reboot).
 *
 * v3.3.0: the reminder is ALWAYS ON — there is no user toggle
 * anymore, so setEnabled/isEnabled are gone. The alarm arms iff a
 * bill is stored, the notification permission is granted, and the
 * due-date window is still open (see DailyReminder.armState).
 */
public final class DailyReminderAlarm {

    private DailyReminderAlarm() {}

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, DailyReminderReceiver.class);
        i.setAction(DailyReminder.ACTION_DAILY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx,
                DailyReminder.ALARM_REQUEST_CODE, i, flags);
    }

    /**
     * Arms the daily ~20:00 (8 PM) alarm from the persisted bill state.
     * v3.6.29: changed from 9 AM to 8 PM (user: people are free at home
     * in the evening).
     * v3.1.2: the decision goes through
     * {@link DailyReminder#armState} — one source of truth shared
     * with the UI and the tests. Arms only when ARMED; cancels when
     * the notify window closed; leaves the alarm alone otherwise.
     *
     * v3.3.0: always-on — no toggle; the alarm simply follows the
     * bill state + permission + window.
     */
    public static void schedule(Context ctx) {
        try {
            DailyReminder.ArmState st = currentState(ctx);
            if (st == DailyReminder.ArmState.ARMED) {
                AlarmManager am = (AlarmManager) ctx.getSystemService(
                        Context.ALARM_SERVICE);
                if (am == null) return;
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                        DailyReminder.nextEightPm(
                                System.currentTimeMillis()),
                        AlarmManager.INTERVAL_DAY, alarmIntent(ctx));
            } else if (st
                    == DailyReminder.ArmState.WINDOW_CLOSED) {
                cancel(ctx);
            }
        } catch (Exception ignored) {
            // A failed schedule must never break the caller.
        }
    }

    /**
     * v3.1.2: the current arm state from persisted prefs +
     * notification permission. The bill screen uses this to show
     * the TRUE state (never a stale ON).
     *
     * v3.3.0: no toggle preference is read — the reminder is
     * always on whenever the bill + permission + window allow it.
     */
    public static DailyReminder.ArmState currentState(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    DailyReminder.PREFS, Context.MODE_PRIVATE);
            long dueMs = sp.getLong(DailyReminder.KEY_DUE_MS, -1);
            return DailyReminder.armState(hasPermission(ctx),
                    dueMs, System.currentTimeMillis());
        } catch (Exception e) {
            return DailyReminder.ArmState.NO_BILL;
        }
    }

    /** POST_NOTIFICATIONS granted (always true below Android 13). */
    static boolean hasPermission(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 33) return true;
            return ctx.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Cancels the daily alarm (window closed, bill replaced, ...). */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent(ctx));
        } catch (Exception ignored) {
        }
    }
}
