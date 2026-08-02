package com.example.notiforwarder

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import kotlinx.coroutines.*

class NotificationListener : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onCreate() {
        super.onCreate()
        Sender.init(this)
        Toast.makeText(this, "سرویس ساخته شد", Toast.LENGTH_SHORT).show()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Toast.makeText(this, "سرویس متصل شد", Toast.LENGTH_SHORT).show()
        scope.launch {
            Sender.drainQueue()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        // Toast برای نمایش دریافت اعلان
        Toast.makeText(this, "اعلان دریافت شد: ${sbn.packageName}", Toast.LENGTH_SHORT).show()

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val time = sbn.postTime

        val appName = getAppName(packageName)

        scope.launch {
            Sender.send(appName, packageName, title, text, time)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
