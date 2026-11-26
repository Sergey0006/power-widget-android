package com.sergey.powerwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Провайдер виджета отключений света на рабочем столе Android.
 */
class PowerWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.sergey.powerwidget.ACTION_REFRESH"
        private const val BASE_API_URL = "https://pm-schedule-bot.onrender.com/api/status?queue="
        const val PREFS_NAME = "power_widget_prefs"
        const val KEY_QUEUE = "selected_queue"
        const val DEFAULT_QUEUE = "2.1"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PowerWidgetProvider::class.java)
            val allIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (id in allIds) {
                updateAppWidget(context, appWidgetManager, id)
            }
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val queue = prefs.getString(KEY_QUEUE, DEFAULT_QUEUE) ?: DEFAULT_QUEUE

        val views = RemoteViews(context.packageName, R.layout.widget_power)

        // Клик по телу виджета открывает главное приложение
        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

        // Клик по кнопке Обновить вызывает принудительный перерасчет
        val refreshIntent = Intent(context, PowerWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)

        // Показываем индикатор загрузки
        views.setTextViewText(R.id.widget_updated_at, "Обновление...")
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Фоновый запрос к серверу
        thread {
            try {
                val url = URL(BASE_API_URL + queue)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val activeQ = json.optJSONObject("active_queue")

                    if (activeQ != null) {
                        val isPower = activeQ.optBoolean("power", true)
                        val nextEvent = activeQ.optString("next_event_text", "")
                        val sensorObj = activeQ.optJSONObject("sensor")
                        val updatedTime = json.optString("now", "")

                        views.setTextViewText(R.id.widget_queue_title, "⚡️ Очередь $queue")
                        views.setTextViewText(R.id.widget_updated_at, updatedTime)

                        if (isPower) {
                            views.setTextViewText(R.id.widget_status_text, "🟢 СВЕТ ЕСТЬ")
                            views.setInt(R.id.widget_badge_layout, "setBackgroundResource", R.drawable.bg_badge_on)
                        } else {
                            views.setTextViewText(R.id.widget_status_text, "🔴 СВЕТА НЕТ")
                            views.setInt(R.id.widget_badge_layout, "setBackgroundResource", R.drawable.bg_badge_off)
                        }

                        views.setTextViewText(R.id.widget_next_event, nextEvent)

                        if (sensorObj != null && sensorObj.optBoolean("has_sensor", false)) {
                            val sensorLine = sensorObj.optString("status_line", "")
                            // Очищаем HTML-теги для простого отображения в виджете
                            val cleanSensor = sensorLine.replace(Regex("<[^>]*>"), "")
                            views.setTextViewText(R.id.widget_sensor_line, cleanSensor)
                            views.setViewVisibility(R.id.widget_sensor_line, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.widget_sensor_line, View.GONE)
                        }

                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            } catch (e: Exception) {
                views.setTextViewText(R.id.widget_updated_at, "Ошибка сети")
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
