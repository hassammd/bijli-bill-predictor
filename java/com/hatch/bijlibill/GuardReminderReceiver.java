package com.hatch.bijlibill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/**
 * v3.2.0 — daily 200-Unit Guard check. Fired every morning ~09:00 by
 * the inexact repeating alarm (GuardReminderAlarm). Re-evaluates the
 * guard on every fire: notifies only while the last-opened meter has
 * an anchor plus reading entries, the notification permission is
 * granted, the projection is WARNING/DANGER, and no guard
 * notification went out today yet. Tapping opens the Guard screen.
 * A denied permission must never crash it (same defensive pattern
 * as DailyReminderReceiver).
 *
 * v3.3.0: always-on — no enable toggle. The check fires iff the
 * meter has anchor + entries and the permission is granted.
 */
public class GuardReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "guard_unit_reminders";
    private static final int NOTIF_ID = 1008;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !GuardReminder.ACTION_GUARD.equals(
                        intent.getAction())) {
            return;
        }
        Context ctx = LocaleHelper.apply(context);
        String meterKey = GuardStore.lastMeter(ctx);
        // v3.3.0 always-on rule: anchor + at least one reading entry
        // + notification permission. No toggle anywhere.
        if (meterKey == null || meterKey.isEmpty()
                || !GuardStore.hasAnchor(ctx, meterKey)
                || GuardStore.entries(ctx, meterKey).isEmpty()
                || !GuardReminderAlarm.hasPermission(ctx)) {
            return;
        }
        double anchor = GuardStore.anchorReading(ctx, meterKey, -1);
        String anchorDate = GuardStore.anchorDate(ctx, meterKey);
        if (!(anchor >= 0) || anchorDate == null
                || anchorDate.isEmpty()) {
            return;
        }
        List<GuardStore.Entry> entries =
                GuardStore.entries(ctx, meterKey);
        double latest = anchor;
        if (!entries.isEmpty()) {
            latest = entries.get(entries.size() - 1).reading;
        }
        String todayIso = GuardReminder.todayIso();
        int daysElapsed =
                GuardReminder.daysBetween(anchorDate, todayIso);
        GuardCalc.Projection p = GuardCalc.project(
                latest - anchor, daysElapsed,
                GuardCalc.DEFAULT_CYCLE_DAYS);
        if (!GuardReminder.shouldNotify(p.status,
                GuardStore.lastNotified(ctx, meterKey), todayIso)) {
            return;
        }
        GuardStore.setLastNotified(ctx, meterKey, todayIso);

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.guard_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        String text = p.status == GuardCalc.Status.DANGER
                ? ctx.getString(R.string.guard_notif_danger,
                        fmt1(p.projectedUnits))
                : ctx.getString(R.string.guard_notif_warning,
                        fmt1(p.projectedUnits));

        Intent open = new Intent(context, GuardActivity.class);
        String[] parts = meterKey.split(":", 3);
        if (parts.length == 3) {
            open.putExtra(GuardActivity.EXTRA_DISCO, parts[0]);
            open.putExtra(GuardActivity.EXTRA_MODE, parts[1]);
            open.putExtra(GuardActivity.EXTRA_REF, parts[2]);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle(ctx.getString(R.string.guard_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
            // A denied/missing permission must never crash the receiver.
        }
    }

    private static String fmt1(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }
}
