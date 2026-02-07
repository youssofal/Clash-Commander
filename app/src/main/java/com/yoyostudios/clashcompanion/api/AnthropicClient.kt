package com.yoyostudios.clashcompanion.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yoyostudios.clashcompanion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Simple wrapper for the Anthropic Messages API.
 * Uses OkHttp + Gson. All calls run on Dispatchers.IO.
 */
object AnthropicClient {

    private const val TAG = "ClashCompanion"
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // Opus can take 30-60s
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /**
     * Send a message to Claude and return the response text.
     *
     * @param model Model ID (e.g. "claude-opus-4-6", "claude-haiku-4-5-20251001")
     * @param systemPrompt System prompt text
     * @param userMessage User message text
     * @param maxTokens Maximum tokens in response
     * @return Response text from Claude
     * @throws IllegalStateException if API key is not configured
     * @throws RuntimeException on API error
     */
    suspend fun chat(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 4096
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY
        if (apiKey.isBlank()) {
            throw IllegalStateException("ANTHROPIC_API_KEY not set in local.properties")
        }

        // Build request body
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", maxTokens)
            addProperty("system", systemPrompt)
            add("messages", Gson().toJsonTree(listOf(
                mapOf("role" to "user", "content" to userMessage)
            )))
        }

        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(requestBody)
            .build()

        Log.i(TAG, "API: Calling $model (max_tokens=$maxTokens)...")
        val startTime = System.currentTimeMillis()

        val response = client.newCall(request).execute()
        val elapsed = System.currentTimeMillis() - startTime
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            Log.e(TAG, "API: Error ${response.code}: $responseBody")
            throw RuntimeException("Anthropic API error ${response.code}: ${responseBody.take(200)}")
        }

        Log.i(TAG, "API: Response from $model in ${elapsed}ms (${responseBody.length} chars)")

        // Parse response: {"content": [{"type": "text", "text": "..."}], ...}
        try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("content")
            if (content != null && content.size() > 0) {
                val firstBlock = content[0].asJsonObject
                val text = firstBlock.get("text")?.asString ?: ""
                return@withContext text
            }
            throw RuntimeException("Empty content in API response")
        } catch (e: Exception) {
            if (e is RuntimeException) throw e
            Log.e(TAG, "API: Failed to parse response: ${e.message}")
            throw RuntimeException("Failed to parse Anthropic response: ${e.message}")
        }
    }
}
