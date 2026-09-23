package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import com.maxrave.common.Config.MAIN_PLAYER
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.LyricsCanvasRepository
import com.maxrave.media3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * Now-playing screen for Android Auto. The host renders artwork, seek bar and
 * transport controls from the MediaSession token registered by
 * [SimpMusicCarSession]; the app only owns the header — queue name as title
 * plus a queue button, which the classic media surface never allowed us to
 * customize.
 */
internal class NowPlayingCarScreen(
    carContext: CarContext,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val player: Player by inject(named(MAIN_PLAYER))
    private val lyricsCanvasRepository: LyricsCanvasRepository by inject()
    private val dataStoreManager: DataStoreManager by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val songMeaningSpeech by lazy {
        SongMeaningCarSpeech(
            context = carContext,
            player = player,
            repository = lyricsCanvasRepository,
            dataStoreManager = dataStoreManager,
            scope = screenScope,
        )
    }

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    songMeaningSpeech.stop()
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch {
            handler.queueData.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val queueTitle =
            handler.queueData.value
                ?.data
                ?.playlistName
                ?.takeIf { it.isNotBlank() }
        return MediaPlaybackTemplate
            .Builder()
            .setHeader(
                Header
                    .Builder()
                    .setTitle(queueTitle ?: "Queue")
                    .addEndHeaderAction(
                        Action
                            .Builder()
                            .setIcon(
                                CarIcon
                                    .Builder(
                                        IconCompat.createWithResource(carContext, R.drawable.ic_car_song_meaning),
                                    ).build(),
                            ).setOnClickListener {
                                screenManager.push(SongMeaningCarScreen(carContext, songMeaningSpeech))
                            }.build(),
                    )
                    .addEndHeaderAction(
                        Action
                            .Builder()
                            .setIcon(
                                CarIcon
                                    .Builder(
                                        IconCompat.createWithResource(carContext, R.drawable.ic_car_queue_music),
                                    ).build(),
                            ).setOnClickListener {
                                screenManager.push(QueueCarScreen(carContext))
                            }.build(),
                    ).build(),
            ).build()
    }
}
