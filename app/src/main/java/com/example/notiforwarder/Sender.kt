package com.example.notiforwarder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Sender {

    // این مقادیر توسط سیستم BuildConfig از Secrets گیت‌هاب پر می‌شوند
    private val WORKER_URL = BuildConfig.WORKER_URL
    private val SECRET_TOKEN = BuildConfig.SECRET_TOKEN

    suspend fun send(app: String, pkg: String, title: String, text: String, time: Long) {
        if (WORKER_URL.isBlank() || SECRET_TOKEN.isBlank()) return

        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("app", app)
                    put("package", pkg)
                    put("title", title)
                    put("text", text)
                    put("time", time.toString())
                }.toString()

                val url = URL(WORKER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", SECRET_TOKEN)
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(payload) }
                conn.responseCode  // برای اطمینان از ارسال
                conn.disconnect()
            } catch (_: Exception) {
                // در نسخه‌های بعدی ذخیره‌ی آفلاین اضافه کنید
            }
        }
    }
}
