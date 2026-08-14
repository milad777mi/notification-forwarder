package com.example.notiforwarderPANEL

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Sender {

    private var context: Context? = null
    private val WORKER_URL = BuildConfig.WORKER_URL
    private val SECRET_TOKEN = BuildConfig.SECRET_TOKEN
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
        if (WORKER_URL.isBlank() || SECRET_TOKEN.isBlank()) return

        val payload = JSONObject().apply {
            put("app", app)
            put("package", pkg)
            put("title", title)
            put("text", text)
            put("time", time.toString())
        }

        val success = sendToServer(payload)

        if (success) {
            drainQueue()
        } else {
            saveToQueue(payload)
        }
    }

    private suspend fun sendToServer(payload: JSONObject): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(WORKER_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-Key", SECRET_TOKEN)
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
                            val success = sendToServer(json)
                            if (success) {
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
}
