package com.hatch.bijlibill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * v2.3.0 due-date reminder receiver. Fired by the AlarmManager alarm the
 * app schedules (2 days before the bill's due date, 09:00) after a
 * successful bill fetch. Shows a local notification that deep-links
 * back to the bill screen. No new AARs — framework Notification APIs
 * only (channels on API 26+, plain builder below).
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_REMIND =
            "com.hatch.bijlibill.BILL_REMINDER";
    public static final String EXTRA_REF = "ref";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_DUE = "due";
    public static final String EXTRA_DISCO = "disco"; // v2.5.0

    private static final String CHANNEL_ID = "bill_reminders";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REMIND.equals(intent.getAction())) {
            return;
        }
        // Localized strings follow the app's saved language.
        Context ctx = LocaleHelper.apply(context);
        String due = intent.getStringExtra(EXTRA_DUE);
        if (due == null) due = "";
        String ref = intent.getStringExtra(EXTRA_REF);
        if (ref == null) ref = "";
        String mode = intent.getStringExtra(EXTRA_MODE);

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(context, BillActivity.class);
        open.putExtra(BillActivity.EXTRA_REF, ref);
        open.putExtra(BillActivity.EXTRA_MODE,
                "cid".equals(mode) ? "cid" : "ref");
        // v2.5.0: reopen the right company's bill.
        String dcode = intent.getStringExtra(EXTRA_DISCO);
        open.putExtra(BillActivity.EXTRA_DISCO,
                Disco.fromCode(dcode).code);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle(ctx.getString(R.string.reminder_notif_title))
                .setContentText(
                        ctx.getString(R.string.reminder_notif_text, due))
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
