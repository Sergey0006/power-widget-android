package com.sergey.powerwidget

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Модуль автоматической проверки и установки обновлений APK из GitHub Releases.
 */
object AppUpdater {
    private const val GITHUB_REPO = "Sergey0006/power-widget-android"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    fun checkForUpdate(context: Context, showToastIfLatest: Boolean = false, onComplete: (() -> Unit)? = null) {
        thread {
            try {
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "PowerWidget-App")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "").removePrefix("v")
                    val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName

                    val assets = json.optJSONArray("assets")
                    var downloadUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    if (isNewerVersion(tagName, currentVersion) && !downloadUrl.isNullOrEmpty()) {
                        val releaseNotes = json.optString("body", "Доступна новая версия виджета!")
                        android.os.Handler(context.mainLooper).post {
                            showUpdateDialog(context, tagName, releaseNotes, downloadUrl)
                        }
                    } else if (showToastIfLatest) {
                        android.os.Handler(context.mainLooper).post {
                            Toast.makeText(context, "У вас установлена последняя версия ($currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                if (showToastIfLatest) {
                    android.os.Handler(context.mainLooper).post {
                        Toast.makeText(context, "Не удалось проверить обновление: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                onComplete?.let { android.os.Handler(context.mainLooper).post(it) }
            }
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        if (remote.isEmpty() || current.isEmpty()) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun showUpdateDialog(context: Context, newVersion: String, notes: String, downloadUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Доступно обновление v$newVersion")
            .setMessage("Что нового:\n$notes\n\nСкачать и обновить прямо сейчас?")
            .setPositiveButton("Обновить") { _, _ ->
                downloadAndInstall(context, downloadUrl)
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun downloadAndInstall(context: Context, downloadUrl: String) {
        Toast.makeText(context, "Загрузка обновления...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000

                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val apkFile = File(cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                android.os.Handler(context.mainLooper).post {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                android.os.Handler(context.mainLooper).post {
                    Toast.makeText(context, "Ошибка скачивания: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось запустить установку: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
