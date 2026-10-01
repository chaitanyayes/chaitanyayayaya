package com.chaitanya.hingeopener

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Calls the Anthropic Messages API over plain HTTPS. */
class ClaudeClient(private val prefs: Prefs) {

    class ApiException(message: String) : Exception(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun generate(images: List<File>): OpenerResult = withContext(Dispatchers.IO) {
        val key = prefs.apiKey
        if (key.isBlank()) throw ApiException("No API key. Open Hinge Opener → Settings and paste your Anthropic API key.")
        if (images.isEmpty()) throw ApiException("No captures yet. Tap the bubble to capture first.")

        val body = buildBody(images, prefs.style, prefs.runnerUps)
        var response = post(key, body, withFallback = true)
        // If the server-side fallback beta is ever rejected, retry once without it.
        if (response.code == 400 && response.body.contains("fallback", ignoreCase = true)) {
            body.remove("fallbacks")
            response = post(key, body, withFallback = false)
        }
        if (response.code !in 200..299) throw ApiException(describeError(response))

        val json = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw ApiException("Unexpected response from Anthropic. Try again.")
        }
        when (json.optString("stop_reason")) {
            "refusal" -> throw ApiException("Claude declined to answer for this profile. Try different screenshots.")
            "max_tokens" -> throw ApiException("Claude's answer got cut off. Try fewer screenshots.")
        }
        val content = json.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        ResultParser.parse(text)
    }

    private data class HttpResult(val code: Int, val body: String, val retryAfter: String?)

    private fun post(key: String, body: JSONObject, withFallback: Boolean): HttpResult {
        val builder = Request.Builder()
            .url(API_URL)
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
        if (withFallback) builder.header("anthropic-beta", FALLBACK_BETA)
        val request = builder.post(body.toString().toRequestBody(JSON)).build()
        return try {
            http.newCall(request).execute().use { r ->
                HttpResult(r.code, r.body?.string().orEmpty(), r.header("retry-after"))
            }
        } catch (e: UnknownHostException) {
            throw ApiException("No internet connection.")
        } catch (e: SocketTimeoutException) {
            throw ApiException("Request timed out. Check your connection and try again.")
        } catch (e: IOException) {
            throw ApiException("Network error: ${e.message ?: "connection failed"}.")
        }
    }

    private fun buildBody(images: List<File>, style: String, runnerUps: Int): JSONObject {
        val content = JSONArray()
        images.forEachIndexed { i, file ->
            content.put(JSONObject().put("type", "text").put("text", "Screenshot ${i + 1}:"))
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", b64),
                    ),
            )
        }
        content.put(
            JSONObject().put("type", "text").put("text", Prompts.userInstruction(images.size, runnerUps)),
        )

        return JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 16000)
            .put("system", Prompts.system(style))
            .put("fallbacks", "default")
            .put(
                "output_config",
                JSONObject()
                    .put("effort", "medium")
                    .put(
                        "format",
                        JSONObject()
                            .put("type", "json_schema")
                            .put("schema", JSONObject(Prompts.OUTPUT_SCHEMA)),
                    ),
            )
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
    }

    private fun describeError(r: HttpResult): String {
        val apiMessage = runCatching {
            JSONObject(r.body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return when (r.code) {
            401 -> "Invalid API key. Check it in Settings."
            403 -> "This API key isn't allowed to use $MODEL. ${apiMessage}".trim()
            413 -> "Screenshots too large. Try fewer captures."
            429 -> "Rate limited by Anthropic" +
                (r.retryAfter?.let { " — try again in ${it}s." } ?: " — wait a minute and try again.")
            529 -> "Anthropic is overloaded right now. Try again in a moment."
            in 500..599 -> "Anthropic server error (${r.code}). Try again."
            else -> if (apiMessage.isNotBlank()) "API error (${r.code}): $apiMessage" else "API error (${r.code})."
        }
    }

    companion object {
        /** Latest Claude Sonnet. */
        const val MODEL = "claude-sonnet-5-5"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val JSON = "application/json".toMediaType()
    }
}
