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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var btnTest: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)

        // =====================================================
        // فعال‌سازی دسترسی اعلان
        // =====================================================

        val btnEnable = Button(this)
        btnEnable.text = "فعال‌سازی دسترسی اعلان"

        btnEnable.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                )
            )
        }

        layout.addView(btnEnable)

        // =====================================================
        // تست ارسال
        // =====================================================

        btnTest = Button(this)
        btnTest.text = "تست ارسال پیام"

        btnTest.setOnClickListener {

            // جلوگیری از تست‌های هم‌زمان
            btnTest.isEnabled = false
            btnTest.text = "در حال ارسال..."

            CoroutineScope(Dispatchers.IO).launch {

                val success = try {
                    Sender.testSend()
                } catch (_: Exception) {
                    false
                }

                runOnUiThread {

                    // فعال کردن دوباره دکمه
                    btnTest.isEnabled = true
                    btnTest.text = "تست ارسال پیام"

                    Toast.makeText(
                        this@MainActivity,
                        if (success) {
                            "پیام تست ارسال شد"
                        } else {
                            "ارسال تست ناموفق بود"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        layout.addView(btnTest)

        // =====================================================
        // نمایش لاگ‌ها
        // =====================================================

        val btnLogs = Button(this)
        btnLogs.text = "نمایش لاگ‌ها"

        btnLogs.setOnClickListener {

            CoroutineScope(Dispatchers.IO).launch {

                val logs = try {
                    Sender.readLogs()
                } catch (e: Exception) {
                    "خطا در خواندن لاگ‌ها: ${e.message}"
                }

                runOnUiThread {
                    showLogsDialog(logs)
                }
            }
        }

        layout.addView(btnLogs)

        setContentView(layout)
    }

    // =====================================================
    // نمایش لاگ
    // =====================================================

    private fun showLogsDialog(logs: String) {

        val scrollView = ScrollView(this)

        val textView = TextView(this)

        textView.text = logs
        textView.setTextIsSelectable(true)
        textView.setPadding(16, 16, 16, 16)

        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("لاگ‌ها")
            .setView(scrollView)

            .setPositiveButton("کپی") { _, _ ->

                val clipboard =
                    getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "logs",
                        logs
                    )
                )

                Toast.makeText(
                    this@MainActivity,
                    "لاگ‌ها کپی شد",
                    Toast.LENGTH_SHORT
                ).show()
            }

            .setNegativeButton(
                "بستن",
                null
            )

            .show()
    }
}
