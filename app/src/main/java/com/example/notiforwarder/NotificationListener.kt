package com.example.notiforwarder

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*

class NotificationListener : NotificationListenerService() {

    // بسته‌های مربوط به تماس (باید عنوان "تماس‌های بی‌پاسخ" داشته باشند)
    private val callPackages = setOf(
        "com.samsung.android.dialer",
        "com.android.phone",
        "com.samsung.android.incallui"
    )

    // بسته پیام‌ها (همه اعلان‌هایش مجاز است)
    private val messagePackage = "com.samsung.android.messaging"

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val pkg = sbn.packageName

        when {
            pkg == messagePackage -> {
                // پیام‌ها: همه اعلان‌ها ارسال شود
            }
            pkg in callPackages -> {
                // تماس: فقط اگر عنوان دقیقاً "تماس‌های بی‌پاسخ" باشد
                val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
                if (title != "تماس‌های بی‌پاسخ") return
            }
            else -> return  // سایر برنامه‌ها مجاز نیستند
        }

        val packageName = pkg
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
