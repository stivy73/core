package com.maxrave.media3.carapp

import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Search screen for the Android Auto templated surface, backed by the same
 * [com.maxrave.media3.service.callback.SimpleMediaSessionCallback] search
 * pipeline the classic voice/browse search used — MediaBrowser.search()
 * triggers onSearch, then getSearchResult() returns the collected tracks.
 * Searching starts on submit only: every query hits the YouTube scraper.
 */
@UnstableApi
internal class SearchCarScreen(
    carContext: CarContext,
    private val browserProvider: () -> ListenableFuture<MediaBrowser>,
) : Screen(carContext),
    KoinComponent {
    private val searchRepository: SearchRepository by inject()
    private var filter = "songs"
    private var query = ""
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bitmapLoader = CoilBitmapLoader(carContext, screenScope)

    // null = nothing searched yet; empty = search finished without results
    private var results: List<MediaItem>? = null
    private var searching = false
    private var searchJob: Job? = null
    private val artwork = mutableMapOf<String, CarIcon>()

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch {
            handler.nowPlaying.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val builder =
            SearchTemplate
                .Builder(
                    object : SearchTemplate.SearchCallback {
                        override fun onSearchTextChanged(searchText: String) {
                            // Intentionally empty — searching only on submit
                        }

                        override fun onSearchSubmitted(searchText: String) {
                            submitSearch(searchText)
                        }
                    },
                ).setHeaderAction(Action.BACK)
                .setSearchHint(carContext.getString(R.string.car_search_hint))
                .setInitialSearchText(query)
                .setShowKeyboardByDefault(results == null && !searching)
        if (searching) {
            return builder.setLoading(true).build()
        }
        val items = results.orEmpty()
        run {
            val nowPlayingId = handler.nowPlaying.value?.mediaId
            val itemListBuilder = ItemList.Builder().setNoItemsMessage("No results")
            listOf(
                "songs" to R.string.car_search_songs,
                "artists" to R.string.car_search_artists,
                "albums" to R.string.car_search_albums,
            ).forEach { (id, label) ->
                itemListBuilder.addItem(
                    Row
                        .Builder()
                        .setTitle(carContext.getString(label) + if (id == filter) " ✓" else "")
                        .setOnClickListener {
                            filter = id
                            if (query.isNotBlank()) submitSearch(query) else invalidate()
                        }.build(),
                )
            }
            items.take((carContext.listContentLimit() - 3).coerceAtLeast(0)).forEach { item ->
                val isPlaying = item.mediaId.substringAfterLast('/') == nowPlayingId
                itemListBuilder.addItem(
                    carContext.mediaRow(item, artwork[item.mediaId], isPlaying) {
                        if (item.mediaMetadata.isBrowsable == true) {
                            if (item.mediaId.startsWith("artist/")) {
                                screenManager.push(
                                    ArtistCarScreen(
                                        carContext,
                                        browserProvider,
                                        item.mediaId.removePrefix("artist/"),
                                        item.mediaMetadata.title.toString(),
                                    ),
                                )
                            } else {
                                screenManager.push(MediaListCarScreen(carContext, browserProvider, item.mediaId, item.mediaMetadata.title.toString()))
                            }
                            return@mediaRow
                        }
                        screenScope.launch {
                            try {
                                browserProvider().await().setMediaItem(item)
                                val selectedId = item.mediaId.substringAfterLast('/')
                                val selected =
                                    withTimeoutOrNull(8_000) {
                                        handler.nowPlaying.first { it?.mediaId == selectedId }
                                    }
                                if (selected == null) {
                                    Logger.e(TAG, "playItem(${item.mediaId}) did not become current")
                                    return@launch
                                }
                                screenManager.popToRoot()
                                screenManager.push(NowPlayingCarScreen(carContext))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e(TAG, "playItem(${item.mediaId}) failed: ${e.message}")
                            }
                        }
                    },
                )
            }
            builder.setItemList(itemListBuilder.build())
        }
        return builder.build()
    }

    private fun submitSearch(query: String) {
        if (query.isBlank()) return
        this.query = query
        searching = true
        invalidate()
        searchJob?.cancel()
        searchJob =
            screenScope.launch {
                val items =
                    try {
                        val browser = browserProvider().await()
                        if (filter == "artists") {
                            searchRepository.getSearchDataArtist(query).last().data.orEmpty().map { artist ->
                                browseItem(
                                    "artist/${artist.browseId}",
                                    artist.artist,
                                    "",
                                    artist.thumbnails.lastOrNull()?.url,
                                    MediaMetadata.MEDIA_TYPE_ARTIST,
                                )
                            }
                        } else if (filter == "albums") {
                            searchRepository.getSearchDataAlbum(query).last().data.orEmpty().map { album ->
                                browseItem(
                                    "album/${album.browseId}",
                                    album.title,
                                    album.artists.joinToString(", ") {
                                        it.name
                                    },
                                    album.thumbnails.lastOrNull()?.url,
                                    MediaMetadata.MEDIA_TYPE_ALBUM,
                                )
                            }
                        } else {
                            browser.search(query, null).await()
                            browser
                                .getSearchResult(query, 0, Int.MAX_VALUE, null)
                                .await()
                                .value
                                ?.toList()
                                ?: emptyList()
                        }
                    } catch (e: CancellationException) {
                        // Rethrow so a superseded search can't clobber newer results
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "search($query) failed: ${e.message}")
                        emptyList()
                    }
                searching = false
                results = items
                invalidate()
                loadArtworkInto(screenScope, bitmapLoader, items, carContext.listContentLimit(), artwork, TAG) {
                    invalidate()
                }
            }
    }

    private fun browseItem(
        id: String,
        title: String,
        subtitle: String,
        image: String?,
        type: Int,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setArtworkUri(image?.let(Uri::parse))
                    .build(),
            ).build()

    private companion object {
        private const val TAG = "SearchCarScreen"
    }
}