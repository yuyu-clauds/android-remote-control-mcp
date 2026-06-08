package com.danielealbano.androidremotecontrolmcp.grow

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import com.danielealbano.androidremotecontrolmcp.R

/**
 * Home-screen widget for the grow game.
 *
 * Two jobs: the title row opens [GrowActivity] (the room), and six state buttons
 * (醒了 / 睡觉 / 吃药 / 早饭 / 午饭 / 晚饭) record state straight to the Life Log via
 * [GrowSignalSender] — so she can tap a state on the desktop without opening the app.
 * That tap is also the feedback signal the dynamic-today engine reads ("吃了" → adjust).
 */
class GrowWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            val views =
                RemoteViews(context.packageName, R.layout.widget_grow).apply {
                    setOnClickPendingIntent(R.id.widget_grow_title_row, openRoomIntent(context, appWidgetId))
                    for (button in BUTTONS) {
                        setOnClickPendingIntent(button.viewId, signalIntent(context, button))
                    }
                }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_SIGNAL) return
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: return
        val appContext = context.applicationContext
        // 吃药提醒跟着她的动作走（6/8）：醒了→泮托拉唑；吃饭→莫沙必利当下+铝碳酸镁饭后1.5h。
        when (category) {
            "起床" -> GrowReminderScheduler.onWake(appContext)
            "三餐" -> GrowReminderScheduler.onMeal(appContext)
        }
        val pending = goAsync()
        Thread {
            val ok = GrowSignalSender.postSignal(category, label)
            Handler(Looper.getMainLooper()).post {
                val msg = if (ok) R.string.widget_grow_toast_ok else R.string.widget_grow_toast_fail
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
            }
            pending.finish()
        }.start()
    }

    private fun openRoomIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, GrowActivity::class.java)
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun signalIntent(context: Context, button: SignalButton): PendingIntent {
        val intent =
            Intent(context, GrowWidgetProvider::class.java).apply {
                action = ACTION_SIGNAL
                putExtra(EXTRA_CATEGORY, button.category)
                putExtra(EXTRA_LABEL, button.label)
            }
        return PendingIntent.getBroadcast(
            context,
            button.requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private data class SignalButton(
        val viewId: Int,
        val requestCode: Int,
        val category: String,
        val label: String,
    )

    companion object {
        private const val ACTION_SIGNAL =
            "com.danielealbano.androidremotecontrolmcp.grow.ACTION_SIGNAL"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_LABEL = "label"

        private val BUTTONS =
            listOf(
                SignalButton(R.id.widget_grow_btn_wake, 101, "起床", "醒了"),
                SignalButton(R.id.widget_grow_btn_sleep, 102, "睡眠", "睡觉了"),
                SignalButton(R.id.widget_grow_btn_med, 103, "吃药", "吃药了"),
                SignalButton(R.id.widget_grow_btn_breakfast, 104, "三餐", "吃了早饭"),
                SignalButton(R.id.widget_grow_btn_lunch, 105, "三餐", "吃了午饭"),
                SignalButton(R.id.widget_grow_btn_dinner, 106, "三餐", "吃了晚饭"),
            )
    }
}
