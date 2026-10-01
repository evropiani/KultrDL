package app.kultr.dl.core

import app.kultr.dl.core.net.Http
import app.kultr.dl.core.sources.Deezer
import app.kultr.dl.core.sources.ListenBrainz
import app.kultr.dl.core.sources.Subsonic
import app.kultr.dl.core.sources.YouTubeMusic
import app.kultr.dl.core.util.parseJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test

class RecommendationSourcesTest {
    @Test
    fun deezerDiscography() {
        val json = parseJson(
            """{"id":302127,"title":"Discovery","cover_xl":"https://e-cdns/x.jpg","genre_id":106,"fans":123,
               "release_date":"2001-03-07","record_type":"album","link":"https://www.deezer.com/album/302127"}""",
        )
        val album = Deezer.parseArtistAlbum(json, "Daft Punk", mapOf(106L to "Electro"))!!
        assertEquals("deezer:album:302127", album.id)
        assertEquals("Daft Punk", album.subtitle)
        assertEquals("2001-03-07", album.releaseDate)
        assertEquals("album", album.recordType)
        assertEquals("Electro", album.genre)
        val artist = Deezer.parseArtist(parseJson("""{"id":27,"name":"Daft Punk","nb_fan":4500000,"picture_xl":"p","type":"artist"}"""))!!
        assertEquals("deezer:27", artist.id)
        assertEquals(4_500_000, artist.fans)
    }

    @Test
    fun youTubeMusicRadio() {
        val json = parseJson(
            """{"contents":{"x":{"playlistPanelRenderer":{"contents":[
              {"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Teardrop"}]},
                "longBylineText":{"runs":[
                  {"text":"Massive Attack","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
                  {"text":" • "},
                  {"text":"Mezzanine","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},
                  {"text":" • "},{"text":"1998"}]},
                "lengthText":{"runs":[{"text":"5:31"}]},
                "thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/x=w60-h60"}]}}},
              {"playlistPanelVideoWrapperRenderer":{"primaryRenderer":{"playlistPanelVideoRenderer":{"videoId":"bbbbbbbbbbb",
                "title":{"runs":[{"text":"Glory Box"}]},"shortBylineText":{"runs":[{"text":"Portishead"}]}}}}}
            ]}}}}""",
        )
        val tracks = YouTubeMusic.parseRadio(json)
        assertEquals(listOf("Teardrop", "Glory Box"), tracks.map { it.title })
        val first = tracks.first()
        assertEquals("Massive Attack", first.artist)
        assertEquals("Mezzanine", first.album)
        assertEquals(331_000L, first.durationMs)
        assertEquals(1998, first.year)
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk", first.streamUrl)
        assertEquals("Portishead", tracks[1].artist)
        assertEquals("abcdefghijk", YouTubeMusic.videoId("https://music.youtube.com/watch?v=abcdefghijk&list=x"))
    }

    @Test
    fun listenBrainzPlaylists() {
        val json = parseJson(
            """{"playlist":{"title":"Weekly Exploration for rob","track":[
               {"title":"Roads","creator":"Portishead","album":"Dummy","identifier":["https://musicbrainz.org/recording/1234-abcd"]},
               {"title":"Angel","creator":"Massive Attack","identifier":"https://musicbrainz.org/recording/5678"}]}}""",
        )
        val tracks = ListenBrainz.parsePlaylist(json)
        assertEquals(listOf("listenbrainz:1234-abcd", "listenbrainz:5678"), tracks.map { it.id })
        assertTrue(tracks.all { it.needsMatch })
        assertEquals("weekly-exploration", ListenBrainz.kind("Weekly Exploration for rob, week of 2026-09-28 Mon"))
        assertNull(ListenBrainz.kind("My own playlist"))
    }

    @Test
    fun subsonicAnswersAndErrors() {
        val ok = Subsonic.check(parseJson("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0","openSubsonic":true}}"""))
        assertEquals("navidrome", (ok as kotlinx.serialization.json.JsonObject)["type"].toString().trim('"'))
        val wrong = assertFailsWith<Subsonic.SubsonicException> {
            Subsonic.check(parseJson("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"""))
        }
        assertEquals(40, wrong.code)
        assertTrue(wrong.message!!.contains("didn't accept"))
        val song = Subsonic.parseSong(
            parseJson(
                """{"id":"s1","title":"Teardrop","artist":"Massive Attack","album":"Mezzanine","albumId":"al1","artistId":"ar1",
                   "genre":"Trip-Hop","year":1998,"track":3,"duration":331,"coverArt":"al-1","playCount":42,
                   "played":"2026-09-20T18:30:00Z","starred":"2025-01-01T00:00:00Z","userRating":5,"isrc":["GBAAA9800001"]}""",
            ),
        )!!
        assertEquals(42, song.playCount)
        assertEquals(331_000L, song.durationMs)
        assertTrue(song.starred)
        assertEquals(5, song.rating)
        assertEquals("GBAAA9800001", song.isrc)
        assertEquals(java.time.Instant.parse("2026-09-20T18:30:00Z").toEpochMilli(), song.playedAt)
        assertEquals("http://nas.local:4533", Subsonic.baseUrl("nas.local:4533/app/#/album/all"))
        assertEquals("https://music.example.com", Subsonic.baseUrl("https://music.example.com/"))
    }

    @Test
    fun subsonicClientSignsEveryRequest() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val songs = (1..3).joinToString(",") { """{"id":"s$it","title":"Song $it","artist":"A","playCount":$it}""" }
            server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","type":"navidrome","serverVersion":"0.58.0","openSubsonic":true}}""").build())
            server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","searchResult3":{"song":[$songs]}}}""").build())
            val client = Subsonic(Http(OkHttpClient()), Subsonic.Server(server.url("/").toString(), "kultr", "sesame"))
            assertEquals("Navidrome 0.58.0", client.ping().toString())
            val all = client.songs(pageSize = 500)
            assertEquals(listOf(1, 2, 3), all.map { it.playCount })
            val ping = server.takeRequest().url
            assertEquals("/rest/ping.view", ping.encodedPath)
            val salt = ping.queryParameter("s")!!
            assertEquals(Subsonic.md5("sesame$salt"), ping.queryParameter("t"))
            assertEquals("kultr", ping.queryParameter("u"))
            assertEquals("json", ping.queryParameter("f"))
            assertNull(ping.queryParameter("p"), "the password itself must never be sent")
            val search = server.takeRequest().url
            assertEquals("", search.queryParameter("query"))
            assertEquals("0", search.queryParameter("songOffset"))
            val stream = client.streamUrl("s1")
            assertTrue(client.owns(stream))
            assertTrue(client.authenticate(stream).contains("&t="))
        } finally {
            server.close()
        }
    }
}
