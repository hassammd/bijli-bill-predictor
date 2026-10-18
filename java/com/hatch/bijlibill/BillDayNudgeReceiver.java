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
 * v3.4.0 — bill-day nudge. Fired once per bill cycle ~2 days before
 * the next bill is expected: "Naya bill aya hoga — fetch karo aur
 * apna accuracy score dekho". Tapping opens BillActivity for the
 * same meter (it auto-fetches on open), which feeds the v3.3.0
 * accuracy tracker and closes the monthly open loop. A denied
 * permission must never crash it (same defensive pattern as the
 * other receivers).
 */
public class BillDayNudgeReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "bill_day_nudge";
    private static final int NOTIF_ID = 1010;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !BillDayNudgeAlarm.ACTION_NUDGE.equals(
                        intent.getAction())) {
            return;
        }
        Context ctx = LocaleHelper.apply(context);
        // One-shot: consume the persisted state first.
        String meterKey = BillDayNudgeAlarm.meterKey(ctx);
        BillDayNudgeAlarm.clearState(ctx);
        if (meterKey == null || meterKey.isEmpty()
                || !BillDayNudgeAlarm.hasPermission(ctx)) {
            return;
        }

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.nudge_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(context, BillActivity.class);
        String[] parts = meterKey.split(":", 3);
        if (parts.length == 3) {
            open.putExtra(BillActivity.EXTRA_DISCO, parts[0]);
            open.putExtra(BillActivity.EXTRA_MODE, parts[1]);
            open.putExtra(BillActivity.EXTRA_REF, parts[2]);
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
        b.setContentTitle(ctx.getString(R.string.nudge_notif_title))
                .setContentText(ctx.getString(R.string.nudge_notif_text))
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
