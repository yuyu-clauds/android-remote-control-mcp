package com.danielealbano.androidremotecontrolmcp.grow

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Schedules the daily in-app medication reminders via [AlarmManager].
 *
 * Exact alarms are attempted (`setExactAndAllowWhileIdle`); if the OS hasn't granted
 * exact-alarm scheduling (Android 12+ can withhold it), we fall back to an inexact daily
 * repeat. Either way Telegram remains the precise backstop, so an inexact in-app nudge is
 * acceptable. Exact alarms are one-shot, so [GrowReminderReceiver] reschedules the next
 * day after each fire; boot re-arms the whole set.
 */
internal object GrowReminderScheduler {

    private const val TAG = "GrowReminderScheduler"
    const val ACTION_FIRE = "com.danielealbano.androidremotecontrolmcp.grow.ACTION_REMINDER"
    const val EXTRA_ID = "reminder_id"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                GrowReminders.CHANNEL_MED,
                "吃药提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "到点提醒吃胃药（更显眼那档，到点会弹出来）" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                GrowReminders.CHANNEL_TASK,
                "日常轻提醒",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "吃饭、喝水这类轻轻提一下，不打扰" },
        )
    }

    /** (Re)arm every reminder. Safe to call repeatedly — alarms with the same id replace. */
    fun scheduleAll(context: Context) {
        ensureChannels(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        for (reminder in GrowReminders.ALL) {
            scheduleAt(am, context, reminder, nextTrigger(reminder.hour, reminder.minute))
        }
        Log.i(TAG, "scheduled ${GrowReminders.ALL.size} reminders")
    }

    /** Re-arm a single reminder for the same time tomorrow (after it has fired). */
    fun rescheduleNextDay(context: Context, reminder: GrowReminders.Reminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val tomorrow =
            triggerCalendar(reminder.hour, reminder.minute).apply { add(Calendar.DAY_OF_YEAR, 1) }
        scheduleAt(am, context, reminder, tomorrow.timeInMillis)
    }

    private fun scheduleAt(
        am: AlarmManager,
        context: Context,
        reminder: GrowReminders.Reminder,
        triggerAtMillis: Long,
    ) {
        val pi = pendingIntent(context, reminder.id)
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAtMillis, AlarmManager.INTERVAL_DAY, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied, using inexact: ${e.message}")
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAtMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }

    private fun pendingIntent(context: Context, id: Int): PendingIntent {
        val intent =
            Intent(context, GrowReminderReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra(EXTRA_ID, id)
            }
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun nextTrigger(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val candidate = triggerCalendar(hour, minute)
        if (candidate.timeInMillis <= now.timeInMillis) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate.timeInMillis
    }

    private fun triggerCalendar(hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
