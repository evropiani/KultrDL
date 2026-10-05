package app.kultr.dl.core

import app.kultr.dl.core.discover.ArtistDirectory
import app.kultr.dl.core.discover.ArtistIdCache
import app.kultr.dl.core.discover.ArtistRef
import app.kultr.dl.core.discover.Karousel
import app.kultr.dl.core.discover.Keys
import app.kultr.dl.core.discover.Rules
import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.taste.ArtistBlocks
import app.kultr.dl.core.taste.Credits
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KarouselTest {
    private fun song(artist: String, title: String, source: Source = Source.DEEZER, genre: String? = null) =
        Track("${source.name.lowercase()}:$artist-$title", source, title, artist, genre = genre)

    private class World(val offline: Boolean = false) : ArtistDirectory {
        val refs = listOf("Daft Punk", "Justice", "Cassius", "Air", "Phoenix", "Drake").associate { Credits.key(it) to ArtistRef("deezer:$it", it) }
        val looked = mutableListOf<String>()
        override suspend fun find(name: String): ArtistRef? {
            if (offline) throw IOException("offline")
            looked += name
            return refs[Credits.key(name)]
        }
        override suspend fun releases(artist: ArtistRef) = emptyList<Collection>()
        override suspend fun bestAlbums(artist: ArtistRef, limit: Int) = emptyList<Collection>()
        override suspend fun similar(artist: ArtistRef) = when (artist.name) {
            "Daft Punk" -> listOf("Justice", "Cassius", "Air", "Drake").map { ArtistRef("deezer:$it", it) }
            else -> emptyList()
        }
        override suspend fun topTracks(artist: ArtistRef, limit: Int) =
            (1..6).map { Track("deezer:${artist.name}-$it", Source.DEEZER, "${artist.name} hit $it", artist.name) }.take(limit)
        override suspend fun tracks(release: Collection) = emptyList<Track>()
    }

    private val playing = song("Daft Punk", "One More Time")
    private val station = (1..12).map { i -> song(listOf("Phoenix", "Air", "Justice", "Daft Punk")[i % 4], "Station song $i", Source.YOUTUBE_MUSIC) } +
        song("Daft Punk", "One More Time", Source.YOUTUBE_MUSIC)

    @Test
    fun keepsTheMusicGoingWithMusicLikeWhatIsPlaying() = runTest {
        val world = World()
        val karousel = Karousel(world, radio = { station })
        val next = karousel.next(
            Karousel.Input(
                seeds = listOf(playing),
                exclude = setOf(Keys.track("Air", "Station song 1"), Keys.track(playing.artist, playing.title)),
                rules = Rules(ArtistBlocks(listOf("drake"))),
                count = 10,
                random = Random(7),
            ),
        )
        assertEquals(10, next.size)
        val keys = next.map { Keys.track(it.artist, it.title) }
        assertEquals(keys.size, keys.toSet().size, "no song twice")
        // What is queued (the song playing, and its other recording on the station) isn't picked again.
        assertTrue(Keys.track("Air", "Station song 1") !in keys)
        assertTrue(next.none { it.title == "One More Time" })
        // Blocked artists never come up, even when the catalogue calls them similar.
        assertTrue(next.none { it.artist == "Drake" })
        // Mostly the station, with songs by similar artists and by the artist playing.
        assertTrue(next.count { it.source == Source.YOUTUBE_MUSIC } >= 4)
        assertTrue(next.any { it.source == Source.DEEZER && it.artist != "Daft Punk" })
        assertTrue(next.any { it.source == Source.DEEZER && it.artist == "Daft Punk" })
        // At most two songs by one artist, and never the same artist twice in a row.
        assertTrue(next.groupBy { it.artist }.values.all { it.size <= Karousel.MAX_PER_ARTIST })
        assertTrue(next.zipWithNext().none { (a, b) -> a.artist == b.artist })
    }

    @Test
    fun withNoConnectionItCarriesOnWithTheUsersOwnMusic() = runTest {
        val mine = listOf(
            song("Daft Punk", "Aerodynamic", Source.NAVIDROME),
            song("Massive Attack", "Teardrop", Source.NAVIDROME, genre = "Trip Hop"),
            song("Portishead", "Roads", Source.PHONE, genre = "Trip Hop"),
            song("Air", "Sexy Boy", Source.NAVIDROME),
            song("Daft Punk", "One More Time", Source.NAVIDROME),
        )
        val karousel = Karousel(World(offline = true), radio = { throw IOException("offline") })
        val next = karousel.next(
            Karousel.Input(
                seeds = listOf(song("Massive Attack", "Angel", Source.NAVIDROME, genre = "Trip Hop")),
                exclude = setOf(Keys.track("Massive Attack", "Angel")),
                owned = mine,
                count = 4,
                random = Random(1),
            ),
        )
        assertEquals(4, next.size)
        // The same artist first, then the same genre, then what the user plays.
        assertEquals(setOf("Teardrop", "Roads"), next.take(4).map { it.title }.filter { it == "Teardrop" || it == "Roads" }.toSet())
        assertTrue(next.all { it.source == Source.NAVIDROME || it.source == Source.PHONE })
    }

    @Test
    fun nothingToGoOnMeansNothingAdded() = runTest {
        val karousel = Karousel(World(offline = true), radio = null)
        assertEquals(emptyList(), karousel.next(Karousel.Input(seeds = listOf(playing))))
        assertEquals(emptyList(), karousel.next(Karousel.Input(seeds = emptyList(), owned = listOf(playing))))
    }

    @Test
    fun anArtistTheCatalogueDoesNotKnowIsAskedForOnce() = runTest {
        val world = World()
        val cache = ArtistIdCache.Memory()
        val karousel = Karousel(world, radio = null, cache = cache)
        val stranger = song("Nobody Knows Me", "Song")
        repeat(2) { karousel.next(Karousel.Input(seeds = listOf(stranger), owned = listOf(playing), random = Random(3))) }
        assertEquals(1, world.looked.count { it == "Nobody Knows Me" })
    }
}
