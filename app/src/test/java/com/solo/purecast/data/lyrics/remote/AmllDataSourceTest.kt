package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.model.LyricFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmllDataSourceTest {

    private class FakeHttpClient(
        private val responder: (String) -> LyricsHttpClient.HttpResponse
    ) : LyricsHttpClient {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, timeoutMs: Int, headers: Map<String, String>): LyricsHttpClient.HttpResponse {
            urls += url
            return responder(url)
        }
    }

    @Test
    fun `fetch returns null when artist or title is blank`() = runBlocking {
        val client = FakeHttpClient { LyricsHttpClient.HttpResponse(200, "{}") }
        val source = AmllDataSource(client)
        assertNull(source.fetch("", "Song"))
        assertNull(source.fetch("Artist", "   "))
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun `fetchWithMetadata selects matching part candidate over part 1 and extracts metadata`() = runBlocking {
        val client = FakeHttpClient { url ->
            when {
                url.contains("/v1/lyrics/search?musicName=") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": {
                        "items": [
                          { "id": 1001, "musicName": "Love the Way You Lie", "artists": ["Eminem", "Rihanna"], "duration": 263 },
                          { "id": 1002, "music_name": "Love the Way You Lie, Pt. 2", "singers": [{"name": "Rihanna"}, {"name": "Eminem"}], "durationMs": 296000 }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                url.endsWith("/v1/lyrics/get?id=1002") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": {
                        "lyrics": "<?xml version=\"1.0\" encoding=\"utf-8\"?><tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:amll=\"http://www.example.com/ns/amll\"><head><amll:meta value=\"Love the Way You Lie, Pt. 2\" key=\"musicName\"/><amll:meta key=\"artists\" value=\"Rihanna\"/></head><body><div><p begin=\"00:05.00\" end=\"04:56.00\"><span begin=\"00:05.00\" end=\"00:06.00\">On</span> <span begin=\"00:06.00\" end=\"00:07.00\">the</span></p></div></body></tt>"
                      }
                    }
                    """.trimIndent()
                )
                else -> LyricsHttpClient.HttpResponse(404, "")
            }
        }

        val source = AmllDataSource(client)
        val result = source.fetchWithMetadata("Rihanna", "Love the Way You Lie, Pt. 2", expectedDurationMs = 296_000L)
        assertNotNull(result)
        assertEquals("Rihanna Eminem", result!!.candidateArtist)
        assertEquals("Love the Way You Lie, Pt. 2", result.candidateTitle)
        assertEquals(LyricFormat.TTML_SYLLABLE, result.timeline.format)
    }

    @Test
    fun `fetchWithMetadata falls back to q search and extracts unordered TTML metadata with numeric entities`() = runBlocking {
        val client = FakeHttpClient { url ->
            when {
                url.contains("/v1/lyrics/search?musicName=") -> LyricsHttpClient.HttpResponse(200, """{"data":[]}""")
                url.contains("/v1/lyrics/search?q=") -> LyricsHttpClient.HttpResponse(
                    200,
                    """{"data":[{"id":"2001"}]}"""
                )
                url.endsWith("/v1/lyrics/get?id=2001") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": {
                        "lyrics": "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:amll=\"http://www.example.com/ns/amll\"><head><amll:meta value=\"Don&#39;t Stop Me Now &amp; &#x27;78\" key=\"musicName\"/><amll:meta value=\"Queen\" key=\"artists\"/></head><body><div><p begin=\"00:01.00\" end=\"03:29.00\"><span begin=\"00:01.00\" end=\"00:02.00\">Tonight</span></p></div></body></tt>"
                      }
                    }
                    """.trimIndent()
                )
                else -> LyricsHttpClient.HttpResponse(404, "")
            }
        }

        val source = AmllDataSource(client)
        val result = source.fetchWithMetadata("Queen", "Don't Stop Me Now", expectedDurationMs = 209_000L)
        assertNotNull(result)
        assertEquals("Queen", result!!.candidateArtist)
        assertEquals("Don't Stop Me Now & '78", result.candidateTitle)
        assertTrue(client.urls.any { it.contains("/v1/lyrics/search?q=") })
    }

    @Test
    fun `fetchWithMetadata returns null when all candidates fail scoring or get fails`() = runBlocking {
        val mismatchClient = FakeHttpClient { url ->
            if (url.contains("/search")) {
                LyricsHttpClient.HttpResponse(
                    200,
                    """{"data":[{"id":3001,"title":"Completely Different Song","artistName":"Other Artist","duration":180}]}"""
                )
            } else {
                LyricsHttpClient.HttpResponse(500, "")
            }
        }
        assertNull(AmllDataSource(mismatchClient).fetchWithMetadata("Jay Chou", "晴天", 269_000L))

        val brokenJsonClient = FakeHttpClient { LyricsHttpClient.HttpResponse(200, "not-json") }
        assertNull(AmllDataSource(brokenJsonClient).fetch("Jay Chou", "晴天"))
    }

    @Test
    fun `fetchWithMetadata parses real AMLL plural array fields and unescaped apostrophe in TTML meta`() = runBlocking {
        val client = FakeHttpClient { url ->
            when {
                url.contains("/v1/lyrics/search?musicName=") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "status": 200,
                      "data": [
                        {
                          "id": 999999,
                          "musicNames": ["Completely Unrelated Remix"],
                          "artistNames": ["DJ Nobody"]
                        },
                        {
                          "id": 2295120713663679,
                          "filename": "1755596075179-160724575-0b7029a6.ttml",
                          "createdAt": 1755596075179,
                          "musicNames": ["We Don't Talk Anymore (feat. Selena Gomez)"],
                          "artistNames": ["Charlie Puth", "Selena Gomez"],
                          "albumNames": ["Nine Track Mind"]
                        }
                      ]
                    }
                    """.trimIndent()
                )
                url.endsWith("/v1/lyrics/get?id=2295120713663679") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": {
                        "lyrics": "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:amll=\"http://www.example.com/ns/amll\"><head><metadata><amll:meta key=\"musicName\" value=\"We Don't Talk Anymore (feat. Selena Gomez)\"/><amll:meta key=\"artists\" value=\"Charlie Puth\"/><amll:meta key=\"artists\" value=\"Selena Gomez\"/></metadata></head><body><div><p begin=\"00:05.00\" end=\"00:08.00\"><span begin=\"00:05.00\" end=\"00:06.00\">We</span> <span begin=\"00:06.00\" end=\"00:08.00\">don't</span></p></div></body></tt>"
                      }
                    }
                    """.trimIndent()
                )
                else -> LyricsHttpClient.HttpResponse(404, "")
            }
        }

        val source = AmllDataSource(client)
        val result = source.fetchWithMetadata(
            artist = "Charlie Puth",
            title = "We Don't Talk Anymore (feat. Selena Gomez)",
            expectedDurationMs = 217_710L
        )
        assertNotNull(result)
        assertEquals("Charlie Puth Selena Gomez", result!!.candidateArtist)
        assertEquals("We Don't Talk Anymore (feat. Selena Gomez)", result.candidateTitle)
        assertTrue(client.urls.any { it.endsWith("/v1/lyrics/get?id=2295120713663679") })
    }

    @Test
    fun `fetchWithMetadata preserves raw apostrophe in TTML metadata when search fields are absent`() = runBlocking {
        val client = FakeHttpClient { url ->
            when {
                url.contains("/v1/lyrics/search?musicName=") -> LyricsHttpClient.HttpResponse(
                    200,
                    """{"data":[{"id":2295120713663679}]}"""
                )
                url.endsWith("/v1/lyrics/get?id=2295120713663679") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": {
                        "lyrics": "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:amll=\"http://www.example.com/ns/amll\"><head><metadata><amll:meta key=\"musicName\" value=\"We Don't Talk Anymore (feat. Selena Gomez)\"/><amll:meta key=\"artists\" value=\"Charlie Puth\"/><amll:meta key=\"artists\" value=\"Selena Gomez\"/></metadata></head><body><div><p begin=\"00:05.00\" end=\"00:08.00\"><span begin=\"00:05.00\" end=\"00:06.00\">We</span></p></div></body></tt>"
                      }
                    }
                    """.trimIndent()
                )
                else -> LyricsHttpClient.HttpResponse(404, "")
            }
        }

        val result = AmllDataSource(client).fetchWithMetadata("Charlie Puth", "We Don't Talk Anymore", 217_710L)
        assertNotNull(result)
        assertEquals("Charlie Puth Selena Gomez", result!!.candidateArtist)
        assertEquals("We Don't Talk Anymore (feat. Selena Gomez)", result.candidateTitle)
    }

    @Test
    fun `fetchWithMetadata ignores explicit JSON null scalar fields and falls back to valid title and artist`() = runBlocking {
        val client = FakeHttpClient { url ->
            when {
                url.contains("/v1/lyrics/search?musicName=") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": [
                        {
                          "id": 5001,
                          "musicNames": null,
                          "musicName": null,
                          "title": "Shape of You",
                          "artistNames": null,
                          "artistName": null,
                          "artist": "Ed Sheeran"
                        }
                      ]
                    }
                    """.trimIndent()
                )
                url.endsWith("/v1/lyrics/get?id=5001") -> LyricsHttpClient.HttpResponse(
                    200,
                    """
                    {
                      "data": {
                        "lyrics": "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:01.00\" end=\"00:05.00\"><span begin=\"00:01.00\" end=\"00:05.00\">The club</span></p></div></body></tt>"
                      }
                    }
                    """.trimIndent()
                )
                else -> LyricsHttpClient.HttpResponse(404, "")
            }
        }

        val result = AmllDataSource(client).fetchWithMetadata("Ed Sheeran", "Shape of You", 233_000L)
        assertNotNull(result)
        assertEquals("Shape of You", result!!.candidateTitle)
        assertEquals("Ed Sheeran", result.candidateArtist)
    }
}
