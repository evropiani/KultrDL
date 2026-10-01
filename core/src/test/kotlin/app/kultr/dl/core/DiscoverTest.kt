package app.kultr.dl.core

import app.kultr.dl.core.discover.ArtistDirectory
import app.kultr.dl.core.discover.ArtistRef
import app.kultr.dl.core.discover.Discovery
import app.kultr.dl.core.discover.Keys
import app.kultr.dl.core.discover.Owned
import app.kultr.dl.core.discover.Played
import app.kultr.dl.core.discover.Rules
import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.taste.ArtistBlocks
import app.kultr.dl.core.taste.Credits
import app.kultr.dl.core.taste.Signal
import app.kultr.dl.core.taste.Taste
import java.io.IOException
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CreditsTest {
    @Test
    fun findsEveryoneOnATrack() {
        assertEquals(listOf("Drake", "Future"), Credits.people("Drake & Future", "Life Is Good"))
        assertEquals(listOf("Rihanna", "Drake"), Credits.people("Rihanna", "Work (feat. Drake)"))
        assertEquals(listOf("A", "B", "C"), Credits.people("A", "Song ft. B & C"))
        assertEquals(listOf("A", "B"), Credits.people("A", "Song [with B]"))
        assertEquals("drake", Credits.key("Drake - Topic"))
        assertEquals("drake", Credits.key("DrakeVEVO".replace("VEVO", " VEVO")))
    }

    @Test
    fun blockingAnArtistBlocksTheirSongsAndSongsTheyAreOn() {
        val blocks = ArtistBlocks(listOf("Drake"))
        assertTrue(blocks.blocks("Drake", "Hotline Bling"))
        assertTrue(blocks.blocks("Drake & Future", "Life Is Good"))
        assertTrue(blocks.blocks("Future, Drake", "Way 2 Sexy"))
        assertTrue(blocks.blocks("Rihanna", "Work (feat. Drake)"))
        assertTrue(blocks.blocks("Rihanna", "Work ft. Drake"))
        assertTrue(blocks.blocks("Rihanna feat. Drake", "Work"))
        assertTrue(blocks.blocks("Drake - Topic", "Passionfruit"))
        assertTrue(blocks.blocks("DJ Khaled", "Popstar", albumArtist = "Drake"))
        // A name that merely contains the blocked one is someone else.
        assertFalse(blocks.blocks("Drake Bell", "Found a Way"))
        assertFalse(blocks.blocks("Nick Drake", "Pink Moon"))
        assertFalse(blocks.blocks("Rihanna", "Umbrella"))
        assertTrue(blocks.blocksArtist("Drake"))
        assertFalse(ArtistBlocks.NONE.blocks("Drake", "Hotline Bling"))
        val album = Collection("x", Source.DEEZER, CollectionKind.ALBUM, "Scorpion", subtitle = "Drake")
        assertTrue(blocks.blocks(album))
    }
}

class TasteTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun recentPlaysOutweighOldOnesAndSkipsCountAgainst() {
        val profile = Taste.build(
            listOf(
                Signal("Massive Attack", "Teardrop", 1.0, now - 2 * day),
                Signal("Massive Attack", "Angel", 1.0, now - 3 * day),
                Signal("Old Band", "Song", 1.0, now - 400 * day),
                Signal("Old Band", "Song 2", 1.0, now - 400 * day),
                Signal("Skipped", "Meh", 1.0, now - day),
                Signal("Skipped", "Meh", -1.5, now - day),
                Signal("Portishead feat. Beth", "Roads (feat. Tricky)", 1.0, now, genre = "trip hop"),
            ),
            now,
        )
        val top = profile.top(10).map { it.name }
        assertEquals("Massive Attack", top.first())
        assertTrue(profile.score("Massive Attack") > profile.score("Old Band"))
        assertTrue(profile.score("Skipped") < 0)
        assertFalse("Skipped" in top)
        // Featured artists count, but half.
        assertEquals(0.5, profile.score("Tricky"), 0.01)
        assertEquals(listOf("Trip Hop"), profile.get("Portishead feat. Beth")!!.genres)
        assertEquals("Rap/Hip-Hop", Taste.genreName("rap / hip hop"))
    }
}

class KeysTest {
    @Test
    fun editionsAndRemastersAreTheSameAlbum() {
        assertEquals(Keys.album("Radiohead", "OK Computer"), Keys.album("Radiohead", "OK Computer (Remastered)"))
        assertEquals(Keys.album("Radiohead", "Kid A"), Keys.album("Radiohead", "Kid A [Deluxe Edition]"))
        assertEquals(Keys.track("Radiohead", "Creep"), Keys.track("Radiohead feat. Nobody", "Creep - 2009 Remaster"))
        val owned = Owned.Builder().add("Radiohead", "Airbag", "OK Computer OKNOTOK 1997 2017", "Radiohead").add("Björk", "Joga", "Homogenic").build()
        assertTrue(owned.hasSong("radiohead", "Airbag"))
        assertTrue(owned.hasAlbum("Björk", "Homogenic (Deluxe Edition)"))
        assertTrue(owned.hasArtist("Bjork"))
        assertFalse(owned.hasAlbum("Björk", "Vespertine"))
    }
}

