package com.maxrave.media3.exoplayer

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class DelegatingForwardingPlayerTest {
    @Test
    fun endedEmptyQueueRemainsEndedDuringTransition() {
        val player = player(Player.STATE_ENDED, empty = true)
        player.suppressPlaybackEnded = true

        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    @Test
    fun endedTrackKeepsSessionBufferingUntilNextTrack() {
        val player = player(Player.STATE_ENDED, empty = false)
        player.suppressPlaybackEnded = true

        assertEquals(Player.STATE_BUFFERING, player.playbackState)
    }

    @Test
    fun clearingQueueWhileSuppressedStopsReportingBuffering() {
        var timeline = timeline(empty = false)
        val player = DelegatingForwardingPlayer(delegate(Player.STATE_ENDED) { timeline })
        player.suppressPlaybackEnded = true
        assertEquals(Player.STATE_BUFFERING, player.playbackState)

        timeline = timeline(empty = true)

        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    @Test
    fun ordinaryStatesAndUnsuppressedEndArePreserved() {
        for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            assertEquals(state, player(state, empty = false).playbackState)
        }
        val idle = player(Player.STATE_IDLE, empty = true)
        idle.suppressPlaybackEnded = true
        assertEquals(Player.STATE_IDLE, idle.playbackState)
    }

    private fun player(
        state: Int,
        empty: Boolean,
    ) = DelegatingForwardingPlayer(delegate(state) { timeline(empty) })

    private fun delegate(
        state: Int,
        timeline: () -> Timeline,
    ): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "getCurrentTimeline" -> timeline()
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player

    private fun timeline(empty: Boolean): Timeline =
        object : Timeline() {
            override fun getWindowCount() = if (empty) 0 else 1

            override fun getPeriodCount() = if (empty) 0 else 1

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long,
            ): Window = error("Window contents are not queried by this regression")

            override fun getPeriod(
                periodIndex: Int,
                period: Period,
                setIds: Boolean,
            ): Period = error("Period contents are not queried by this regression")

            override fun getIndexOfPeriod(uid: Any) = 0

            override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
        }
}