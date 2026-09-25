package com.maxrave.media3.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put

/** Plays OpenAI speech as it arrives, with a cache shared by the phone player and Android Auto. */
class CloudSongMeaningSpeech(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    @Volatile private var generation = 0L
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var audioTrack: AudioTrack? = null

    fun stop() {
        generation++
        activeConnection?.disconnect()
        activeConnection = null
        audioTrack?.release()
        audioTrack = null
    }

    suspend fun speak(
        text: String,
        apiKey: String,
        beforePlayback: suspend () -> Unit = {},
        onStage: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val expectedGeneration = generation
        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest((MODEL + VOICE + INSTRUCTIONS + text).encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val target = File(cacheDirectory, "$digest.pcm")
        if (target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            onStage("audio_cache_hit")
            target.inputStream().use { input -> playPcm(input, expectedGeneration, beforePlayback, onStage) }
            return@withContext
        }

        currentCoroutineContext().ensureActive()
        check(expectedGeneration == generation)
        val connection = URL(SPEECH_URL).openConnection() as HttpURLConnection
        activeConnection = connection
        val temporary = File(cacheDirectory, "$digest-${SystemClock.elapsedRealtimeNanos()}.tmp")
        try {
            currentCoroutineContext().ensureActive()
            check(expectedGeneration == generation)
            onStage("audio_request_started")
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = buildJsonObject {
                put("model", MODEL)
                put("voice", VOICE)
                put("input", text)
                put("instructions", INSTRUCTIONS)
                put("response_format", "pcm")
            }.toString()
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            check(connection.responseCode in 200..299)
            onStage("audio_headers_ready")
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { cache ->
                    playPcm(input, expectedGeneration, beforePlayback, onStage, cache)
                }
            }
            currentCoroutineContext().ensureActive()
            check(expectedGeneration == generation)
            check(temporary.renameTo(target))
            scope.launch(Dispatchers.IO) { pruneCache(cacheDirectory) }
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
            temporary.delete()
        }
    }

    suspend fun speakGemini(
        text: String,
        apiKey: String,
        style: String,
        beforePlayback: suspend () -> Unit = {},
        onStage: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val expectedGeneration = generation
        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((GEMINI_MODEL + GEMINI_VOICE + style + text).encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val target = File(cacheDirectory, "$digest.pcm")
        if (target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            onStage("audio_cache_hit")
            target.inputStream().use { playPcm(it, expectedGeneration, beforePlayback, onStage) }
            return@withContext
        }
        currentCoroutineContext().ensureActive()
        check(expectedGeneration == generation)
        val connection = URL(GEMINI_URL).openConnection() as HttpURLConnection
        activeConnection = connection
        val temporary = File(cacheDirectory, "$digest-${SystemClock.elapsedRealtimeNanos()}.tmp")
        try {
            onStage("audio_request_started")
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", text)
                                putJsonObject("speech_metadata") { put("style", geminiStyle(style)) }
                            })
                        }
                    })
                }
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") { put("voice", GEMINI_VOICE) }
                    }
                }
            }.toString()
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            check(connection.responseCode in 200..299) { "Gemini TTS HTTP ${connection.responseCode}" }
            onStage("audio_headers_ready")
            connection.inputStream.bufferedReader().use { reader ->
                GeminiPcmStream(reader).use { input ->
                    FileOutputStream(temporary).use { cache ->
                        playPcm(input, expectedGeneration, beforePlayback, onStage, cache)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(expectedGeneration == generation)
            check(temporary.renameTo(target))
            scope.launch(Dispatchers.IO) { pruneCache(cacheDirectory) }
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun geminiStyle(style: String): String = when (style) {
        "dj" -> "Parla in italiano come un DJ radiofonico: energico, ritmato e coinvolgente, senza cantare."
        "empathetic" -> "Parla in italiano con calore ed empatia, ritmo naturale e lievi esitazioni conversazionali come ehm, senza cambiare il significato."
        else -> "Parla in italiano con tono professionale, chiaro e misurato."
    }

    /** Converts Gemini's SSE audio parts to the same raw PCM stream used by OpenAI playback. */
    internal class GeminiPcmStream(private val reader: BufferedReader) : InputStream() {
        private var chunk = ByteArray(0)
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (length == 0) return 0
            while (offset >= chunk.size) {
                val line = reader.readLine() ?: return -1
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") return -1
                chunk = parseGeminiAudio(payload) ?: continue
                offset = 0
            }
            val count = minOf(length, chunk.size - offset)
            chunk.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            return count
        }

        override fun close() = reader.close()
    }

    private suspend fun playPcm(
        input: InputStream,
        expectedGeneration: Long,
        beforePlayback: suspend () -> Unit,
        onStage: (String) -> Unit,
        cache: FileOutputStream? = null,
    ) {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0)
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setBufferSizeInBytes(maxOf(minBuffer, BUFFER_SIZE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        audioTrack = track
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            var totalBytes = 0L
            var pendingByte = -1
            var firstByte = true
            var firstSampleSubmitted = false
            while (true) {
                currentCoroutineContext().ensureActive()
                check(expectedGeneration == generation)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                currentCoroutineContext().ensureActive()
                check(expectedGeneration == generation)
                cache?.write(buffer, 0, count)
                totalBytes += count
                if (firstByte) {
                    onStage("first_audio_byte")
                    firstByte = false
                }

                var offset = 0
                if (pendingByte >= 0) {
                    if (!firstSampleSubmitted) startPlayback(track, expectedGeneration, beforePlayback)
                    val firstSample = byteArrayOf(pendingByte.toByte(), buffer[0])
                    check(track.write(firstSample, 0, firstSample.size, AudioTrack.WRITE_BLOCKING) == firstSample.size)
                    pendingByte = -1
                    offset = 1
                    if (!firstSampleSubmitted) {
                        currentCoroutineContext().ensureActive()
                        check(expectedGeneration == generation)
                        onStage("first_pcm_submitted")
                        firstSampleSubmitted = true
                    }
                }
                val pairedEnd = count - ((count - offset) % BYTES_PER_FRAME)
                if (offset < pairedEnd && !firstSampleSubmitted) startPlayback(track, expectedGeneration, beforePlayback)
                while (offset < pairedEnd) {
                    currentCoroutineContext().ensureActive()
                    check(expectedGeneration == generation)
                    val written = track.write(buffer, offset, pairedEnd - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0 && written % BYTES_PER_FRAME == 0) { "AudioTrack write failed: $written" }
                    offset += written
                }
                if (pairedEnd < count) pendingByte = buffer[count - 1].toInt() and 0xff
                if (!firstSampleSubmitted && pairedEnd > 0) {
                    currentCoroutineContext().ensureActive()
                    check(expectedGeneration == generation)
                    onStage("first_pcm_submitted")
                    firstSampleSubmitted = true
                }
            }
            check(totalBytes > 0L) { "Empty speech response" }
            check(pendingByte < 0) { "Incomplete PCM sample" }
            val totalFrames = totalBytes / BYTES_PER_FRAME
            while (expectedGeneration == generation && track.playbackHeadPosition.toLong() < totalFrames) {
                currentCoroutineContext().ensureActive()
                delay(20)
            }
            currentCoroutineContext().ensureActive()
            check(expectedGeneration == generation)
            onStage("speech_complete")
        } finally {
            if (audioTrack === track) {
                audioTrack = null
                track.release()
            }
        }
    }

    private suspend fun startPlayback(
        track: AudioTrack,
        expectedGeneration: Long,
        beforePlayback: suspend () -> Unit,
    ) {
        beforePlayback()
        currentCoroutineContext().ensureActive()
        check(expectedGeneration == generation)
        track.play()
    }

    private fun pruneCache(directory: File) {
        val files = directory.listFiles()?.filter { it.extension == "pcm" } ?: return
        var totalBytes = files.sumOf(File::length)
        files.sortedBy(File::lastModified).forEach { file ->
            val fileBytes = file.length()
            if (totalBytes > MAX_CACHE_BYTES && file.delete()) totalBytes -= fileBytes
        }
    }

    private companion object {
        fun parseGeminiAudio(payload: String): ByteArray? = runCatching {
            val parts = Json.parseToJsonElement(payload).jsonObject["candidates"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            val encoded = parts?.firstNotNullOfOrNull { part ->
                part.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
            } ?: return@runCatching null
            Base64.getDecoder().decode(encoded)
        }.getOrNull()
        const val SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        const val MODEL = "gpt-4o-mini-tts"
        const val VOICE = "marin"
        const val GEMINI_MODEL = "gemini-3.8-flash-tts"
        const val GEMINI_VOICE = "Kore"
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-tts:streamGenerateContent?alt=sse"
        const val INSTRUCTIONS = "Leggi in italiano con tono naturale, caldo e informativo."
        const val CACHE_DIRECTORY = "song_meaning_speech"
        const val SAMPLE_RATE = 24_000
        const val BUFFER_SIZE = 4_096
        const val BYTES_PER_FRAME = 2
        const val MAX_CACHE_BYTES = 100L * 1024 * 1024
    }
}
