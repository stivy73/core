package com.maxrave.media3.carapp

import com.maxrave.domain.data.model.browse.artist.ResultAlbum
import com.maxrave.domain.data.model.browse.artist.ResultSingle
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistAlbumLinksTest {
    @Test
    fun preservesAlbumsAndSinglesWithoutDuplicateBrowseDestinations() {
        val album = ResultAlbum("MPRE1", false, emptyList(), "Album", "2026")
        val repeated = ResultSingle("MPRE1", emptyList(), "Same release", "2026")
        val single = ResultSingle("MPRE2", emptyList(), "Single", "2026")
        assertEquals(listOf("MPRE1" to "Album", "MPRE2" to "Single"), artistAlbumLinks(listOf(album), listOf(repeated, single)))
    }

    @Test
    fun rejectsMissingBrowseIdentifiersAndSupportsEmptyCatalog() {
        assertEquals(emptyList<Pair<String, String>>(), artistAlbumLinks(emptyList(), emptyList()))
        assertEquals(emptyList<Pair<String, String>>(), artistAlbumLinks(emptyList(), listOf(ResultSingle(" ", emptyList(), "Invalid", ""))))
    }
}