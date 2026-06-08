package com.danielealbano.androidremotecontrolmcp.grow

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.danielealbano.androidremotecontrolmcp.R

/**
 * Fires the in-app reminder notification, and re-arms the schedule on boot.
 *
 * Posts on the med channel (high importance → heads-up) so it pops over whatever she is
 * doing, then re-arms the same reminder for the next day (exact alarms are one-shot).
 */
class GrowReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> GrowReminderScheduler.scheduleAll(context)
            GrowReminderScheduler.ACTION_FIRE -> fireReminder(context, intent)
        }
    }

    private fun fireReminder(context: Context, intent: Intent) {
        val id = intent.getIntExtra(GrowReminderScheduler.EXTRA_ID, -1)
        val reminder = GrowReminders.byId(id) ?: return
        showNotification(context, reminder)
        // Daily fixed reminders re-arm for tomorrow; event one-shots (EVENT_ALL) fire once and stop.
        if (GrowReminders.ALL.any { it.id == id }) {
            GrowReminderScheduler.rescheduleNextDay(context, reminder)
        }
    }

    private fun showNotification(context: Context, reminder: GrowReminders.Reminder) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val openRoom =
            PendingIntent.getActivity(
                context,
                reminder.id,
                Intent(context, GrowActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val priority =
            if (reminder.channel == GrowReminders.CHANNEL_MED) {
                NotificationCompat.PRIORITY_HIGH
            } else {
                NotificationCompat.PRIORITY_LOW
            }
        val notification =
            NotificationCompat.Builder(context, reminder.channel)
                .setSmallIcon(R.drawable.ic_grow_notify)
                .setContentTitle(reminder.title)
                .setContentText(reminder.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
                .setPriority(priority)
                .setAutoCancel(true)
                .setContentIntent(openRoom)
                .build()
        nm.notify(reminder.id, notification)
    }
}