private fun track(artist: String, title: String, id: String = "deezer:${artist}-$title") =
    Track(id, Source.DEEZER, title, artist, artworkUrl = "https://img/$artist-$title.jpg")

class DiscoveryTest {
    private val today = LocalDate.parse("2026-10-01")

    private fun album(artist: String, title: String, date: String, type: String = "album", genre: String? = "Electro") =
        Collection("deezer:album:$artist-$title", Source.DEEZER, CollectionKind.ALBUM, title, subtitle = artist, releaseDate = date, recordType = type, genre = genre)

    private class Fake(
        val artists: Map<String, ArtistRef>,
        val releases: Map<String, List<Collection>>,
        val similar: Map<String, List<ArtistRef>>,
        val top: Map<String, List<Track>>,
        val fail: Boolean = false,
    ) : ArtistDirectory {
        val asked = mutableListOf<String>()
        override suspend fun find(name: String): ArtistRef? {
            if (fail) throw IOException("offline")
            asked += name
            return artists[Credits.key(name)]
        }
        override suspend fun releases(artist: ArtistRef) = releases[artist.id].orEmpty()
        override suspend fun bestAlbums(artist: ArtistRef, limit: Int) = releases[artist.id].orEmpty().filter { it.recordType == "album" }.take(limit)
        override suspend fun similar(artist: ArtistRef) = similar[artist.id].orEmpty()
        override suspend fun topTracks(artist: ArtistRef, limit: Int) = top[artist.id].orEmpty().take(limit)
        override suspend fun tracks(release: Collection) = listOf(track(release.subtitle!!, release.title + " (title track)"))
    }

    private fun ref(name: String) = ArtistRef("deezer:$name", name, fans = 1000)

    private val world = Fake(
        artists = listOf("Daft Punk", "Justice", "Air", "Cassius", "Drake", "Phoenix").associate { Credits.key(it) to ref(it) },
        releases = mapOf(
            "deezer:Daft Punk" to listOf(
                album("Daft Punk", "New Thing", "2026-09-26", "single"),
                album("Daft Punk", "Homework", "1997-01-20"),
                album("Daft Punk", "Discovery", "2001-03-12"),
                album("Daft Punk", "Too Old News", "2026-06-01", "single"),
            ),
            "deezer:Justice" to listOf(album("Justice", "Hyperdrama", "2024-04-26"), album("Justice", "Cross", "2007-06-11")),
            "deezer:Cassius" to listOf(album("Cassius", "1999", "1999-01-01")),
            "deezer:Drake" to listOf(album("Drake", "Blocked Album", "2026-09-30", genre = "Rap/Hip Hop")),
            "deezer:Phoenix" to listOf(album("Phoenix", "Wolfgang", "2009-05-25", genre = "Alternative")),
        ),
        similar = mapOf(
            "deezer:Daft Punk" to listOf(ref("Justice"), ref("Cassius"), ref("Drake"), ref("Air")),
            "deezer:Air" to listOf(ref("Phoenix"), ref("Cassius")),
        ),
        top = mapOf(
            "deezer:Cassius" to (1..6).map { track("Cassius", "C$it") },
            "deezer:Phoenix" to (1..6).map { track("Phoenix", "P$it") },
            "deezer:Drake" to (1..6).map { track("Drake", "D$it") },
            "deezer:Daft Punk" to (1..8).map { track("Daft Punk", "New DP $it") } + track("Daft Punk", "One More Time"),
            "deezer:Air" to (1..6).map { track("Air", "Air $it") },
        ),
    )

    private val now = 1_790_000_000_000L
    private val familiar = listOf(
        Played(track("Daft Punk", "One More Time", "phone:1"), 40, now - 2 * 86_400_000L),
        Played(track("Daft Punk", "Aerodynamic", "phone:2"), 25, now - 100 * 86_400_000L),
        Played(track("Daft Punk", "Digital Love", "phone:3"), 3, now - 50 * 86_400_000L),
        Played(track("Air", "La Femme d'Argent", "phone:4"), 12, now - 3 * 86_400_000L),
        Played(track("Air", "Sexy Boy", "phone:5"), 8, now - 200 * 86_400_000L),
        Played(track("Justice", "D.A.N.C.E.", "phone:6"), 5, now - 5 * 86_400_000L),
    ) + (1..10).map { Played(track("Air", "Air old $it", "phone:a$it"), 2, now - 5 * 86_400_000L) }

