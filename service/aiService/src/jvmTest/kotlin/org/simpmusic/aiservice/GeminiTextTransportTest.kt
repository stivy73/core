package org.simpmusic.aiservice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GeminiTextTransportTest {
    @Test
    fun sendsBothKeyFormatsThroughGoogleHeaderUnmodified() =
        runBlocking {
            for (key in listOf("AIza_synthetic_test_key", "AQ.synthetic.authorization.key", "AQ." + "synthetic_auth_key.".repeat(18))) {
                val client =
                    HttpClient(
                        MockEngine { request ->
                            assertEquals("/v1beta/interactions", request.url.encodedPath)
                            assertEquals(key, request.headers["x-goog-api-key"])
                            assertNull(request.headers["Authorization"])
                            respond(
                                """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Interpretazione italiana."}]}]}""",
                                HttpStatusCode.OK,
                                headersOf("Content-Type", "application/json"),
                            )
                        },
                    )
                assertEquals("Interpretazione italiana.", generateGeminiText(" $key ", "gemini-3.5-flash-lite", "Test", client))
            }
        }

    @Test
    fun quotaFailureNeverEchoesResponseOrCredentials() =
        runBlocking {
            val client = HttpClient(MockEngine { respond("response with private request data", HttpStatusCode.TooManyRequests) })
            val error = assertFailsWith<GeminiTextApiException> { generateGeminiText("AQ.synthetic", "gemini-3.8-flash", "Test", client) }
            assertEquals(429, error.status)
            assertEquals("Google AI HTTP 429", error.message)
            assertEquals("ai_quota_exceeded", songMeaningFailureCode(error))
        }
}