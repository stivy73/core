package com.maxrave.media3.carapp

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.Player
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var audioPlayer: MediaPlayer? = null
    private var resumePlayback = false
    private var generation = 0L

    fun start(onFinished: () -> Unit) {
        stop(resume = false)
        val current = player.currentMediaItem ?: run {
            onFinished()
            return
        }
        val expectedMediaId = current.mediaId
        val title = current.mediaMetadata.title?.toString().orEmpty()
        val artist = current.mediaMetadata.artist?.toString().orEmpty()
        resumePlayback = player.isPlaying
        player.pause()
        val currentGeneration = ++generation
        requestJob =
            scope.launch {
                val lyrics =
                    repository
                        .getSavedLyrics(expectedMediaId)
                        .first()
                        ?.lines
                        ?.joinToString("\n") { it.words }
                        ?.takeIf(String::isNotBlank)
                val result = repository.getSongExplanation(title, artist, lyrics).first()
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
        audioPlayer?.release()
        audioPlayer = null
        if (resume) resumeIfNeeded() else resumePlayback = false
    }

    private fun speakWithAndroid(text: String, expectedGeneration: Long, onFinished: () -> Unit) {
        textToSpeech =
            TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS || expectedGeneration != generation) {
                    finish(onFinished)
                    return@TextToSpeech
                }
                val tts = textToSpeech ?: return@TextToSpeech
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
                        override fun onStart(utteranceId: String?) = Unit
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
        val file = runCatching { openAiSpeech(text, key) }.getOrElse {
            finish(onFinished)
            return
        }
        if (expectedGeneration != generation) return
        audioPlayer =
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(context, Uri.fromFile(file))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { scope.launch { finish(onFinished) } }
                setOnErrorListener { _, _, _ ->
                    scope.launch { finish(onFinished) }
                    true
                }
                prepareAsync()
            }
    }

    private suspend fun openAiSpeech(text: String, key: String): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "song_meaning_speech").apply { mkdirs() }
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest((OPENAI_VOICE + text).encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val target = File(directory, "$digest.mp3")
            if (target.length() > 0L) return@withContext target
            val connection = URL(OPENAI_SPEECH_URL).openConnection() as HttpURLConnection
            try {
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
                    put("instructions", "Leggi in italiano con tono naturale, caldo e informativo.")
                    put("response_format", "mp3")
                }.toString()
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
                check(connection.responseCode in 200..299)
                val temporary = File(directory, "$digest.tmp")
                connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
                check(temporary.renameTo(target))
                target
            } finally {
                connection.disconnect()
            }
        }

    private fun finish(onFinished: () -> Unit) {
        textToSpeech?.shutdown()
        textToSpeech = null
        audioPlayer?.release()
        audioPlayer = null
        resumeIfNeeded()
        onFinished()
    }

    private fun resumeIfNeeded() {
        if (resumePlayback) {
            resumePlayback = false
            player.play()
        }
    }

    private companion object {
        const val OPENAI_SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        const val OPENAI_TTS_MODEL = "gpt-4o-mini-tts"
        const val OPENAI_VOICE = "marin"
    }
}
