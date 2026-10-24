package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.domain.data.model.browse.artist.ResultAlbum
import com.maxrave.domain.data.model.browse.artist.ResultSingle
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.media3.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.last
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class ArtistCarScreen(
    carContext: CarContext,
    private val browserProvider: () -> ListenableFuture<MediaBrowser>,
    private val artistId: String,
    private val name: String,
) : Screen(carContext),
    KoinComponent {
    private val repository: ArtistRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var albums: List<Pair<String, String>>? = null
    private var failed = false

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    scope.cancel()
                }
            },
        )
        scope.launch {
            try {
                val result = repository.getArtistData(artistId).last()
                val artist = (result as? Resource.Success)?.data
                failed = artist == null
                albums = artistAlbumLinks(artist?.albums?.results.orEmpty(), artist?.singles?.results.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                albums = emptyList()
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = ListTemplate.Builder().setTitle(name).setHeaderAction(Action.BACK)
        val items = albums ?: return builder.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage(carContext.getString(if (failed) R.string.car_catalog_error else R.string.car_catalog_empty))
        items.take(carContext.listContentLimit()).forEach { (id, title) ->
            list.addItem(
                Row
                    .Builder()
                    .setTitle(title)
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(MediaListCarScreen(carContext, browserProvider, "album/$id", title))
                    }.build(),
            )
        }
        return builder.setSingleList(list.build()).build()
    }
}

internal fun artistAlbumLinks(
    albums: List<ResultAlbum>,
    singles: List<ResultSingle>,
): List<Pair<String, String>> =
    (albums.map { it.browseId to it.title } + singles.map { it.browseId to it.title })
        .filter { it.first.isNotBlank() }
        .distinctBy { it.first }