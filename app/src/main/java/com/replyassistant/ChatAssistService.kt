package com.replyassistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Reads the chat that is open on screen, asks the AI for a reply and shows it in a
 * floating panel. Nothing is sent unless the user taps "Send".
 */
class ChatAssistService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var suggestionView: TextView? = null

    private var lastSignature = ""      // last conversation state we handled
    private var currentSuggestion = ""
    private var currentTranscript = ""
    private var currentApp = ""
    private var currentTitle = ""
    private var requestId = 0

    private val analyzeRunnable = Runnable { analyzeScreen() }

    private data class Line(val me: Boolean, val text: String)

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || Prefs.paused(this)) return
        // Wait until the screen stops changing for a moment, then read it once.
        main.removeCallbacks(analyzeRunnable)
        main.postDelayed(analyzeRunnable, 700)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hidePanel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ reading the chat

    private fun analyzeScreen() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return

        val input = findInput(root)
        if (input == null) {            // not a chat screen (chat list, profile, etc.)
            hidePanel(); lastSignature = ""; return
        }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val inputRect = Rect().also { input.getBoundsInScreen(it) }

        val title = findTitle(root, screenH)
        val lines = mutableListOf<Line>()
        collectMessages(root, screenW, (screenH * 0.11).toInt(), inputRect.top, lines)
        if (lines.isEmpty()) return

        val recent = lines.takeLast(20)
        // Only suggest when the other person spoke last.
        if (recent.last().me) { hidePanel(); return }

        val signature = pkg + "|" + title + "|" + recent.takeLast(4).joinToString("§") { it.text }
        if (signature == lastSignature) return
        lastSignature = signature

        currentApp = appName(pkg)
        currentTitle = title
        currentTranscript = recent.joinToString("\n") { (if (it.me) "Me: " else "Them: ") + it.text }
        requestSuggestion(null)
    }

    private fun findInput(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findInput(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findTitle(root: AccessibilityNodeInfo, screenH: Int): String {
        var best = ""
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || best.isNotEmpty()) return
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.length in 2..40 && !n.isEditable) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.top in 1 until (screenH * 0.12).toInt() && !isNoise(t)) { best = t; return }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun collectMessages(
        node: AccessibilityNodeInfo?, screenW: Int, top: Int, bottom: Int, out: MutableList<Line>,
    ) {
        if (node == null || !node.isVisibleToUser) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && !node.isEditable && node.childCount == 0) {
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.top >= top && r.bottom <= bottom && !isNoise(text)) {
                // Bubbles on the right are the user's own messages.
                val me = r.centerX() > screenW / 2 && r.left > screenW * 0.2
                out.add(Line(me, text))
            }
        }
        for (i in 0 until node.childCount) collectMessages(node.getChild(i), screenW, top, bottom, out)
    }

    private val timeRegex = Regex("""^\d{1,2}[:.]\d{2}(\s?[aApP]\.?[mM]\.?)?$""")
    private val noiseWords = setOf(
        "today", "yesterday", "seen", "delivered", "read", "sent", "online", "typing…", "typing...",
        "send", "message", "type a message", "edited", "you", "new messages", "unread messages",
    )

    private fun isNoise(t: String): Boolean {
        val l = t.lowercase()
        return t.length < 2 || timeRegex.matches(t) || l in noiseWords ||
            l.startsWith("last seen") || l.matches(Regex("""^\d+ unread messages?$"""))
    }

    private fun appName(pkg: String) = when {
        pkg.startsWith("com.whatsapp") -> "WhatsApp"
        pkg.startsWith("com.facebook") -> "Facebook Messenger"
        pkg == "com.instagram.android" -> "Instagram"
        pkg == "org.telegram.messenger" -> "Telegram"
        pkg == "com.tinder" -> "Tinder"
        pkg == "com.badoo.mobile" -> "Badoo"
        pkg == "com.bumble.app" -> "Bumble"
        pkg == "com.hinge.app" -> "Hinge"
        pkg == "com.snapchat.android" -> "Snapchat"
        else -> "messaging"
    }

    // ------------------------------------------------------------------ AI

    private fun requestSuggestion(notThis: String?) {
        val key = Prefs.apiKey(this)
        showPanel()
        if (key.isBlank()) { setSuggestion("Open Reply Assistant and paste your free Groq key first."); return }
        setSuggestion("Thinking…")
        currentSuggestion = ""
        val id = ++requestId
        val url = Prefs.apiUrl(this); val model = Prefs.model(this); val about = Prefs.aboutMe(this)
        val app = currentApp; val title = currentTitle; val transcript = currentTranscript
        thread {
            val result = try {
                Result.success(AiClient.suggestReply(url, key, model, app, title, about, transcript, notThis))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (id != requestId) return@post // a newer request replaced this one
                result.onSuccess { currentSuggestion = it; setSuggestion(it) }
                result.onFailure { setSuggestion("⚠ ${it.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ sending

    /** Puts the text in the chat's input box; if [send] is true, also taps the app's Send button. */
    private fun insertText(text: String, send: Boolean) {
        val root = rootInActiveWindow ?: return
        val input = findInput(root) ?: run { toast("Couldn't find the message box"); return }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!send) { hidePanel(); return }

        main.postDelayed({
            val r2 = rootInActiveWindow
            val sendBtn = r2?.let { findSendButton(it) }
            if (sendBtn != null && clickNode(sendBtn)) {
                hidePanel() // a new suggestion appears when they reply
            } else {
                toast("Reply is in the box — tap the app's Send button")
                hidePanel()
            }
        }, 350)
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found != null) return
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
            val txt = n.text?.toString()?.lowercase().orEmpty()
            val looksLikeSend = id.endsWith("/send") || id.contains("send_button") || id.contains("send_btn") ||
                id.endsWith(":id/send") || desc == "send" || desc.startsWith("send ") ||
                desc == "envoyer" || txt == "send"
            if (looksLikeSend && n.isVisibleToUser && !n.isEditable) { found = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    // ------------------------------------------------------------------ floating panel

    private fun showPanel() {
        if (panel != null) return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(8))
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(Color.parseColor("#F21E1F24"))
            }
            elevation = px(8).toFloat()
        }
        val label = TextView(this).apply {
            text = "Suggested reply"
            setTextColor(Color.parseColor("#9AA0A6")); textSize = 11f
        }
        val sug = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(0, px(4), 0, px(6))
            maxLines = 6
        }
        suggestionView = sug

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(t: String, color: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 13f
            setTextColor(Color.parseColor(color))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0; setPadding(px(10), 0, px(10), 0)
            setOnClickListener { onClick() }
        }
        row.addView(btn("✕", "#9AA0A6") { hidePanel() })
        row.addView(btn("Another", "#9AA0A6") { requestSuggestion(currentSuggestion.ifBlank { null }) })
        row.addView(btn("Edit", "#8AB4F8") {
            if (currentSuggestion.isNotBlank()) insertText(currentSuggestion, send = false)
        })
        row.addView(btn("Send", "#25D366") {
            if (currentSuggestion.isNotBlank()) insertText(currentSuggestion, send = true)
        })

        box.addView(label); box.addView(sug); box.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (resources.displayMetrics.heightPixels * 0.09).toInt()
            horizontalMargin = 0.03f
        }
        try {
            wm.addView(box, lp); panel = box
        } catch (e: Exception) {
            suggestionView = null
        }
    }

    private fun setSuggestion(t: String) { suggestionView?.text = t }

    private fun hidePanel() {
        requestId++ // cancel any pending result
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; suggestionView = null
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
