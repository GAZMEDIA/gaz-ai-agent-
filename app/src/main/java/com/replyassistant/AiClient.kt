package com.replyassistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for any OpenAI-compatible chat API (default: Groq free tier).
 * No extra libraries. Must be called from a background thread.
 */
object AiClient {

    fun suggestReply(
        url: String,
        apiKey: String,
        model: String,
        appName: String,
        chatTitle: String,
        aboutMe: String,
        transcript: String,
        notThis: String?,
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

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 200)
            .put("temperature", 0.8)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", userContent))
            )

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429) throw RuntimeException("Free limit reached for now. Try again in a minute.")
            if (code !in 200..299) throw RuntimeException("AI error $code: ${text.take(160)}")

            return JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim().trim('"')
        } finally {
            conn.disconnect()
        }
    }
}
