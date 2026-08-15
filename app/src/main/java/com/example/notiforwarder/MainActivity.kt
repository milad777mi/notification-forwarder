package com.example.notiforwarder.mili

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)

        val btnEnable = Button(this)
        btnEnable.text = "فعال‌سازی دسترسی اعلان"
        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        layout.addView(btnEnable)

        val btnTest = Button(this)
        btnTest.text = "تست ارسال پیام"
        btnTest.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val success = Sender.testSend()
                runOnUiThread {
                    Toast.makeText(this, if (success) "پیام تست ارسال شد" else "ارسال تست ناموفق بود", Toast.LENGTH_SHORT).show()
                }
            }
        }
        layout.addView(btnTest)

        val btnLogs = Button(this)
        btnLogs.text = "نمایش لاگ‌ها"
        btnLogs.setOnClickListener {
            val logs = Sender.readLogs()
            showLogsDialog(logs)
        }
        layout.addView(btnLogs)

        setContentView(layout)
    }

    private fun showLogsDialog(logs: String) {
        val scrollView = ScrollView(this)
        val textView = TextView(this)
        textView.text = logs
        textView.setTextIsSelectable(true)
        textView.setPadding(16, 16, 16, 16)
        scrollView.addView(textView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("لاگ‌ها")
            .setView(scrollView)
            .setPositiveButton("کپی") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("logs", logs))
                Toast.makeText(this, "لاگ‌ها کپی شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("بستن", null)
            .create()
        dialog.show()
    }
}e.getTimeZone("Asia/Tehran")
            sdf.format(date)
        } catch (e: Exception) {
            time.toString()
        }
    }
}
