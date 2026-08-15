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

    private val messagePackage = "com.samsung.android.messaging"

    // برنامه‌های مجاز بدون فیلتر (تمام اعلان‌ها ارسال شوند)
    private val allowedPackages = setOf(
        "ir.nasim"
    )

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Sender.init(this)
        registerNetworkCallback()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch {
            Sender.drainQueue()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    delay(500)
                    Sender.drainQueue()
                }
            }
        }

        cm.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val pkg = sbn.packageName

        when {
            pkg in allowedPackages -> {
                // برنامه‌های مجاز بدون فیلتر (مثل ir.nasim)
            }
            pkg == messagePackage -> {
                // همهٔ اعلان‌های پیام مجاز هستند
            }
            pkg in callPackages -> {
                val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
                if (title != "تماس‌های بی‌پاسخ") return   // فقط تماس بی‌پاسخ ارسال شود
            }
            else -> return
        }

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val time = sbn.postTime

        val appName = getAppName(pkg)

        // ارسال با کلید یکتا
        scope.launch {
            Sender.send(
                app = appName,
                pkg = pkg,
                title = title,
                text = text,
                time = time,
                notificationKey = sbn.key   // <-- کلید یکتای اعلان
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        job.cancel()
    }

    private fun getAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
