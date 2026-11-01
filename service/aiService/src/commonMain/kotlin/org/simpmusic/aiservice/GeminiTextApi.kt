package org.simpmusic.aiservice

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.*

/** Uses Google's own authentication, without assuming a prefix or length for API keys. */
private fun geminiHttpClient() =
    HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 45_000
            requestTimeoutMillis = 50_000
        }
    }

internal suspend fun generateGeminiText(
    key: String,
    model: String,
    prompt: String,
    httpClient: HttpClient? = null,
    systemInstruction: String? = null,
): String {
    require(key.isNotBlank()) { "AI API key is missing" }
    return (httpClient ?: geminiHttpClient()).use { client ->
        val response =
            client.post("https://generativelanguage.googleapis.com/v1beta/interactions") {
                header("x-goog-api-key", key.trim())
                contentType(ContentType.Application.Json)
                setBody(buildGeminiTextRequest(model, prompt, systemInstruction).toString())
            }
        // Never expose Google's free-form response (it can contain request identifiers or URLs).
        if (response.status.value !in 200..299) throw parseGeminiTextError(response.status.value, response.bodyAsText())
        parseGeminiTextResponse(response.bodyAsText())
    }
}

internal fun buildGeminiTextRequest(
    model: String,
    prompt: String,
    systemInstruction: String? = null,
): JsonObject =
    buildJsonObject {
        put("store", false)
        put("model", model)
        put("input", prompt)
        systemInstruction?.let { put("system_instruction", it) }
    }

internal fun parseGeminiTextResponse(body: String): String {
    val result = Json.parseToJsonElement(body).jsonObject
    if (result["status"]?.jsonPrimitive?.content != "completed") throw GeminiTextApiException(502)
    return result["steps"]
        ?.jsonArray
        .orEmpty()
        .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "model_output" }
        .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
        .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
        .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        .joinToString("")
        .trim()
        .takeIf { it.isNotEmpty() }
        ?: throw GeminiTextApiException(502)
}

class GeminiTextApiException(
    val status: Int,
    val reason: String? = null,
) : Exception("Google AI HTTP $status" + (reason?.let { " $it" } ?: ""))

internal fun parseGeminiTextError(
    status: Int,
    body: String,
): GeminiTextApiException {
    val allowed = setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_SERVICE_BLOCKED", "SERVICE_DISABLED", "BILLING_DISABLED")
    val reason =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["error"]
                ?.jsonObject
                ?.get("details")
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonObject["reason"]?.jsonPrimitive?.contentOrNull }
                .firstOrNull { it in allowed }
        }.getOrNull()
    return GeminiTextApiException(status, reason)
}

/** Stable, non-secret error codes shared by the app and the car player. */
fun songMeaningFailureCode(error: Throwable): String {
    val status = (error as? GeminiTextApiException)?.status
    val message = error.message.orEmpty()
    return when {
        status == 429 || Regex("""\b429\b""").containsMatchIn(message) -> "ai_quota_exceeded"
        (error as? GeminiTextApiException)?.reason in setOf("API_KEY_INVALID", "API_KEY_EXPIRED") -> "invalid_ai_api_key"
        status == 403 -> "ai_permission_denied"
        status == 401 || message.contains("Please pass a valid API key", ignoreCase = true) -> "invalid_ai_api_key"
        else -> "Unable to explain this song"
    }
}