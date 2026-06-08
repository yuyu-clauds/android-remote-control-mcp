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

    /** 铝碳酸镁 fires 1.5h after a meal tap (两餐之间). */
    private const val LVTAN_DELAY_MS = 90L * 60L * 1000L

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

    /**
     * Ensure notification channels exist. As of 6/8 the in-app med reminders are
     * signal-triggered (see [onWake] / [onMeal]) instead of armed at fixed clock times, so
     * this no longer schedules the legacy [GrowReminders.ALL] alarms — the server-side
     * Telegram reminder is the fixed-time backstop. Safe to call repeatedly (boot / app start).
     */
    fun scheduleAll(context: Context) {
        ensureChannels(context)
        Log.i(TAG, "med reminders are signal-triggered; channels ensured")
    }

    /** 醒了 → 泮托拉唑（空腹晨起），点一下立刻提。 */
    fun onWake(context: Context) {
        scheduleOneShot(context, GrowReminders.EVENT_PANTUOLA, 0L)
    }

    /** 吃饭（早/午/晚）→ 莫沙必利当下提，铝碳酸镁饭后 1.5 小时提。 */
    fun onMeal(context: Context) {
        scheduleOneShot(context, GrowReminders.EVENT_MOSHA, 0L)
        scheduleOneShot(context, GrowReminders.EVENT_LVTAN, LVTAN_DELAY_MS)
    }

    /** One-shot reminder relative to now (event-triggered); unlike the daily ones it does not repeat. */
    fun scheduleOneShot(context: Context, reminder: GrowReminders.Reminder, delayMillis: Long) {
        ensureChannels(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        scheduleOneShotAt(am, context, reminder, System.currentTimeMillis() + delayMillis)
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

    private fun scheduleOneShotAt(
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
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied, using inexact one-shot: ${e.message}")
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
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

    private fun triggerCalendar(hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
