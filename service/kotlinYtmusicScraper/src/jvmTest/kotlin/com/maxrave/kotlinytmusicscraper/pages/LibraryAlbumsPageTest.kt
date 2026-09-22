package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryAlbumsPageTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun page(name: String): LibraryAlbumsPage {
        val text = checkNotNull(javaClass.getResource("/library-albums/$name.json")).readText()
        return LibraryAlbumsPage.fromResponse(json.decodeFromString<BrowseResponse>(text))
    }

    @Test
    fun parsesAlbumWithExistingBrowseParser() {
        val result = page("first")
        assertEquals("MPRE_A", result.albums.single().browseId)
        assertEquals("Album MPRE_A", result.albums.single().title)
        assertEquals(
            "Artist",
            result.albums
                .single()
                .artists
                ?.single()
                ?.name,
        )
        assertEquals(listOf("next-1"), result.continuations)
    }

    @Test
    fun emptyLibrary() =
        runBlocking<Unit> {
            assertTrue(LibraryAlbumsPage.completed { page("empty") }.isEmpty())
        }

    @Test
    fun readsGridContinuation() {
        assertEquals(listOf("MPRE_A", "MPRE_B"), page("next-1").albums.map { it.browseId })
        assertEquals(listOf("next-2"), page("next-1").continuations)
    }

    @Test
    fun exhaustsContinuationsWithoutDuplicateAlbums() =
        runBlocking<Unit> {
            val requests = mutableListOf<String?>()
            val albums =
                LibraryAlbumsPage.completed {
                    requests.add(it)
                    page(it ?: "first")
                }
            assertEquals(listOf(null, "next-1", "next-2"), requests)
            assertEquals(listOf("MPRE_A", "MPRE_B", "MPRE_C"), albums.map { it.browseId })
        }

    @Test
    fun repeatedContinuationFailsInsteadOfReturningPartialLibrary() =
        runBlocking<Unit> {
            var requests = 0
            assertFailsWith<IllegalArgumentException> {
                LibraryAlbumsPage.completed {
                    requests++
                    page(if (it == null) "first" else "repeated")
                }
            }
            assertEquals(2, requests)
        }

    @Test
    fun continuationFailureIsNotPartialSuccess() =
        runBlocking<Unit> {
            assertFailsWith<IllegalStateException> {
                LibraryAlbumsPage.completed {
                    if (it == null) page("first") else error("HTTP failure")
                }
            }
        }

    @Test
    fun firstPageFailurePropagates() =
        runBlocking<Unit> {
            assertFailsWith<IllegalStateException> { LibraryAlbumsPage.completed { error("Unauthorized") } }
        }

    @Test
    fun malformedAndUnknownResponsesFail() {
        assertFailsWith<IllegalArgumentException> { page("unexpected") }
        assertFailsWith<IllegalArgumentException> { page("missing") }
        assertFailsWith<IllegalArgumentException> { page("invalid-album") }
    }

    @Test
    fun sectionContinuationPreservesNestedAlbumsAndNextToken() {
        val result = page("section-continuation")
        assertEquals(listOf("MPRE_C"), result.albums.map { it.browseId })
        assertEquals(listOf("last"), result.continuations)
    }

    @Test
    fun cancellationIsNotSwallowed() =
        runBlocking<Unit> {
            assertFailsWith<CancellationException> { LibraryAlbumsPage.completed { throw CancellationException() } }
        }

    @Test
    fun duplicateTokensInSamePageFail() =
        runBlocking<Unit> {
            assertFailsWith<IllegalArgumentException> {
                LibraryAlbumsPage.completed { page("first").copy(continuations = listOf("same", "same")) }
            }
        }

    @Test
    fun blankContinuationFails() =
        runBlocking<Unit> {
            assertFailsWith<IllegalArgumentException> {
                LibraryAlbumsPage.completed { page("first").copy(continuations = listOf("")) }
            }
        }

    @Test
    fun noArbitraryPageLimit() =
        runBlocking<Unit> {
            var requests = 0
            val albums =
                LibraryAlbumsPage.completed {
                    requests++
                    LibraryAlbumsPage(
                        listOf(page("first").albums.single().copy(browseId = "MPRE_$requests")),
                        if (requests < 150) listOf("page-$requests") else emptyList(),
                    )
                }
            assertEquals(150, requests)
            assertEquals(150, albums.size)
        }
}