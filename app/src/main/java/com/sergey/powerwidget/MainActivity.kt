package com.sergey.powerwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvDate: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvNextEvent: TextView
    private lateinit var tvSensor: TextView
    private lateinit var tvTodaySchedule: TextView
    private lateinit var tvVersion: TextView

    private lateinit var btnQ12: Button
    private lateinit var btnQ21: Button
    private lateinit var btnQ41: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnCheckUpdate: Button

    private var currentQueue = "2.1"
    private val apiUrl = "https://pm-schedule-bot.onrender.com/api/status?queue="

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSavedQueue()
        setupListeners()
        loadStatusData()

        // Проверяем наличие обновлений в фоне при запуске
        AppUpdater.checkForUpdate(this, showToastIfLatest = false)
    }

    private fun initViews() {
        tvDate = findViewById(R.id.tv_date)
        tvStatusBadge = findViewById(R.id.tv_status_badge)
        tvNextEvent = findViewById(R.id.tv_next_event)
        tvSensor = findViewById(R.id.tv_sensor)
        tvTodaySchedule = findViewById(R.id.tv_today_schedule)
        tvVersion = findViewById(R.id.tv_version)

        btnQ12 = findViewById(R.id.btn_q_1_2)
        btnQ21 = findViewById(R.id.btn_q_2_1)
        btnQ41 = findViewById(R.id.btn_q_4_1)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnCheckUpdate = findViewById(R.id.btn_check_update)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        tvVersion.text = "Версия: $versionName"
    }

    private fun loadSavedQueue() {
        val prefs = getSharedPreferences(PowerWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
        currentQueue = prefs.getString(PowerWidgetProvider.KEY_QUEUE, PowerWidgetProvider.DEFAULT_QUEUE) ?: "2.1"
        updateQueueButtonsUi()
    }

    private fun selectQueue(q: String) {
        currentQueue = q
        val prefs = getSharedPreferences(PowerWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PowerWidgetProvider.KEY_QUEUE, q).apply()
        updateQueueButtonsUi()
        loadStatusData()

        // Уведомляем виджет об изменении очереди
        val intent = Intent(this, PowerWidgetProvider::class.java).apply {
            action = PowerWidgetProvider.ACTION_REFRESH
        }
        sendBroadcast(intent)
    }

    private fun updateQueueButtonsUi() {
        btnQ12.setBackgroundColor(if (currentQueue == "1.2") getColor(R.color.accent_blue) else getColor(R.color.widget_card_bg))
        btnQ21.setBackgroundColor(if (currentQueue == "2.1") getColor(R.color.accent_blue) else getColor(R.color.widget_card_bg))
        btnQ41.setBackgroundColor(if (currentQueue == "4.1") getColor(R.color.accent_blue) else getColor(R.color.widget_card_bg))
    }

    private fun setupListeners() {
        btnQ12.setOnClickListener { selectQueue("1.2") }
        btnQ21.setOnClickListener { selectQueue("2.1") }
        btnQ41.setOnClickListener { selectQueue("4.1") }

        btnRefresh.setOnClickListener {
            loadStatusData()
            // Обновляем также и виджет
            val intent = Intent(this, PowerWidgetProvider::class.java).apply {
                action = PowerWidgetProvider.ACTION_REFRESH
            }
            sendBroadcast(intent)
        }

        btnCheckUpdate.setOnClickListener {
            AppUpdater.checkForUpdate(this, showToastIfLatest = true)
        }
    }

    private fun loadStatusData() {
        tvStatusBadge.text = "⏳ Загрузка..."
        thread {
            try {
                val conn = URL(apiUrl + currentQueue).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val activeQ = json.optJSONObject("active_queue")

                    runOnUiThread {
                        if (activeQ != null) {
                            val isPower = activeQ.optBoolean("power", true)
                            val nextEvent = activeQ.optString("next_event_text", "")
                            val totalOff = activeQ.optString("total_off_today", "")
                            val dayRu = json.optString("day_ru", "")
                            val nowTime = json.optString("now", "")

                            tvDate.text = "$dayRu • $nowTime"

                            if (isPower) {
                                tvStatusBadge.text = "🟢 СВЕТ ЕСТЬ"
                                tvStatusBadge.setTextColor(getColor(R.color.green_light))
                            } else {
                                tvStatusBadge.text = "🔴 СВЕТА НЕТ"
                                tvStatusBadge.setTextColor(getColor(R.color.red_light))
                            }

                            tvNextEvent.text = "⏳ $nextEvent"

                            val sensorObj = activeQ.optJSONObject("sensor")
                            if (sensorObj != null && sensorObj.optBoolean("has_sensor", false)) {
                                val cleanSensor = sensorObj.optString("status_line", "").replace(Regex("<[^>]*>"), "")
                                tvSensor.text = cleanSensor
                            } else {
                                tvSensor.text = "📡 По данной очереди внешний датчик пока не подключен"
                            }

                            val scheduleArr = activeQ.optJSONArray("schedule_today")
                            val intervalsList = mutableListOf<String>()
                            if (scheduleArr != null) {
                                for (i in 0 until scheduleArr.length()) {
                                    intervalsList.add(scheduleArr.getString(i))
                                }
                            }

                            val schedText = if (intervalsList.isNotEmpty()) {
                                "График отключений (всего $totalOff):\n• " + intervalsList.joinToString("\n• ")
                            } else {
                                "Отключений на сегодня не запланировано 🎉"
                            }
                            tvTodaySchedule.text = schedText
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка соединения: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
