package com.hatch.bijlibill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v3.4.0 — weekly usage report (v3.6.0: every Sunday ~10:00).
 * Strictly always-on: NO off switch anywhere (v3.3.0+
 * philosophy). Reports the last-7-days units for the guard meter:
 * from the guard's anchor + reading entries when present,
 * otherwise auto-projected from the last fetched bill's pace
 * (v3.6.0 zero-input Guard). Fires NOTHING only when there is no
 * guard data AND no cached bill (never spam). Every fire carries
 * the weekly reading nudge. Tapping opens the Guard screen —
 * every retention nudge must open the app. A denied permission
 * must never crash it (same defensive pattern as the other
 * receivers).
 */
public class WeeklyReportReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "weekly_report";
    private static final int NOTIF_ID = 1012;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !WeeklyReportAlarm.ACTION_WEEKLY.equals(
                        intent.getAction())) {
            return;
        }
        Context ctx = LocaleHelper.apply(context);
        String meterKey = GuardStore.lastMeter(ctx);
        if (meterKey == null || meterKey.isEmpty()) {
            // v3.6.0: the user may never have opened the Guard —
            // fall back to the first saved meter so the Sunday
            // reminder still works with zero input.
            meterKey = firstSavedMeterKey(ctx);
        }
        if (meterKey == null || meterKey.isEmpty()
                || !WeeklyReportAlarm.hasPermission(ctx)) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        double weekUnits = -1;
        double usedCycle = -1;
        int daysElapsed = 0;

        boolean hasGuardData =
                GuardStore.hasAnchor(ctx, meterKey)
                        || !GuardStore.entries(ctx, meterKey)
                                .isEmpty();
        if (hasGuardData) {
            List<WeeklyReport.Point> pts =
                    new ArrayList<WeeklyReport.Point>();
            String aDate = GuardStore.anchorDate(ctx, meterKey);
            double anchor =
                    GuardStore.anchorReading(ctx, meterKey, -1);
            Date ad = anchor >= 0
                    ? GuardReminder.parseIso(aDate) : null;
            if (ad != null) {
                WeeklyReport.Point p = new WeeklyReport.Point();
                p.dayMs = ad.getTime();
                p.reading = anchor;
                pts.add(p);
            }
            List<GuardStore.Entry> entries =
                    GuardStore.entries(ctx, meterKey);
            for (GuardStore.Entry e : entries) {
                Date d = GuardReminder.parseIso(e.dateIso);
                if (d == null) continue;
                WeeklyReport.Point p = new WeeklyReport.Point();
                p.dayMs = d.getTime();
                p.reading = e.reading;
                pts.add(p);
            }
            weekUnits = WeeklyReport.weekUnits(pts, nowMs);
            if (ad != null) {
                double latest = anchor;
                if (!entries.isEmpty()) {
                    latest = entries.get(entries.size() - 1).reading;
                }
                usedCycle = Math.max(0, latest - anchor);
                daysElapsed = GuardReminder.daysBetween(aDate,
                        GuardReminder.todayIso());
            }
        }

        String text;
        if (weekUnits > 0) {
            double remaining = usedCycle >= 0
                    ? Math.max(0,
                            GuardCalc.UNIT_LIMIT - usedCycle)
                    : Math.max(0,
                            GuardCalc.UNIT_LIMIT - weekUnits);
            boolean danger = false;
            if (usedCycle >= 0) {
                GuardCalc.Projection p = GuardCalc.project(usedCycle,
                        daysElapsed,
                        GuardCalc.DEFAULT_CYCLE_DAYS);
                danger = p.status == GuardCalc.Status.DANGER;
            }
            text = danger
                    ? ctx.getString(
                            R.string.weekly_notif_text_danger,
                            fmt1(weekUnits), fmt1(remaining))
                    : ctx.getString(R.string.weekly_notif_text,
                            fmt1(weekUnits), fmt1(remaining));
        } else {
            // v3.6.0: zero-input Guard — auto-project from the last
            // fetched bill's pace, labeled as an andaza. No bill
            // either -> stay silent, never spam.
            LescoBillFetcher.BillInfo info =
                    cachedBillInfo(ctx, meterKey);
            if (info == null || !(info.unitsAsInt() > 0)) return;
            String rd =
                    GuardReminder.parseBillDate(info.readingDate);
            int billDaysElapsed = rd == null ? 0 : Math.max(0,
                    GuardReminder.daysBetween(rd,
                            GuardReminder.todayIso()));
            GuardCalc.Projection ap = GuardCalc.autoProjectFromBill(
                    info.unitsAsInt(), GuardCalc.DEFAULT_CYCLE_DAYS,
                    billDaysElapsed);
            text = ap.status == GuardCalc.Status.DANGER
                    ? ctx.getString(
                            R.string.weekly_notif_text_auto_danger,
                            fmt1(ap.projectedUnits))
                    : ctx.getString(
                            R.string.weekly_notif_text_auto,
                            fmt1(ap.projectedUnits));
        }

        // v3.6.0: the weekly reading nudge rides on every Sunday
        // report — one line, always.
        text = text + "\n"
                + ctx.getString(R.string.weekly_notif_nudge);

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.weekly_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

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
        b.setContentTitle(ctx.getString(R.string.weekly_notif_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
            // A denied/missing permission must never crash the receiver.
        }
    }

    /** Last fetched bill's info for the meter, or null. */
    private static LescoBillFetcher.BillInfo cachedBillInfo(
            Context ctx, String meterKey) {
        try {
            BillCache.CachedPage cached =
                    BillCache.getHtml(ctx, meterKey);
            if (cached == null || cached.html == null
                    || cached.html.isEmpty()) {
                return null;
            }
            String[] parts = meterKey.split(":", 3);
            String ref = parts.length == 3 ? parts[2] : "";
            LescoBillFetcher.BillPage p =
                    LescoBillFetcher.pageFromHtml(cached.html, ref,
                            cached.fetchPath);
            if (p != null && p.parseOk && p.info != null) {
                return p.info;
            }
        } catch (Exception ignored) {
            // Fall through to null.
        }
        return null;
    }

    /** First saved meter's key, or null when none exists. */
    private static String firstSavedMeterKey(Context ctx) {
        try {
            java.util.List<SavedMeters.Meter> ms =
                    SavedMeters.load(MeterUi.store(ctx));
            if (ms == null || ms.isEmpty()) return null;
            SavedMeters.Meter m = ms.get(0);
            if (m == null || m.idValue == null
                    || m.idValue.isEmpty()) {
                return null;
            }
            String mode = "cid".equals(m.idType) ? "cid" : "ref";
            String disco = (m.disco == null || m.disco.isEmpty())
                    ? "lesco" : m.disco;
            return disco + ":" + mode + ":" + m.idValue;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
