package com.replyassistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Simple settings screen: paste your free key, describe how you text, turn the service on. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(36), px(20), px(24))
        }
        fun heading(t: String) = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, px(18), 0, px(4))
        }

        col.addView(TextView(this).apply { text = "Reply Assistant"; textSize = 26f; setTextColor(Color.BLACK) })
        col.addView(TextView(this).apply {
            text = "Open any chat in WhatsApp, Messenger, Instagram, Telegram, Tinder, Badoo and more. " +
                "A suggested reply appears at the top. Nothing is sent unless you tap Send."
            textSize = 14f; setPadding(0, px(6), 0, 0)
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, px(16), 0, 0) }
        col.addView(status)
        col.addView(Button(this).apply {
            text = "1. Turn on Reply Assistant (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        col.addView(heading("2. Your free Groq API key (from console.groq.com → API Keys)"))
        val key = EditText(this).apply {
            hint = "gsk_..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
        }
        col.addView(key)

        col.addView(heading("3. About you and how you text (optional)"))
        val about = EditText(this).apply {
            hint = "e.g. I'm Tunde, 27, Lagos. I text short and playful, mix English and Pidgin, use few emojis."
            minLines = 3
            setText(Prefs.aboutMe(this@MainActivity))
        }
        col.addView(about)

        col.addView(heading("Advanced: AI provider (leave as is for Groq)"))
        val url = EditText(this).apply { setText(Prefs.apiUrl(this@MainActivity)); textSize = 13f }
        val model = EditText(this).apply { setText(Prefs.model(this@MainActivity)); textSize = 13f }
        col.addView(url); col.addView(model)

        val paused = CheckBox(this).apply {
            text = "Pause suggestions"; isChecked = Prefs.paused(this@MainActivity)
        }
        col.addView(paused)

        col.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                Prefs.save(
                    this@MainActivity, key.text.toString(), url.text.toString(),
                    model.text.toString(), about.text.toString(), paused.isChecked,
                )
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        status.text = if (enabled) "✅ Service is ON" else "❌ Service is OFF — tap the button below"
    }
}
