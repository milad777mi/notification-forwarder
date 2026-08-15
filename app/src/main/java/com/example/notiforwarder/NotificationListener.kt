package com.example.notiforwarder.mili

import android.app.Notification
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class NotificationListener : NotificationListenerService() {

    private val callPackages = setOf(
        "com.samsung.android.dialer",
        "com.android.phone",
        "com.samsung.android.incallui"
    )

    private val messagePackage =
        "com.samsung.android.messaging"

    // برنامه‌های مجاز بدون فیلتر
    private val allowedPackages = setOf(
        "ir.nasim"
    )

    private val job = SupervisorJob()

    private val scope =
        CoroutineScope(Dispatchers.IO + job)

    private var networkCallback:
        ConnectivityManager.NetworkCallback? = null

    // جلوگیری از اجرای هم‌زمان drainQueue
    @Volatile
    private var draining = false

    override fun onCreate() {
        super.onCreate()

        Sender.init(this)

        registerNetworkCallback()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        drainQueueSafe()
    }

    // =====================================================
    // SAFE QUEUE DRAIN
    // =====================================================

    private fun drainQueueSafe() {

        // اگر قبلاً در حال اجرای صف هستیم،
        // اجرای جدید ایجاد نکن
        if (draining) return

        draining = true

        scope.launch {

            try {

                Sender.drainQueue()

            } catch (_: Exception) {

                // خطای صف نباید Service را متوقف کند

            } finally {

                draining = false
            }
        }
    }

    // =====================================================
    // NETWORK CALLBACK
    // =====================================================

    private fun registerNetworkCallback() {

        if (networkCallback != null) return

        val cm =
            getSystemService(
                CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val request =
            NetworkRequest.Builder()
                .addCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                .build()

        networkCallback =
            object :
                ConnectivityManager.NetworkCallback() {

                override fun onAvailable(
                    network: Network
                ) {

                    scope.launch {

                        delay(500)

                        drainQueueSafe()
                    }
                }
            }

        cm.registerNetworkCallback(
            request,
            networkCallback!!
        )
    }

    // =====================================================
    // NOTIFICATION RECEIVED
    // =====================================================

    override fun onNotificationPosted(
        sbn: StatusBarNotification?
    ) {

        super.onNotificationPosted(sbn)

        sbn ?: return

        val pkg =
            sbn.packageName

        // =================================================
        // FILTER
        // =================================================

        when {

            pkg in allowedPackages -> {
                // همه اعلان‌های این برنامه مجاز هستند
            }

            pkg == messagePackage -> {
                // همه اعلان‌های پیام مجاز هستند
            }

            pkg in callPackages -> {

                val title =
                    sbn.notification.extras
                        .getString(
                            Notification.EXTRA_TITLE
                        ) ?: ""

                // فقط تماس‌های بی‌پاسخ
                if (title != "تماس‌های بی‌پاسخ") {
                    return
                }
            }

            else -> {
                return
            }
        }

        // =================================================
        // EXTRACT DATA
        // =================================================

        val extras =
            sbn.notification.extras

        val title =
            extras.getString(
                Notification.EXTRA_TITLE
            ) ?: ""

        val text =
            extras.getCharSequence(
                Notification.EXTRA_TEXT
            )?.toString() ?: ""

        val time =
            sbn.postTime

        val appName =
            getAppName(pkg)

        // کلید یکتای اعلان
        val notificationKey =
            sbn.key

        // =================================================
        // SEND
        // =================================================

        scope.launch {

            try {

                Sender.send(
                    app = appName,
                    pkg = pkg,
                    title = title,
                    text = text,
                    time = time,
                    notificationKey = notificationKey
                )

            } catch (_: Exception) {

                // خطای ارسال نباید NotificationListener
                // را متوقف کند

            }
        }
    }

    // =====================================================
    // DESTROY
    // =====================================================

    override fun onDestroy() {

        super.onDestroy()

        try {

            val cm =
                getSystemService(
                    CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            networkCallback?.let {

                cm.unregisterNetworkCallback(it)
            }

        } catch (_: Exception) {
        }

        networkCallback = null

        job.cancel()
    }

    // =====================================================
    // APP NAME
    // =====================================================

    private fun getAppName(
        packageName: String
    ): String {

        return try {

            val info =
                packageManager.getApplicationInfo(
                    packageName,
                    0
                )

            packageManager
                .getApplicationLabel(info)
                .toString()

        } catch (_: Exception) {

            packageName
        }
    }
}
