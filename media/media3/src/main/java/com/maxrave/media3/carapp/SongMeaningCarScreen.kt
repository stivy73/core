package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.maxrave.media3.R

internal class SongMeaningCarScreen(
    carContext: CarContext,
    private val speech: SongMeaningCarSpeech,
) : Screen(carContext) {
    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    speech.stop()
                }
            },
        )
        speech.start {
            if (!isDestroyed && screenManager.top === this) screenManager.pop()
        }
    }

    override fun onGetTemplate(): Template =
        MessageTemplate
            .Builder(" ")
            .setTitle(carContext.getString(R.string.song_meaning))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action
                    .Builder()
                    .setTitle(carContext.getString(R.string.stop_reading))
                    .setOnClickListener {
                        speech.stop()
                        screenManager.pop()
                    }.build(),
            ).build()

    private val isDestroyed: Boolean
        get() = lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED
}
