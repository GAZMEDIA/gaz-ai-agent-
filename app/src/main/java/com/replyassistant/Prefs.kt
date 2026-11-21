package com.replyassistant

import android.content.Context

object Prefs {
    private const val FILE = "reply_assistant"

    // Defaults use Groq's free tier (OpenAI-compatible API). Any OpenAI-compatible
    // provider works: just change the URL, model and key in the app.
    const val DEFAULT_URL = "https://api.groq.com/openai/v1/chat/completions"
    const val DEFAULT_MODEL = "llama-3.3-70b-versatile"

    fun apiKey(c: Context) = sp(c).getString("api_key", "") ?: ""
    fun apiUrl(c: Context) = sp(c).getString("api_url", DEFAULT_URL)?.ifBlank { DEFAULT_URL } ?: DEFAULT_URL
    fun model(c: Context) = sp(c).getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
    fun aboutMe(c: Context) = sp(c).getString("about_me", "") ?: ""
    fun paused(c: Context) = sp(c).getBoolean("paused", false)

    fun save(c: Context, apiKey: String, apiUrl: String, model: String, aboutMe: String, paused: Boolean) {
        sp(c).edit()
            .putString("api_key", apiKey.trim())
            .putString("api_url", apiUrl.trim())
            .putString("model", model.trim())
            .putString("about_me", aboutMe.trim())
            .putBoolean("paused", paused)
            .apply()
    }

    fun saveModel(c: Context, model: String) {
        sp(c).edit().putString("model", model).apply()
    }

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
