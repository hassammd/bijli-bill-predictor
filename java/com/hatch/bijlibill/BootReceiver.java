package com.hatch.bijlibill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * v3.1.1 — re-arms the daily bill reminder after a reboot. Alarms do
 * not survive reboot, so on BOOT_COMPLETED we re-read the persisted
 * bill state and re-arm when the notify window is still open.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        // DailyReminderAlarm.schedule() re-validates everything
        // (bill stored, permission granted, window still open) and
        // no-ops otherwise. v3.3.0: always-on, no toggle.
        DailyReminderAlarm.schedule(context);
        // v3.2.0: re-arm the 200-Unit Guard check, but only when a
        // guard meter is actually active.
        GuardReminderAlarm.scheduleIfEnabled(context);
        // v3.4.0: re-arm the bill-day nudge (persisted trigger) and
        // the weekly usage report. Both no-op safely without data.
        BillDayNudgeAlarm.scheduleIfArmed(context);
        WeeklyReportAlarm.schedule(context);
    }
}
