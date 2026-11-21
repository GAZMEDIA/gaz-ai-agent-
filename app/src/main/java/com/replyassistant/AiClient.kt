package com.replyassistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for any OpenAI-compatible chat API (default: Groq free tier).
 * No extra libraries. Must be called from a background thread.
 * If the chosen model is retired or not allowed, it picks a working one by itself
 * and reports it through [onModelChanged] so the app remembers it.
 */
object AiClient {

    // Tried in this order when the saved model stops working.
    private val PREFERRED = listOf(
        "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "openai/gpt-oss-120b",
        "llama-3.1-8b-instant", "qwen/qwen3.8-27b",
    )

    private class ModelMissing : RuntimeException()

    fun suggestReply(
        url: String,
        apiKey: String,
        model: String,
        appName: String,
        chatTitle: String,
        aboutMe: String,
        transcript: String,
        notThis: String?,
        onModelChanged: (String) -> Unit,
    ): String {
        val system = buildString {
            append("You help the user reply in their $appName chat")
            if (chatTitle.isNotBlank()) append(" with \"$chatTitle\"")
            append(". You get the recent conversation as read from the phone screen: ")
            append("'Me' is the user, 'Them' is the other person. The screen text may include stray labels, ")
            append("buttons or timestamps; ignore those.\n")
            append("Write the ONE next message the user should send.\n")
            append("- Match the chat's language (English, Pidgin, Yoruba, Igbo, Hausa, French...), tone, slang and typical length.\n")
            append("- Sound like a real person texting from their phone, not an assistant. No hashtags, no quotes around the message.\n")
            append("- Don't invent facts about the user, make commitments, or share money, bank, address or other personal details ")
            append("unless they are already in the chat or in the user's profile.\n")
            append("- Output only the message text, nothing else.\n")
            if (aboutMe.isNotBlank()) append("\nAbout the user and how they text:\n$aboutMe\n")
        }
        var userContent = "Conversation:\n$transcript"
        if (notThis != null) userContent += "\n\nGive a different reply from this one: \"$notThis\""

        return try {
            chat(url, apiKey, model, system, userContent)
        } catch (e: ModelMissing) {
            val available = listModels(url, apiKey)
            val pick = PREFERRED.firstOrNull { it in available && it != model }
                ?: available.firstOrNull { it != model && !it.contains("whisper") && !it.contains("guard") && !it.contains("tts") && !it.contains("orpheus") }
                ?: throw RuntimeException("No usable AI model found on this key. Check console.groq.com.")
            val reply = chat(url, apiKey, pick, system, userContent)
            onModelChanged(pick)
            reply
        }
    }

    private fun chat(url: String, apiKey: String, model: String, system: String, userContent: String): String {
        val reasoning = model.startsWith("openai/gpt-oss")
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", if (reasoning) 600 else 200)
            .put("temperature", 0.8)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", userContent))
            )
        if (reasoning) body.put("reasoning_effort", "low")

        val (code, text) = request(url, "POST", apiKey, body.toString())
        if (code == 404 || text.contains("model_not_found") || text.contains("model_decommissioned")) throw ModelMissing()
        if (code == 429) throw RuntimeException("Free limit reached for now. Try again in a minute.")
        if (code == 401) throw RuntimeException("Your Groq key isn't valid. Paste a new key in Reply Assistant.")
        if (code !in 200..299) throw RuntimeException("AI error $code: ${text.take(160)}")

        return JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim().trim('"')
    }

    private fun listModels(chatUrl: String, apiKey: String): List<String> {
        val modelsUrl = chatUrl.substringBefore("/chat/completions") + "/models"
        val (code, text) = request(modelsUrl, "GET", apiKey, null)
        if (code !in 200..299) return emptyList()
        val data = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
    }

    private fun request(url: String, method: String, apiKey: String, body: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) doOutput = true
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
