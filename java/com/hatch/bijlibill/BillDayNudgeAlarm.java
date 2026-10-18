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
 * v3.4.0 — one-shot bill-day nudge alarm. Fired once per fetched
 * bill, ~2 days before the next bill is expected, so the user
 * re-opens the app monthly and the v3.3.0 accuracy tracker gets
 * fed. Framework AlarmManager only (set, RTC_WAKEUP); no
 * WorkManager/AndroidX. Each new fetch REPLACES the previous alarm
 * (FLAG_UPDATE_CURRENT). The trigger + meter key persist in prefs
 * so BootReceiver can re-arm after a reboot.
 */
public final class BillDayNudgeAlarm {

    private BillDayNudgeAlarm() {}

    static final String PREFS = "bill_day_nudge";
    static final String KEY_METER = "meter_key";
    static final String KEY_TRIGGER = "trigger_ms";

    /** Broadcast action for the one-shot nudge. */
    public static final String ACTION_NUDGE =
            "com.hatch.bijlibill.ACTION_BILL_DAY_NUDGE";
    /** Alarm request code (distinct from the other alarms). */
    public static final int ALARM_REQUEST_CODE = 1009;

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ACTION_NUDGE);
        // Explicit component via class name (no compile-time
        // dependency on the receiver — keeps the headless test
        // compile free of the activity stack).
        i.setClassName(ctx,
                "com.hatch.bijlibill.BillDayNudgeReceiver");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST_CODE, i,
                flags);
    }

    /**
     * Schedules (replacing any previous nudge) from the freshly
     * fetched bill's reading date. Unparseable date or a trigger in
     * the past -> any previous nudge is cancelled and nothing is
     * scheduled.
     */
    public static void scheduleFromReadingDate(Context ctx,
            String meterKey, String readingDateRaw) {
        try {
            long trigger = BillDayNudge.nudgeTimeMs(readingDateRaw,
                    System.currentTimeMillis());
            if (trigger < 0) {
                cancel(ctx);
                return;
            }
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am == null) return;
            am.set(AlarmManager.RTC_WAKEUP, trigger,
                    alarmIntent(ctx));
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_METER,
                            meterKey == null ? "" : meterKey)
                    .putLong(KEY_TRIGGER, trigger)
                    .apply();
        } catch (Exception ignored) {
            // A failed schedule must never break the caller.
        }
    }

    /**
     * Boot re-arm: fires again only when the persisted trigger is
     * still in the future.
     */
    public static void scheduleIfArmed(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE);
            long trigger = sp.getLong(KEY_TRIGGER, -1);
            if (trigger > System.currentTimeMillis()) {
                AlarmManager am = (AlarmManager) ctx
                        .getSystemService(Context.ALARM_SERVICE);
                if (am != null) {
                    am.set(AlarmManager.RTC_WAKEUP, trigger,
                            alarmIntent(ctx));
                }
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    /** Persisted meter key for the armed nudge ("" when none). */
    static String meterKey(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS,
                    Context.MODE_PRIVATE).getString(KEY_METER, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Clears the persisted one-shot state (called after firing). */
    static void clearState(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_METER).remove(KEY_TRIGGER)
                    .apply();
        } catch (Exception ignored) {
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

    /** Cancels the nudge and clears its persisted state. */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(
                    Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent(ctx));
        } catch (Exception ignored) {
        }
        clearState(ctx);
    }
}
