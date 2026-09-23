package com.maxrave.media3.carapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.media3.common.Player
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SongMeaningCarSpeech(
    private val context: Context,
    private val player: Player,
    private val repository: LyricsCanvasRepository,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) {
    private var requestJob: Job? = null
    private var textToSpeech: TextToSpeech? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    private var resumePlayback = false
    @Volatile private var generation = 0L
    @Volatile private var startedAt = 0L

    fun start(onFinished: () -> Unit) {
        stop(resume = false)
        val current = player.currentMediaItem ?: run {
            onFinished()
            return
        }
        val expectedMediaId = current.mediaId
        val title = current.mediaMetadata.title?.toString().orEmpty()
        val artist = current.mediaMetadata.artist?.toString().orEmpty()
        startedAt = SystemClock.elapsedRealtime()
        resumePlayback = player.isPlaying
        player.pause()
        val currentGeneration = ++generation
        logTiming("tap", currentGeneration)
        requestJob =
            scope.launch {
                val lyrics =
                    repository
                        .getSavedLyrics(expectedMediaId)
                        .first()
                        ?.lines
                        ?.joinToString("\n") { it.words }
                        ?.takeIf(String::isNotBlank)
                logTiming("lyrics_ready", currentGeneration)
                val result = repository.getSongExplanation(title, artist, lyrics).first()
                logTiming("explanation_ready", currentGeneration)
                if (currentGeneration != generation || player.currentMediaItem?.mediaId != expectedMediaId) return@launch
                val explanation = (result as? Resource.Success)?.data.orEmpty()
                if (explanation.isBlank()) {
                    finish(onFinished)
                    return@launch
                }
                when (dataStoreManager.songMeaningTtsProvider.first()) {
                    DataStoreManager.SONG_MEANING_TTS_OPENAI -> speakWithOpenAi(explanation, currentGeneration, onFinished)
                    else -> speakWithAndroid(explanation, currentGeneration, onFinished)
                }
            }
    }

    fun stop(resume: Boolean = true) {
        generation++
        requestJob?.cancel()
        requestJob = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        activeConnection?.disconnect()
        activeConnection = null
        audioTrack?.release()
        audioTrack = null
        if (resume) resumeIfNeeded() else resumePlayback = false
    }

    private fun speakWithAndroid(text: String, expectedGeneration: Long, onFinished: () -> Unit) {
        logTiming("android_tts_init_started", expectedGeneration)
        textToSpeech =
            TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS || expectedGeneration != generation) {
                    finish(onFinished)
                    return@TextToSpeech
                }
                val tts = textToSpeech ?: return@TextToSpeech
                logTiming("android_tts_ready", expectedGeneration)
                val language = tts.setLanguage(Locale.ITALIAN)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    finish(onFinished)
                    return@TextToSpeech
                }
                val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1))
                val prefix = "car-song-meaning-$expectedGeneration"
                val finalId = "$prefix-${chunks.lastIndex}"
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId == "$prefix-0") logTiming("android_tts_started", expectedGeneration)
                        }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == finalId) scope.launch { finish(onFinished) }
                        }
                        @Deprecated("Deprecated in Android")
                        override fun onError(utteranceId: String?) = scope.launch { finish(onFinished) }.let { Unit }
                    },
                )
                chunks.forEachIndexed { index, chunk ->
                    tts.speak(
                        chunk,
                        if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        Bundle(),
                        "$prefix-$index",
                    )
                }
            }
    }

    private suspend fun speakWithOpenAi(text: String, expectedGeneration: Long, onFinished: () -> Unit) {
        if (dataStoreManager.aiProvider.first() != DataStoreManager.AI_PROVIDER_OPENAI) {
            finish(onFinished)
            return
        }
        val key = dataStoreManager.aiApiKey.first()
        if (key.isBlank()) {
            finish(onFinished)
            return
        }
        runCatching { streamOpenAiSpeech(text, key, expectedGeneration) }
            .onFailure { error ->
                if (expectedGeneration == generation) Log.w(TAG, "speech_failed elapsedMs=${elapsedMs()}", error)
            }
        if (expectedGeneration == generation) finish(onFinished)
    }

    private suspend fun streamOpenAiSpeech(text: String, key: String, expectedGeneration: Long) =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "song_meaning_speech").apply { mkdirs() }
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest((OPENAI_TTS_MODEL + OPENAI_VOICE + OPENAI_INSTRUCTIONS + text).encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val target = File(directory, "$digest.pcm")
            if (target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                logTiming("audio_cache_hit", expectedGeneration)
                target.inputStream().use { playPcm(it, expectedGeneration) }
                return@withContext
            }
            currentCoroutineContext().ensureActive()
            check(expectedGeneration == generation)
            val connection = URL(OPENAI_SPEECH_URL).openConnection() as HttpURLConnection
            activeConnection = connection
            val temporary = File(directory, "$digest-$expectedGeneration.tmp")
            try {
                logTiming("audio_request_started", expectedGeneration)
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = buildJsonObject {
                    put("model", OPENAI_TTS_MODEL)
                    put("voice", OPENAI_VOICE)
                    put("input", text)
                    put("instructions", OPENAI_INSTRUCTIONS)
                    put("response_format", "pcm")
                }.toString()
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
                check(connection.responseCode in 200..299)
                logTiming("audio_headers_ready", expectedGeneration)
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { cache ->
                        playPcm(input, expectedGeneration, cache)
                    }
                }
                currentCoroutineContext().ensureActive()
                check(expectedGeneration == generation)
                check(temporary.renameTo(target))
                scope.launch(Dispatchers.IO) { pruneSpeechCache(directory) }
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
                temporary.delete()
            }
        }

    private suspend fun playPcm(
        input: java.io.InputStream,
        expectedGeneration: Long,
        cache: FileOutputStream? = null,
    ) {
        val minBuffer = AudioTrack.getMinBufferSize(PCM_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                        .setSampleRate(PCM_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setBufferSizeInBytes(maxOf(minBuffer, PCM_BUFFER_SIZE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        audioTrack = track
        var firstByte = true
        var firstSampleSubmitted = false
        val buffer = ByteArray(PCM_BUFFER_SIZE)
        var totalBytes = 0L
        var pendingByte = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            check(expectedGeneration == generation)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            cache?.write(buffer, 0, count)
            totalBytes += count
            if (firstByte) {
                logTiming("first_audio_byte", expectedGeneration)
                track.play()
                firstByte = false
            }
            var offset = 0
            if (pendingByte >= 0) {
                val firstSample = byteArrayOf(pendingByte.toByte(), buffer[0])
                check(track.write(firstSample, 0, firstSample.size, AudioTrack.WRITE_BLOCKING) == firstSample.size)
                pendingByte = -1
                offset = 1
            }
            val pairedEnd = count - ((count - offset) % PCM_BYTES_PER_FRAME)
            while (offset < pairedEnd) {
                currentCoroutineContext().ensureActive()
                check(expectedGeneration == generation)
                val written = track.write(buffer, offset, pairedEnd - offset, AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "AudioTrack write failed: $written" }
                offset += written
            }
            if (pairedEnd < count) pendingByte = buffer[count - 1].toInt() and 0xff
            if (!firstSampleSubmitted && (pairedEnd > 0 || offset > 0)) {
                logTiming("first_pcm_submitted", expectedGeneration)
                firstSampleSubmitted = true
            }
        }
        check(totalBytes > 0L) { "Empty speech response" }
        check(pendingByte < 0) { "Incomplete PCM sample" }
        val totalFrames = totalBytes / PCM_BYTES_PER_FRAME
        while (expectedGeneration == generation && track.playbackHeadPosition.toLong() < totalFrames) {
            currentCoroutineContext().ensureActive()
            kotlinx.coroutines.delay(20)
        }
        logTiming("speech_complete", expectedGeneration)
    }

    private fun pruneSpeechCache(directory: File) {
        val cachedFiles = directory.listFiles()?.filter { it.extension == "pcm" } ?: return
        var totalBytes = cachedFiles.sumOf(File::length)
        cachedFiles.sortedBy(File::lastModified).forEach { file ->
            val fileBytes = file.length()
            if (totalBytes > MAX_PCM_CACHE_BYTES && file.delete()) totalBytes -= fileBytes
        }
    }

    private fun finish(onFinished: () -> Unit) {
        textToSpeech?.shutdown()
        textToSpeech = null
        audioTrack?.release()
        audioTrack = null
        resumeIfNeeded()
        onFinished()
    }

    private fun resumeIfNeeded() {
        if (resumePlayback) {
            resumePlayback = false
            player.play()
        }
    }

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt

    private fun logTiming(stage: String, expectedGeneration: Long) {
        if (expectedGeneration == generation) Log.i(TAG, "stage=$stage elapsedMs=${elapsedMs()}")
    }

    private companion object {
        const val TAG = "SongMeaningCarSpeech"
        const val OPENAI_SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        const val OPENAI_TTS_MODEL = "gpt-4o-mini-tts"
        const val OPENAI_VOICE = "marin"
        const val OPENAI_INSTRUCTIONS = "Leggi in italiano con tono naturale, caldo e informativo."
        const val PCM_SAMPLE_RATE = 24_000
        const val PCM_BUFFER_SIZE = 4_096
        const val PCM_BYTES_PER_FRAME = 2
        const val MAX_PCM_CACHE_BYTES = 100L * 1024 * 1024
    }
}
