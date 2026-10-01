package app.kultr.dl.core

import app.kultr.dl.core.discover.CatalogDirectory
import app.kultr.dl.core.discover.Discovery
import app.kultr.dl.core.discover.Owned
import app.kultr.dl.core.discover.Played
import app.kultr.dl.core.discover.Rules
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.sources.AppleMusic
import app.kultr.dl.core.sources.Deezer
import app.kultr.dl.core.sources.ListenBrainz
import app.kultr.dl.core.sources.YouTubeMusic
import app.kultr.dl.core.taste.Signal
import app.kultr.dl.core.taste.Taste
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The recommendation sources against the real services. Run on CI with
 * LIVE_SOURCES=1 (it needs the internet); prints what each one answered.
 */
class LiveSourcesTest {
    private val http = Http(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
    private val deezer = Deezer(http)
    private val apple = AppleMusic(http) { "US" }

    @Before
    fun onlyWhenAsked() = assumeTrue("set LIVE_SOURCES=1 to run", System.getenv("LIVE_SOURCES") == "1")

    @Test
    fun deezerAndApple() = runBlocking {
        val artist = deezer.searchArtists("Daft Punk").maxBy { it.fans }
        println("Deezer artist: $artist")
        val albums = deezer.artistAlbums(artist.id.removePrefix("deezer:"), artist.name)
        println("Deezer releases: ${albums.take(5).map { "${it.title} (${it.recordType}, ${it.releaseDate}, ${it.genre})" }}")
        val related = deezer.related(artist.id.removePrefix("deezer:"))
        println("Deezer related: ${related.take(8).map { it.name }}")
        val top = deezer.top(artist.id.removePrefix("deezer:"), 5)
        println("Deezer top: ${top.map { it.title }}")
        assertTrue(albums.isNotEmpty() && albums.all { it.releaseDate != null } && related.isNotEmpty() && top.isNotEmpty())
        val appleArtist = apple.searchArtists("Daft Punk").first()
        val appleAlbums = apple.artistAlbums(appleArtist.id.removePrefix("apple:"))
        println("Apple releases: ${appleAlbums.take(5).map { "${it.title} (${it.recordType}, ${it.releaseDate}, ${it.genre})" }}")
        assertTrue(appleAlbums.isNotEmpty())
    }

    @Test
    fun youTubeMusicRadio() = runBlocking {
        val result = runCatching { YouTubeMusic(http).radio("u7K72X4eo_s") }
        println("YouTube Music radio: ${result.getOrNull()?.take(8)?.map { "${it.artist} – ${it.title}" } ?: result.exceptionOrNull()}")
    }

    @Test
    fun listenBrainz() = runBlocking {
        val lb = ListenBrainz(http)
        println("ListenBrainz top artists of rob: ${runCatching { lb.topArtists("rob").take(5) }.getOrElse { it.toString() }}")
        val playlists = runCatching { lb.createdFor("rob") }.getOrElse { println("createdFor: $it"); emptyList() }
        println("ListenBrainz playlists: ${playlists.take(4).map { it.title }}")
        playlists.firstOrNull()?.let { println("First playlist: ${lb.playlist(it.id).take(5).map { t -> "${t.artist} – ${t.title}" }}") }
    }

    @Test
    fun aWholeFeed() = runBlocking {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val played = listOf("Daft Punk" to "One More Time", "Massive Attack" to "Teardrop", "Air" to "Sexy Boy", "Portishead" to "Glory Box")
            .mapIndexed { i, (a, t) -> Played(Track("test:$i", Source.WEB, t, a, streamUrl = "x"), 10 - i, now - i * day) }
        val feed = Discovery(CatalogDirectory(deezer, apple), log = { println("  · $it") }).build(
            Discovery.Input(
                profile = Taste.build(played.map { Signal(it.track.artist, it.track.title, it.plays.toDouble(), it.lastPlayedAt) }, now),
                owned = Owned.Builder().apply { played.forEach { add(it.track.artist, it.track.title) } }.build(),
                rules = Rules(),
                familiar = played,
                releaseWindowDays = 120,
            ),
        )
        println("Feed: ${feed.releases.size} releases ${feed.releases.take(3).map { "${it.artist} – ${it.collection.title} ${it.collection.releaseDate}" }}")
        println("Mixes: ${feed.mixes.map { "${it.title} (${it.tracks.size})" }}")
        println("Albums for you: ${feed.albums.take(5).map { "${it.artist} – ${it.collection.title} (${it.reason})" }}")
        assertTrue(feed.mixes.isNotEmpty() && feed.albums.isNotEmpty())
    }
}
