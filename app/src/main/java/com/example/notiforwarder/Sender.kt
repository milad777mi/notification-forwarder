package com.example.notiforwarder.mili

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object Sender {

    private var context: Context? = null

    private val BALE_BOT_TOKEN = BuildConfig.BALE_BOT_TOKEN
    private val BALE_USER_ID = BuildConfig.BALE_USER_ID
    private val BALE_CHANNEL_ID = BuildConfig.BALE_CHANNEL_ID

    private val RUBIKA_BOT_TOKEN = BuildConfig.RUBIKA_BOT_TOKEN
    private val RUBIKA_USER_ID = BuildConfig.RUBIKA_USER_ID
    private val RUBIKA_CHANNEL_ID = BuildConfig.RUBIKA_CHANNEL_ID

    private const val queueFileName = "pending_notifications.json"
    private const val logFileName = "sender_log.txt"
    private val mutex = Mutex()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        log("Sender initialized")
    }

    suspend fun send(
        app: String,
        pkg: String,
        title: String,
        text: String,
        time: Long
    ) {
        if (BALE_BOT_TOKEN.isBlank() && RUBIKA_BOT_TOKEN.isBlank()) {
            log("Error: No tokens configured")
            return
        }

        val message = buildMessage(app, pkg, title, text, time)
        val destinations = getDestinations()

        var allFailed = true
        for ((type, chatId) in destinations) {
            val success = sendToDestination(type, chatId, message)
            if (success) {
                log("Success to $type ($chatId)")
                allFailed = false
            } else {
                log("Failed to $type ($chatId)")
            }
        }

        if (allFailed) {
            log("All destinations failed, saving to queue")
            val payload = JSONObject().apply {
                put("app", app)
                put("package", pkg)
                put("title", title)
                put("text", text)
                put("time", time.toString())
            }
            saveToQueue(payload)
        } else {
            log("At least one destination succeeded, draining queue")
            drainQueue()
        }
    }

    suspend fun testSend(): Boolean {
        log("Test send started")
        val testMessage = "🔔 پیام تست از برنامه\nزمان: ${formatTime(System.currentTimeMillis())}"
        val destinations = getDestinations()
        var anySuccess = false
        for ((type, chatId) in destinations) {
            val success = sendToDestination(type, chatId, testMessage)
            if (success) {
                log("Test success to $type ($chatId)")
                anySuccess = true
            } else {
                log("Test failed to $type ($chatId)")
            }
        }
        log("Test send finished, anySuccess=$anySuccess")
        return anySuccess
    }

    private fun getDestinations(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        if (BALE_BOT_TOKEN.isNotBlank() && BALE_USER_ID.isNotBlank())
            list.add(Pair("bale_user", BALE_USER_ID))
        if (BALE_BOT_TOKEN.isNotBlank() && BALE_CHANNEL_ID.isNotBlank())
            list.add(Pair("bale_channel", BALE_CHANNEL_ID))
        if (RUBIKA_BOT_TOKEN.isNotBlank() && RUBIKA_USER_ID.isNotBlank())
            list.add(Pair("rubika_user", RUBIKA_USER_ID))
        if (RUBIKA_BOT_TOKEN.isNotBlank() && RUBIKA_CHANNEL_ID.isNotBlank())
            list.add(Pair("rubika_channel", RUBIKA_CHANNEL_ID))
        return list
    }

    private fun buildMessage(app: String, pkg: String, title: String, text: String, time: Long): String {
        val formattedTime = formatTime(time)
        return "📱 ${app}\n" +
                "Package: ${pkg}\n" +
                "Title: ${title}\n" +
                "Text: ${text}\n" +
                "Time: ${formattedTime}"
    }

    private suspend fun sendToDestination(type: String, chatId: String, message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url: URL
                val payload = JSONObject()

                when (type) {
                    "bale_user", "bale_channel" -> {
                        url = URL("https://tapi.bale.ai/bot${BALE_BOT_TOKEN}/sendMessage")
                        payload.put("chat_id", chatId)
                        payload.put("text", message)
                    }
                    "rubika_user", "rubika_channel" -> {
                        url = URL("https://botapi.rubika.ir/v3/${RUBIKA_BOT_TOKEN}/sendMessage")
                        payload.put("chat_id", chatId)
                        payload.put("text", message)
                    }
                    else -> return@withContext false
                }

                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use {
                    it.write(payload.toString())
                }

                val code = conn.responseCode
                val response = conn.inputStream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()

                log("API response $type $chatId: code=$code, body=${response.take(200)}")
                code in 200..299
            } catch (e: Exception) {
                log("Error sending to $type $chatId: ${e.message}")
                false
            }
        }
    }

    private suspend fun saveToQueue(payload: JSONObject) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val ctx = context ?: return@withContext
                    if (payload.length() <= 2) return@withContext

                    val file = File(ctx.filesDir, queueFileName)
                    file.appendText(payload.toString() + "\n")
                    log("Saved to queue, current queue size: ${file.length()} bytes")
                } catch (e: Exception) {
                    log("Error saving to queue: ${e.message}")
                }
            }
        }
    }

    suspend fun drainQueue() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val ctx = context ?: return@withContext
                    val file = File(ctx.filesDir, queueFileName)
                    if (!file.exists()) return@withContext

                    val lines = file.readLines().toMutableList()
                    val iterator = lines.iterator()
                    var changed = false

                    while (iterator.hasNext()) {
                        val line = iterator.next()
                        try {
                            val json = JSONObject(line)
                            val app = json.getString("app")
                            val pkg = json.getString("package")
                            val title = json.getString("title")
                            val text = json.getString("text")
                            val time = json.getLong("time")

                            val message = buildMessage(app, pkg, title, text, time)
                            val destinations = getDestinations()
                            var allFailed = true
                            for ((type, chatId) in destinations) {
                                if (sendToDestination(type, chatId, message)) {
                                    allFailed = false
                                }
                            }

                            if (!allFailed) {
                                iterator.remove()
                                changed = true
                            } else {
                                break
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }

                    if (changed) {
                        if (lines.isEmpty()) {
                            file.delete()
                            log("Queue drained, file deleted")
                        } else {
                            file.writeText(lines.joinToString("\n") + "\n")
                            log("Queue partially drained, ${lines.size} left")
                        }
                    }
                } catch (e: Exception) {
                    log("Error in drainQueue: ${e.message}")
                }
            }
        }
    }

    // تابع کمکی برای لاگ‌نویسی
    private fun log(message: String) {
        val ctx = context ?: return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val logLine = "[$timestamp] $message\n"
        try {
            val file = File(ctx.filesDir, logFileName)
            file.appendText(logLine)
            // محدود کردن اندازه لاگ به 200 خط آخر
            val lines = file.readLines()
            if (lines.size > 200) {
                file.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            // لاگ‌ها ذخیره نمی‌شوند
        }
    }

    // خواندن لاگ‌ها
    fun readLogs(): String {
        val ctx = context ?: return "No context"
        return try {
            val file = File(ctx.filesDir, logFileName)
            if (file.exists()) file.readText() else "No logs yet"
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    private fun formatTime(time: Long): String {
        return try {
            val date = Date(time)
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Tehran")
            sdf.format(date)
        } catch (e: Exception) {
            time.toString()
        }
    }
}