    private fun input(blocks: List<String> = emptyList(), dismissed: Set<String> = emptySet(), discover: Double = 0.5): Discovery.Input {
        val signals = familiar.map { Signal(it.track.artist, it.track.title, it.plays.toDouble(), it.lastPlayedAt) }
        val owned = Owned.Builder().apply {
            add("Daft Punk", "One More Time", "Discovery")
            add("Air", "Sexy Boy", "Moon Safari")
            add("Justice", "D.A.N.C.E.", "Cross")
        }.build()
        return Discovery.Input(
            profile = Taste.build(signals, now),
            owned = owned,
            rules = Rules(ArtistBlocks(blocks), dismissed),
            familiar = familiar,
            discover = discover,
            today = today,
            now = now,
        )
    }

    @Test
    fun buildsTheFeed() = runTest {
        val feed = Discovery(world).build(input())
        // New releases: within the last 30 days, newest first; older singles left out.
        assertEquals(listOf("New Thing"), feed.releases.filter { it.artist == "Daft Punk" }.map { it.collection.title })
        assertEquals("Single", feed.releases.first { it.collection.title == "New Thing" }.reason)
        // Missing: albums by artists the user owns, minus the ones they have (Discovery, Cross).
        val missing = feed.missing.map { it.collection.title }
        assertTrue("Homework" in missing, missing.toString())
        assertFalse("Discovery" in missing)
        assertTrue("Hyperdrama" in missing)
        assertFalse("Cross" in missing)
        // Albums for you come from artists new to the user.
        val albums = feed.albums.map { it.artist }
        assertTrue("Cassius" in albums || "Phoenix" in albums, albums.toString())
        assertFalse("Daft Punk" in albums)
        // Mixes: Release Radar, daily mixes, Discover, "Because you play…".
        val ids = feed.mixes.map { it.id }
        assertTrue("release-radar" in ids, ids.toString())
        assertTrue(ids.any { it.startsWith("daily-") }, ids.toString())
        assertTrue(ids.any { it.startsWith("because-") }, ids.toString())
        for (mix in feed.mixes) {
            val keys = mix.tracks.map { Keys.track(it.artist, it.title) }
            assertEquals(keys.distinct(), keys, "${mix.id} repeats a song")
            // Six artists in this world: at most five songs each in a mix of thirty.
            assertTrue(mix.tracks.groupBy { Credits.key(it.artist) }.values.all { it.size <= 5 }, "${mix.id}: too many by one artist")
        }
        // Rediscover: played a lot, not for a while.
        assertEquals(listOf("Aerodynamic", "Sexy Boy", "Digital Love"), feed.rediscover.map { it.title })
        assertFalse(feed.offline)
    }

    @Test
    fun blockedArtistsAndDismissedAlbumsNeverShowUp() = runTest {
        val feed = Discovery(world).build(input(blocks = listOf("Drake"), dismissed = setOf(Keys.album("Justice", "Hyperdrama"))))
        val everything = feed.releases.map { it.artist } + feed.albums.map { it.artist } + feed.missing.map { it.artist } +
            feed.mixes.flatMap { m -> m.tracks.map { it.artist } }
        assertFalse(everything.any { Credits.key(it) == "drake" }, everything.toString())
        assertFalse(feed.missing.any { it.collection.title == "Hyperdrama" })
        val unblocked = Discovery(world).build(input())
        assertTrue(unblocked.releases.any { it.artist == "Drake" } || unblocked.mixes.any { m -> m.tracks.any { it.artist == "Drake" } })
    }

    @Test
    fun theDiscoverSliderChangesTheMix() = runTest {
        fun freshShare(feed: app.kultr.dl.core.discover.Feed): Double {
            val mix = feed.mixes.first { it.id.startsWith("daily-") }
            return mix.tracks.count { !it.id.startsWith("phone:") }.toDouble() / mix.tracks.size
        }
        val familiarFeed = Discovery(world).build(input(discover = 0.0))
        val adventurous = Discovery(world).build(input(discover = 1.0))
        assertTrue(freshShare(adventurous) > freshShare(familiarFeed), "${freshShare(adventurous)} vs ${freshShare(familiarFeed)}")
    }

    @Test
    fun withoutAConnectionItStillMakesMixesFromWhatIsThere() = runTest {
        val offline = Fake(emptyMap(), emptyMap(), emptyMap(), emptyMap(), fail = true)
        val feed = Discovery(offline).build(input())
        assertTrue(feed.offline)
        assertTrue(feed.releases.isEmpty())
        assertTrue(feed.mixes.any { it.id.startsWith("daily-") && it.tracks.all { t -> t.id.startsWith("phone:") } }, feed.mixes.toString())
        assertTrue(feed.rediscover.isNotEmpty())
    }

    @Test
    fun spreadKeepsTheSameArtistApart() {
        val list = listOf(track("A", "1"), track("A", "2"), track("A", "3"), track("B", "1"), track("B", "2"), track("C", "1"))
        val spread = Discovery.spread(list)
        assertEquals(list.size, spread.size)
        assertTrue(spread.zipWithNext().count { (a, b) -> a.artist == b.artist } == 0, spread.map { it.artist }.toString())
    }
}
