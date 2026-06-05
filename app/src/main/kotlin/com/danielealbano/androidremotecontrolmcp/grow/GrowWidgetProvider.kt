package com.danielealbano.androidremotecontrolmcp.grow

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.danielealbano.androidremotecontrolmcp.R

/**
 * Home-screen widget that opens [GrowActivity].
 *
 * v1 is intentionally tiny: a warm one-liner plus a tap target. Live status
 * (体力 / 心情 / …) is a deliberate follow-up — wiring real data into a widget
 * needs async fetch + update, which is out of scope for the first shell.
 */
class GrowWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(context, GrowActivity::class.java)
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val views =
                RemoteViews(context.packageName, R.layout.widget_grow).apply {
                    setOnClickPendingIntent(R.id.widget_grow_root, pendingIntent)
                }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
