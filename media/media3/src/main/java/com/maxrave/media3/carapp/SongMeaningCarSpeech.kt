package com.maxrave.media3.carapp

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.media3.R
import com.maxrave.media3.speech.CloudSongMeaningSpeech
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

internal class SongMeaningCarSpeech(
    private val context: Context,
    private val player: Player,
    private val repository: LyricsCanvasRepository,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) {
    private var requestJob: Job? = null
    private var textToSpeech: TextToSpeech? = null
    private val openAiSpeech = CloudSongMeaningSpeech(context, scope)
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
        val durationSeconds = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000)?.toInt()
        startedAt = SystemClock.elapsedRealtime()
        resumePlayback = player.isPlaying
        player.pause()
        val currentGeneration = ++generation
        logTiming("tap", currentGeneration)
        requestJob =
            scope.launch {
                val lyrics = resolveLyrics(expectedMediaId, title, artist, durationSeconds)
                logTiming("lyrics_ready", currentGeneration)
                if (currentGeneration != generation || player.currentMediaItem?.mediaId != expectedMediaId) return@launch
                if (lyrics == null) {
                    // Never ask the model to infer a meaning from the title alone.
                    speakWithAndroid(context.getString(R.string.song_meaning_lyrics_unavailable), currentGeneration, onFinished)
                    return@launch
                }
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
                    DataStoreManager.SONG_MEANING_TTS_GOOGLE -> speakWithGoogle(explanation, currentGeneration, onFinished)
                    else -> speakWithAndroid(explanation, currentGeneration, onFinished)
                }
            }
    }

    private suspend fun resolveLyrics(videoId: String, title: String, artist: String, durationSeconds: Int?): String? {
        fun Lyrics?.text(): String? = this?.lines?.joinToString("\n") { it.words }?.takeIf(String::isNotBlank)

        val saved =
            try {
                repository.getSavedLyrics(videoId).first()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "saved_lyrics_failed", error)
                null
            }
        if (saved?.error == false) {
            saved.lines?.joinToString("\n") { it.words }?.takeIf(String::isNotBlank)?.let { return it }
        }

        // The phone player fetches lyrics from the selected provider and saves them later.
        // Android Auto can be opened before that save finishes, so use the same sources here.
        val selected = dataStoreManager.lyricsProvider.first()
        val sources =
            buildList {
                add(selected)
                add(DataStoreManager.SIMPMUSIC)
                if (dataStoreManager.spotifyLyrics.first() == DataStoreManager.TRUE) add("spotify")
                add(DataStoreManager.LRCLIB)
                add(DataStoreManager.BETTER_LYRICS)
                add(DataStoreManager.YOUTUBE)
            }.distinct()
        for (source in sources) {
            val text =
                try {
                    when (source) {
                        DataStoreManager.SIMPMUSIC ->
                            (repository.getSimpMusicLyrics(videoId).firstOrNull() as? Resource.Success<Lyrics>)?.data.text()
                        DataStoreManager.LRCLIB ->
                            (repository.getLrclibLyricsData(artist, title, durationSeconds).firstOrNull() as? Resource.Success<Lyrics>)?.data.text()
                        DataStoreManager.BETTER_LYRICS ->
                            (repository.getBetterLyrics(artist, title, durationSeconds).firstOrNull() as? Resource.Success<Lyrics>)?.data.text()
                        DataStoreManager.YOUTUBE ->
                            (repository.getYouTubeCaption(dataStoreManager.youtubeSubtitleLanguage.first(), videoId).firstOrNull() as? Resource.Success<Pair<Lyrics, Lyrics?>>)
                                ?.data?.first.text()
                        "spotify" ->
                            (repository.getSpotifyLyrics(dataStoreManager, "$title $artist", durationSeconds).firstOrNull() as? Resource.Success<Lyrics>)?.data.text()
                        else -> null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "lyrics_source_failed source=$source", error)
                    null
                }
            if (text != null) return text
        }
        return null
    }

    fun stop(resume: Boolean = true) {
        generation++
        requestJob?.cancel()
        requestJob = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        openAiSpeech.stop()
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
        runCatching {
            openAiSpeech.speak(text, key, onStage = { stage -> logTiming(stage, expectedGeneration) })
        }
            .onFailure { error ->
                if (expectedGeneration == generation) Log.w(TAG, "speech_failed elapsedMs=${elapsedMs()}", error)
            }
        if (expectedGeneration == generation) finish(onFinished)
    }

    private suspend fun speakWithGoogle(text: String, expectedGeneration: Long, onFinished: () -> Unit) {
        val key = dataStoreManager.googleTtsApiKey.first().ifBlank {
            if (dataStoreManager.aiProvider.first() == DataStoreManager.AI_PROVIDER_GEMINI) dataStoreManager.aiApiKey.first() else ""
        }
        if (key.isBlank()) {
            speakWithAndroid(context.getString(R.string.song_meaning_google_key_missing), expectedGeneration, onFinished)
            return
        }
        runCatching {
            openAiSpeech.speakGemini(text, key, dataStoreManager.songMeaningVoiceStyle.first(), onStage = { stage -> logTiming(stage, expectedGeneration) })
        }.onFailure { error ->
            if (expectedGeneration == generation) Log.w(TAG, "gemini_speech_failed elapsedMs=${elapsedMs()}", error)
        }
        if (expectedGeneration == generation) finish(onFinished)
    }

    private fun finish(onFinished: () -> Unit) {
        textToSpeech?.shutdown()
        textToSpeech = null
        openAiSpeech.stop()
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
    }
}
