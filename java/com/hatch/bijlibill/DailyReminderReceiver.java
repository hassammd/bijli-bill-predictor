package com.hatch.bijlibill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * v3.1.1 — daily bill-payment reminder. Fired every morning ~09:00 by
 * the inexact repeating alarm (DailyReminderAlarm). Re-evaluates the
 * rule on every fire: notifies only while the bill counts as unpaid
 * and the due date is today/future (or at most 7 days past — then it
 * cancels the alarm itself). Tapping opens the bill screen.
 * Framework APIs only; a denied permission must never crash it.
 */
public class DailyReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "daily_bill_reminders";
    private static final int NOTIF_ID = 1002;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !DailyReminder.ACTION_DAILY.equals(intent.getAction())) {
            return;
        }
        // Localized strings follow the app's saved language.
        Context ctx = LocaleHelper.apply(context);
        SharedPreferences sp = ctx.getSharedPreferences(
                DailyReminder.PREFS, Context.MODE_PRIVATE);
        // v3.3.0: always-on — no toggle check. The alarm only exists
        // while the bill + permission + window allow it; the receiver
        // just re-validates the window on every fire.
        long dueMs = sp.getLong(DailyReminder.KEY_DUE_MS, -1);
        long now = System.currentTimeMillis();
        // Ground truth: the bill carries no paid-status signal, so a
        // fetched bill counts as unpaid (see DailyReminder).
        if (!DailyReminder.shouldNotify(false, dueMs, now)) {
            // Window closed (e.g. 7+ days past due): stop the alarm.
            DailyReminderAlarm.cancel(ctx);
            return;
        }
        int days = DailyReminder.daysUntilDue(dueMs, now);
        DailyReminder.Variant v = DailyReminder.variant(days);
        String payable = sp.getString(DailyReminder.KEY_PAYABLE, "");
        if (payable == null) payable = "";

        String text;
        if (v == DailyReminder.Variant.FUTURE) {
            text = ctx.getString(R.string.daily_rem_future, days, payable);
        } else if (v == DailyReminder.Variant.TODAY) {
            text = ctx.getString(R.string.daily_rem_today, payable);
        } else {
            text = ctx.getString(R.string.daily_rem_overdue, payable);
        }

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.daily_rem_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(context, BillActivity.class);
        open.putExtra(BillActivity.EXTRA_REF,
                sp.getString(DailyReminder.KEY_REF, ""));
        String mode = sp.getString(DailyReminder.KEY_MODE, "ref");
        open.putExtra(BillActivity.EXTRA_MODE,
                "cid".equals(mode) ? "cid" : "ref");
        open.putExtra(BillActivity.EXTRA_DISCO,
                Disco.fromCode(
                        sp.getString(DailyReminder.KEY_DISCO, null)).code);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle(ctx.getString(R.string.daily_rem_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
            // A denied/missing permission must never crash the receiver.
        }
    }
}
