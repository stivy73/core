package com.maxrave.media3.speech

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiPcmStreamTest {
    @Test
    fun concatenatesAudioFromMultipleEvents() {
        val events = """
            data: {"candidates":[{"content":{"parts":[{"inlineData":{"data":"AQI="}}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"ignored"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"inlineData":{"data":"AwQ="}}]}}]}

            data: [DONE]
        """.trimIndent()
        val output = ByteArrayOutputStream()
        CloudSongMeaningSpeech.GeminiPcmStream(events.reader().buffered()).use { it.copyTo(output) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
    }

    @Test
    fun emptyOrMalformedEventsDoNotProduceAudio() {
        val events = "data: not-json\n\ndata: {\"candidates\":[]}\n"
        CloudSongMeaningSpeech.GeminiPcmStream(events.reader().buffered()).use {
            assertEquals(-1, it.read())
        }
    }
}
