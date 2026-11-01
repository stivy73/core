package org.simpmusic.aiservice

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class GeminiTextApiTest {
    @Test
    fun acceptsLegacyAndAuthorizationKeysWithoutRewritingThem() {
        for (key in listOf("AIza_test_placeholder", "AQ.synthetic.authorization-key")) {
            assertEquals(key, AiClient().apply { apiKey = key }.apiKey)
        }
    }

    @Test
    fun retainsSelectedModel() {
        assertEquals("false", buildGeminiTextRequest("gemini-3.5-flash-lite", "Test")["store"]?.jsonPrimitive?.content)
        assertEquals("gemini-3.5-flash-lite", buildGeminiTextRequest("gemini-3.5-flash-lite", "Test")["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun separatesInstructionsFromLyricsContext() {
        val request = buildGeminiTextRequest("gemini-3.8-flash", "Lyrics context", "Write in Italian")
        assertEquals("Write in Italian", request["system_instruction"]?.jsonPrimitive?.content)
        assertEquals("Lyrics context", request["input"]?.jsonPrimitive?.content)
    }

    @Test
    fun onlyReturnsCompletedModelTextAndExcludesThoughts() {
        val json =
            """{"status":"completed","steps":[{"type":"model_output","content":[""" +
                """{"type":"thought","text":"private"},{"type":"text","text":"Spiegazione italiana."}]}]}"""
        assertEquals("Spiegazione italiana.", parseGeminiTextResponse(json))
        assertFails { parseGeminiTextResponse("""{"status":"in_progress","steps":[]}""") }
        assertFails { parseGeminiTextResponse("""{"status":"completed","steps":[]}""") }
    }

    @Test
    fun retainsOnlyAllowlistedGoogleErrorReasons() {
        val body = """{"error":{"message":"private response","details":[{"reason":"API_KEY_INVALID"}]}}"""
        val error = parseGeminiTextError(400, body)
        assertEquals("invalid_ai_api_key", songMeaningFailureCode(error))
        assertFalse(error.message.orEmpty().contains("private response"))
        assertNull(parseGeminiTextError(400, """{"error":{"details":[{"reason":"private unknown value"}]}}""").reason)
    }

    @Test
    fun distinguishesQuotaFromAuthenticationWithoutEchoingResponse() {
        assertEquals("ai_quota_exceeded", songMeaningFailureCode(GeminiTextApiException(429)))
        assertEquals("invalid_ai_api_key", songMeaningFailureCode(GeminiTextApiException(401)))
        assertEquals("ai_permission_denied", songMeaningFailureCode(GeminiTextApiException(403)))
        assertEquals("ai_quota_exceeded", songMeaningFailureCode(Exception("HTTP 429 Too Many Requests")))
        assertEquals("Unable to explain this song", songMeaningFailureCode(GeminiTextApiException(500)))
    }
}