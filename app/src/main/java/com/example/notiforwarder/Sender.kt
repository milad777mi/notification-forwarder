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

    // اطلاعات بله (Bale)
    private val BALE_BOT_TOKEN = BuildConfig.BALE_BOT_TOKEN
    private val BALE_USER_ID = BuildConfig.BALE_USER_ID
    private val BALE_CHANNEL_ID = BuildConfig.BALE_CHANNEL_ID

    // اطلاعات روبیکا (Rubika)
    private val RUBIKA_BOT_TOKEN = BuildConfig.RUBIKA_BOT_TOKEN
    private val RUBIKA_USER_ID = BuildConfig.RUBIKA_USER_ID
    private val RUBIKA_CHANNEL_ID = BuildConfig.RUBIKA_CHANNEL_ID

    private const val queueFileName = "pending_notifications.json"
    private val mutex = Mutex()

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    suspend fun send(
        app: String,
        pkg: String,
        title: String,
        text: String,
        time: Long
    ) {
        if (BALE_BOT_TOKEN.isBlank() && RUBIKA_BOT_TOKEN.isBlank()) return

        val message = buildMessage(app, pkg, title, text, time)

        val destinations = mutableListOf<Pair<String, String>>()

        if (BALE_BOT_TOKEN.isNotBlank() && BALE_USER_ID.isNotBlank()) {
            destinations.add(Pair("bale_user", BALE_USER_ID))
        }
        if (BALE_BOT_TOKEN.isNotBlank() && BALE_CHANNEL_ID.isNotBlank()) {
            destinations.add(Pair("bale_channel", BALE_CHANNEL_ID))
        }
        if (RUBIKA_BOT_TOKEN.isNotBlank() && RUBIKA_USER_ID.isNotBlank()) {
            destinations.add(Pair("rubika_user", RUBIKA_USER_ID))
        }
        if (RUBIKA_BOT_TOKEN.isNotBlank() && RUBIKA_CHANNEL_ID.isNotBlank()) {
            destinations.add(Pair("rubika_channel", RUBIKA_CHANNEL_ID))
        }

        var allFailed = true
        for ((type, chatId) in destinations) {
            val success = sendToDestination(type, chatId, message)
            if (success) {
                allFailed = false
            }
        }

        if (allFailed) {
            // اگر هیچ‌کدام موفق نبود، در صف ذخیره کن
            val payload = JSONObject().apply {
                put("app", app)
                put("package", pkg)
                put("title", title)
                put("text", text)
                put("time", time.toString())
            }
            saveToQueue(payload)
        } else {
            // اگر حداقل یکی موفق بود، صف قبلی را خالی کن
            drainQueue()
        }
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
                conn.disconnect()

                code in 200..299
            } catch (e: Exception) {
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
                } catch (_: Exception) {
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

                            // دوباره سعی کن به همه مقصدها بفرستد
                            var allFailed = true
                            val destinations = mutableListOf<Pair<String, String>>()
                            if (BALE_BOT_TOKEN.isNotBlank() && BALE_USER_ID.isNotBlank())
                                destinations.add(Pair("bale_user", BALE_USER_ID))
                            if (BALE_BOT_TOKEN.isNotBlank() && BALE_CHANNEL_ID.isNotBlank())
                                destinations.add(Pair("bale_channel", BALE_CHANNEL_ID))
                            if (RUBIKA_BOT_TOKEN.isNotBlank() && RUBIKA_USER_ID.isNotBlank())
                                destinations.add(Pair("rubika_user", RUBIKA_USER_ID))
                            if (RUBIKA_BOT_TOKEN.isNotBlank() && RUBIKA_CHANNEL_ID.isNotBlank())
                                destinations.add(Pair("rubika_channel", RUBIKA_CHANNEL_ID))

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
                        } else {
                            file.writeText(lines.joinToString("\n") + "\n")
                        }
                    }
                } catch (_: Exception) {
                }
            }
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
