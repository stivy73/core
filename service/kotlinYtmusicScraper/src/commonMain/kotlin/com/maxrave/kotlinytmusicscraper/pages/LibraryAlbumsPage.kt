package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import com.maxrave.kotlinytmusicscraper.models.Continuation
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Library envelope only: individual albums use the same parser as generic browse. */
internal data class LibraryAlbumsPage(
    val albums: List<AlbumItem>,
    val continuations: List<String>,
) {
    companion object {
        fun fromResponse(response: BrowseResponse): LibraryAlbumsPage {
            val albums = mutableListOf<AlbumItem>()
            val tokens = mutableListOf<String>()

            fun addTokens(values: List<Continuation>?) {
                values.orEmpty().forEach {
                    val token = it.nextContinuationData?.continuation
                    require(!token.isNullOrBlank()) { "Malformed album continuation" }
                    tokens.add(token)
                }
            }

            fun addAlbum(renderer: MusicTwoRowItemRenderer?) {
                val album = renderer?.let { RelatedPage.fromMusicTwoRowItemRenderer(it) } as? AlbumItem
                require(album != null && album.browseId.isNotBlank()) { "Unexpected library album renderer" }
                albums.add(album)
            }
            response.continuationContents?.gridContinuation?.let { grid ->
                grid.items.forEach { addAlbum(it.musicTwoRowItemRenderer) }
                addTokens(grid.continuations)
                return LibraryAlbumsPage(albums, tokens)
            }
            val sectionContinuation = response.continuationContents?.sectionListContinuation
            val section =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull { it.tabRenderer.content != null }
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?: response.contents?.sectionListRenderer
            val contents = sectionContinuation?.contents ?: section?.contents
            require(contents != null) { "Missing library album content" }
            contents.forEach { content ->
                val grid = content.gridRenderer
                require(grid != null) { "Unexpected library album section" }
                grid.items.forEach { addAlbum(it.musicTwoRowItemRenderer) }
                addTokens(grid.continuations)
            }
            addTokens(sectionContinuation?.continuations ?: section?.continuations)
            return LibraryAlbumsPage(albums, tokens)
        }

        /** All-or-error, stable order, no album cap and no silently truncated partial success. */
        suspend fun completed(fetch: suspend (String?) -> LibraryAlbumsPage): List<AlbumItem> {
            val albums = linkedMapOf<String, AlbumItem>()
            val seen = mutableSetOf<String>()
            val pending = ArrayDeque<String>()
            var token: String? = null
            do {
                currentCoroutineContext().ensureActive()
                val page = fetch(token)
                page.albums.forEach { if (it.browseId !in albums) albums[it.browseId] = it }
                page.continuations.forEach {
                    require(it.isNotBlank() && seen.add(it)) { "Repeated or malformed album continuation" }
                    pending.addLast(it)
                }
                token = pending.removeFirstOrNull()
            } while (token != null)
            return albums.values.toList()
        }
    }
}