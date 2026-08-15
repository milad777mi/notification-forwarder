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
import kotlin.math.min

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
    private const val dedupFileName = "sent_keys.txt"

    // جلوگیری از تکرار اعلان در این مدت
    private const val DEDUP_TTL = 60 * 60 * 1000L

    // حداکثر تعداد کلیدهای ضدتکرار
    private const val MAX_DEDUP_KEYS = 200

    // فاصله بین درخواست‌های روبیکا
    private const val RUBIKA_DELAY_MS = 3500L

    // Timeout شبکه
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 10000

    // حداکثر تعداد تلاش برای تست هم‌زمان
    private val sendMutex = Mutex()
    private val queueMutex = Mutex()

    // جلوگیری از اجرای هم‌زمان تست
    private val testMutex = Mutex()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        log("Sender initialized")
    }

    // =====================================================
    // SEND NOTIFICATION
    // =====================================================

    suspend fun send(
        app: String,
        pkg: String,
        title: String,
        text: String,
        time: Long,
        notificationKey: String = ""
    ) {

        // ضدتکرار
        if (
            notificationKey.isNotBlank() &&
            isDuplicate(notificationKey)
        ) {
            log(
                "Duplicate notification skipped: $notificationKey"
            )
            return
        }

        if (
            BALE_BOT_TOKEN.isBlank() &&
            RUBIKA_BOT_TOKEN.isBlank()
        ) {
            log("Error: No tokens configured")
            return
        }

        val message =
            buildMessage(
                app,
                pkg,
                title,
                text,
                time
            )

        val destinations =
            getDestinations()

        if (destinations.isEmpty()) {
            log("Error: No valid destinations configured")
            return
        }

        var anySuccess = false

        // فقط یک ارسال در هر لحظه
        sendMutex.withLock {

            for ((type, chatId) in destinations) {

                if (
                    type.startsWith("rubika_")
                ) {
                    delay(RUBIKA_DELAY_MS)
                }

                val result =
                    sendToDestination(
                        type,
                        chatId,
                        message
                    )

                if (result.success) {

                    anySuccess = true

                    log(
                        "Success to $type ($chatId)"
                    )

                } else {

                    log(
                        "Failed to $type ($chatId), reason=${result.reason}"
                    )
                }
            }
        }

        // =================================================
        // RESULT
        // =================================================

        if (anySuccess) {

            // حداقل یک مقصد موفق بوده
            if (
                notificationKey.isNotBlank()
            ) {
                markAsSent(notificationKey)
            }

            log(
                "At least one destination succeeded"
            )

            // تلاش برای ارسال صف
            drainQueue()

        } else {

            // هیچ مقصدی موفق نشده
            log(
                "All destinations failed, saving to queue"
            )

            val payload =
                JSONObject().apply {

                    put("app", app)
                    put("package", pkg)
                    put("title", title)
                    put("text", text)
                    put("time", time.toString())
                    put("key", notificationKey)
                }

            saveToQueue(payload)
        }
    }

    // =====================================================
    // TEST SEND
    // =====================================================

    suspend fun testSend(): Boolean {

        // جلوگیری از چند Test هم‌زمان
        return testMutex.withLock {

            log("Test send started")

            val testMessage =
                "🔔 پیام تست از برنامه\n" +
                        "زمان: ${formatTime(System.currentTimeMillis())}"

            val destinations =
                getDestinations()

            if (destinations.isEmpty()) {

                log(
                    "Test failed: no destinations configured"
                )

                return@withLock false
            }

            var anySuccess = false

            sendMutex.withLock {

                for ((type, chatId) in destinations) {

                    // فاصله مناسب برای روبیکا
                    if (
                        type.startsWith("rubika_")
                    ) {
                        delay(RUBIKA_DELAY_MS)
                    }

                    val result =
                        sendToDestination(
                            type,
                            chatId,
                            testMessage
                        )

                    if (result.success) {

                        log(
                            "Test success to $type ($chatId)"
                        )

                        anySuccess = true

                    } else {

                        log(
                            "Test failed to $type ($chatId), reason=${result.reason}"
                        )
                    }
                }
            }

            log(
                "Test send finished, anySuccess=$anySuccess"
            )

            anySuccess
        }
    }

    // =====================================================
    // DESTINATIONS
    // =====================================================

    private fun getDestinations():
            List<Pair<String, String>> {

        val list =
            mutableListOf<Pair<String, String>>()

        if (
            BALE_BOT_TOKEN.isNotBlank() &&
            BALE_USER_ID.isNotBlank()
        ) {

            list.add(
                Pair(
                    "bale_user",
                    BALE_USER_ID
                )
            )
        }

        if (
            BALE_BOT_TOKEN.isNotBlank() &&
            BALE_CHANNEL_ID.isNotBlank()
        ) {

            list.add(
                Pair(
                    "bale_channel",
                    BALE_CHANNEL_ID
                )
            )
        }

        if (
            RUBIKA_BOT_TOKEN.isNotBlank() &&
            RUBIKA_USER_ID.isNotBlank()
        ) {

            list.add(
                Pair(
                    "rubika_user",
                    RUBIKA_USER_ID
                )
            )
        }

        if (
            RUBIKA_BOT_TOKEN.isNotBlank() &&
            RUBIKA_CHANNEL_ID.isNotBlank()
        ) {

            list.add(
                Pair(
                    "rubika_channel",
                    RUBIKA_CHANNEL_ID
                )
            )
        }

        return list
    }

    // =====================================================
    // MESSAGE
    // =====================================================

    private fun buildMessage(
        app: String,
        pkg: String,
        title: String,
        text: String,
        time: Long
    ): String {

        val formattedTime =
            formatTime(time)

        return "📱 $app\n" +
                "Package: $pkg\n" +
                "Title: $title\n" +
                "Text: $text\n" +
                "Time: $formattedTime"
    }

    // =====================================================
    // SEND RESULT
    // =====================================================

    private data class SendResult(
        val success: Boolean,
        val reason: String = ""
    )

    // =====================================================
    // SEND TO DESTINATION
    // =====================================================

    private suspend fun sendToDestination(
        type: String,
        chatId: String,
        message: String
    ): SendResult {

        return withContext(Dispatchers.IO) {

            var conn: HttpURLConnection? = null

            try {

                val url: URL

                val payload =
                    JSONObject()

                when (type) {

                    "bale_user",
                    "bale_channel" -> {

                        url =
                            URL(
                                "https://tapi.bale.ai/bot" +
                                        BALE_BOT_TOKEN +
                                        "/sendMessage"
                            )

                        payload.put(
                            "chat_id",
                            chatId
                        )

                        payload.put(
                            "text",
                            message
                        )
                    }

                    "rubika_user",
                    "rubika_channel" -> {

                        url =
                            URL(
                                "https://botapi.rubika.ir/v3/" +
                                        RUBIKA_BOT_TOKEN +
                                        "/sendMessage"
                            )

                        payload.put(
                            "chat_id",
                            chatId
                        )

                        payload.put(
                            "text",
                            message
                        )

                        payload.put(
                            "random_id",
                            UUID.randomUUID().toString()
                        )
                    }

                    else -> {

                        return@withContext SendResult(
                            false,
                            "Unknown destination"
                        )
                    }
                }

                conn =
                    url.openConnection()
                            as HttpURLConnection

                conn.requestMethod = "POST"

                conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                conn.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                conn.connectTimeout =
                    CONNECT_TIMEOUT_MS

                conn.readTimeout =
                    READ_TIMEOUT_MS

                conn.doOutput = true

                OutputStreamWriter(
                    conn.outputStream,
                    Charsets.UTF_8
                ).use {

                    it.write(
                        payload.toString()
                    )

                    it.flush()
                }

                val code =
                    conn.responseCode

                val response =
                    try {

                        if (code in 200..399) {

                            conn.inputStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: ""

                        } else {

                            conn.errorStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: ""
                        }

                    } catch (_: Exception) {
                        ""
                    }

                log(
                    "API response $type $chatId: " +
                            "code=$code, " +
                            "body=${response.take(300)}"
                )

                // =================================================
                // RUBIKA
                // =================================================

                if (
                    type.startsWith("rubika_")
                ) {

                    val jsonResp =
                        runCatching {
                            JSONObject(response)
                        }.getOrNull()

                    val status =
                        jsonResp
                            ?.optString(
                                "status"
                            )
                            ?: ""

                    when (status) {

                        "OK" -> {

                            SendResult(
                                true,
                                "OK"
                            )
                        }

                        "TOO_REQUESTS" -> {

                            log(
                                "Rubika rate limit: TOO_REQUESTS for $chatId"
                            )

                            SendResult(
                                false,
                                "TOO_REQUESTS"
                            )
                        }

                        else -> {

                            SendResult(
                                false,
                                if (
                                    status.isNotBlank()
                                ) {
                                    status
                                } else {
                                    "HTTP $code"
                                }
                            )
                        }
                    }

                } else {

                    // =================================================
                    // BALE
                    // =================================================

                    val jsonResp =
                        runCatching {
                            JSONObject(response)
                        }.getOrNull()

                    val ok =
                        jsonResp?.optBoolean(
                            "ok",
                            false
                        ) ?: false

                    if (
                        code in 200..299 &&
                        ok
                    ) {

                        SendResult(
                            true,
                            "OK"
                        )

                    } else {

                        SendResult(
                            false,
                            "HTTP $code"
                        )
                    }
                }

            } catch (e: Exception) {

                log(
                    "Error sending to $type $chatId: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                )

                SendResult(
                    false,
                    e.message ?: "Network error"
                )

            } finally {

                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    // =====================================================
    // DEDUP CHECK
    // =====================================================

    private suspend fun isDuplicate(
        key: String
    ): Boolean {

        if (key.isBlank()) {
            return false
        }

        val ctx =
            context ?: return false

        return withContext(Dispatchers.IO) {

            try {

                val file =
                    File(
                        ctx.filesDir,
                        dedupFileName
                    )

                if (!file.exists()) {
                    return@withContext false
                }

                val now =
                    System.currentTimeMillis()

                file.readLines().any { line ->

                    val parts =
                        line.split("|")

                    if (parts.size != 2) {
                        false
                    } else {

                        val savedKey =
                            parts[0]

                        val savedTime =
                            parts[1]
                                .toLongOrNull()
                                ?: 0L

                        savedKey == key &&
                                now - savedTime <
                                DEDUP_TTL
                    }
                }

            } catch (_: Exception) {

                false
            }
        }
    }

    // =====================================================
    // MARK SENT
    // =====================================================

    private suspend fun markAsSent(
        key: String
    ) {

        if (key.isBlank()) {
            return
        }

        val ctx =
            context ?: return

        withContext(Dispatchers.IO) {

            try {

                val file =
                    File(
                        ctx.filesDir,
                        dedupFileName
                    )

                val now =
                    System.currentTimeMillis()

                val lines =
                    if (file.exists()) {
                        file.readLines()
                    } else {
                        emptyList()
                    }

                val cleaned =
                    lines.filter { line ->

                        val parts =
                            line.split("|")

                        if (parts.size != 2) {
                            false
                        } else {

                            val time =
                                parts[1]
                                    .toLongOrNull()
                                    ?: 0L

                            now - time <
                                    DEDUP_TTL
                        }
                    }.toMutableList()

                cleaned.add(
                    "$key|$now"
                )

                val finalList =
                    if (
                        cleaned.size >
                        MAX_DEDUP_KEYS
                    ) {

                        cleaned.takeLast(
                            MAX_DEDUP_KEYS
                        )

                    } else {

                        cleaned
                    }

                file.writeText(
                    finalList.joinToString("\n")
                )

            } catch (e: Exception) {

                log(
                    "Error markAsSent: ${e.message}"
                )
            }
        }
    }

    // =====================================================
    // SAVE QUEUE
    // =====================================================

    private suspend fun saveToQueue(
        payload: JSONObject
    ) {

        queueMutex.withLock {

            withContext(Dispatchers.IO) {

                try {

                    val ctx =
                        context
                            ?: return@withContext

                    val file =
                        File(
                            ctx.filesDir,
                            queueFileName
                        )

                    file.appendText(
                        payload.toString() +
                                "\n"
                    )

                    log(
                        "Saved to queue, " +
                                "queue size=${file.length()} bytes"
                    )

                } catch (e: Exception) {

                    log(
                        "Error saving to queue: ${e.message}"
                    )
                }
            }
        }
    }

    // =====================================================
    // DRAIN QUEUE
    // =====================================================

    suspend fun drainQueue() {

        queueMutex.withLock {

            withContext(Dispatchers.IO) {

                try {

                    val ctx =
                        context
                            ?: return@withContext

                    val file =
                        File(
                            ctx.filesDir,
                            queueFileName
                        )

                    if (!file.exists()) {
                        return@withContext
                    }

                    val lines =
                        file.readLines()
                            .toMutableList()

                    if (lines.isEmpty()) {

                        file.delete()

                        return@withContext
                    }

                    val iterator =
                        lines.iterator()

                    var changed = false

                    while (iterator.hasNext()) {

                        val line =
                            iterator.next()

                        try {

                            val json =
                                JSONObject(line)

                            val app =
                                json.getString(
                                    "app"
                                )

                            val pkg =
                                json.getString(
                                    "package"
                                )

                            val title =
                                json.getString(
                                    "title"
                                )

                            val text =
                                json.getString(
                                    "text"
                                )

                            val time =
                                json.getLong(
                                    "time"
                                )

                            val key =
                                json.optString(
                                    "key",
                                    ""
                                )

                            // اگر قبلاً موفق شده
                            if (
                                key.isNotBlank() &&
                                isDuplicate(key)
                            ) {

                                iterator.remove()

                                changed = true

                                continue
                            }

                            val message =
                                buildMessage(
                                    app,
                                    pkg,
                                    title,
                                    text,
                                    time
                                )

                            val destinations =
                                getDestinations()

                            var anySuccess =
                                false

                            for (
                                destination
                                in destinations
                            ) {

                                val type =
                                    destination.first

                                val chatId =
                                    destination.second

                                if (
                                    type.startsWith(
                                        "rubika_"
                                    )
                                ) {

                                    delay(
                                        RUBIKA_DELAY_MS
                                    )
                                }

                                val result =
                                    sendToDestination(
                                        type,
                                        chatId,
                                        message
                                    )

                                if (
                                    result.success
                                ) {

                                    anySuccess = true
                                }
                            }

                            if (anySuccess) {

                                if (
                                    key.isNotBlank()
                                ) {

                                    markAsSent(key)
                                }

                                iterator.remove()

                                changed = true

                            } else {

                                // اولین پیام هنوز شکست خورده؛
                                // بیشتر تلاش نکن
                                break
                            }

                        } catch (e: Exception) {

                            log(
                                "Queue item error: ${e.message}"
                            )

                            break
                        }
                    }

                    if (changed) {

                        if (lines.isEmpty()) {

                            file.delete()

                            log(
                                "Queue drained, file deleted"
                            )

                        } else {

                            file.writeText(
                                lines.joinToString(
                                    "\n"
                                ) + "\n"
                            )

                            log(
                                "Queue partially drained, " +
                                        "${lines.size} left"
                            )
                        }
                    }

                } catch (e: Exception) {

                    log(
                        "Error in drainQueue: ${e.message}"
                    )
                }
            }
        }
    }

    // =====================================================
    // LOG
    // =====================================================

    private fun log(
        message: String
    ) {

        val ctx =
            context ?: return

        val timestamp =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US
            ).format(Date())

        val logLine =
            "[$timestamp] $message\n"

        try {

            val file =
                File(
                    ctx.filesDir,
                    logFileName
                )

            file.appendText(
                logLine
            )

            val lines =
                file.readLines()

            if (lines.size > 200) {

                file.writeText(
                    lines
                        .takeLast(200)
                        .joinToString("\n") +
                            "\n"
                )
            }

        } catch (_: Exception) {
        }
    }

    // =====================================================
    // READ LOGS
    // =====================================================

    fun readLogs(): String {

        val ctx =
            context
                ?: return "No context"

        return try {

            val file =
                File(
                    ctx.filesDir,
                    logFileName
                )

            if (file.exists()) {

                file.readText()

            } else {

                "No logs yet"
            }

        } catch (e: Exception) {

            "Error reading logs: ${e.message}"
        }
    }

    // =====================================================
    // FORMAT TIME
    // =====================================================

    private fun formatTime(
        time: Long
    ): String {

        return try {

            val date =
                Date(time)

            val sdf =
                SimpleDateFormat(
                    "yyyy/MM/dd HH:mm",
                    Locale.US
                )

            sdf.timeZone =
                TimeZone.getTimeZone(
                    "Asia/Tehran"
                )

            sdf.format(date)

        } catch (_: Exception) {

            time.toString()
        }
    }
}
