package app.kultr.dl.core.discover

import app.kultr.dl.core.model.Track
import app.kultr.dl.core.taste.Credits
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Karousel: when the queue runs out, more music like what has been playing,
 * so it never stops.
 *
 * It draws on a station started from the song now playing (YouTube Music's
 * radio), on songs by artists like the ones playing, on those artists' own
 * best-known songs, and on the user's own music by any of them. Short of
 * those (with no connection, say) it carries on with the user's own music:
 * the same genres first, then what they play most.
 */
class Karousel(
    private val directory: ArtistDirectory?,
    private val radio: (suspend (Track) -> List<Track>)?,
    private val cache: ArtistIdCache = ArtistIdCache.Memory(),
    private val log: (String) -> Unit = {},
) {
    data class Input(
        /** What has been playing, the song now playing first. */
        val seeds: List<Track>,
        /** [Keys.track] keys not to play: what is queued and what played lately. */
        val exclude: Set<String> = emptySet(),
        val rules: Rules = Rules(),
        /** The user's own songs (on the phone, on Navidrome, downloaded, hearted), most played first. */
        val owned: List<Track> = emptyList(),
        val count: Int = 10,
        val random: Random = Random.Default,
    )

    private class Around(val same: List<Track>, val similar: List<Track>, val similarArtists: List<String>)

    suspend fun next(input: Input): List<Track> = coroutineScope {
        val seeds = input.seeds.filter { it.artist.isNotBlank() }
        if (seeds.isEmpty() || input.count <= 0) return@coroutineScope emptyList()
        val random = input.random
        val artists = seeds.map { Keys.primary(it.artist) }.distinctBy { Credits.key(it) }.take(3)

        val station = async {
            val r = radio ?: return@async emptyList()
            for (seed in seeds.take(2)) {
                val found = safe("radio for ${seed.title}", emptyList()) { r(seed) }
                if (found.isNotEmpty()) return@async found
            }
            emptyList()
        }
        val around = artists.mapIndexed { i, name -> async { around(name, if (i == 0) 4 else 2, random) } }

        val fromStation = station.await()
        val found = around.awaitAll()
        val seedArtists = artists.map(Credits::key).toSet()
        val nearArtists = seedArtists + found.flatMap { it.similarArtists }.map(Credits::key)
        val seedGenres = seeds.mapNotNull { it.genre?.lowercase() }.toSet()
        fun artistOf(t: Track) = Credits.key(Keys.primary(t.artist))

        // In order of preference: the station, artists like these, these artists, the user's own songs by any of them.
        val pools = listOf(
            fromStation,
            roundRobin(found.map { it.similar }),
            roundRobin(found.map { it.same }),
            input.owned.filter { artistOf(it) in nearArtists }.shuffled(random),
        ).map { ArrayDeque(it) }
        val backups = listOf(
            input.owned.filter { it.genre?.lowercase() in seedGenres && artistOf(it) !in nearArtists }.shuffled(random),
            input.owned.take(200).shuffled(random),
        ).map { ArrayDeque(it) }

        val picked = ArrayList<Track>()
        val keys = HashSet<String>(input.exclude)
        val perArtist = HashMap<String, Int>()
        fun take(pool: ArrayDeque<Track>): Boolean {
            while (pool.isNotEmpty()) {
                val t = pool.removeFirst()
                val key = Keys.track(t.artist, t.title)
                val artist = artistOf(t)
                if (key in keys || !input.rules.allows(t) || (perArtist[artist] ?: 0) >= MAX_PER_ARTIST) continue
                keys += key
                perArtist[artist] = (perArtist[artist] ?: 0) + 1
                picked += t
                return true
            }
            return false
        }
        // Mostly the station, with artists like these, these artists and the user's own songs mixed in.
        var turn = 0
        while (picked.size < input.count) {
            val first = MIX[turn++ % MIX.size]
            if (take(pools[first])) continue
            if (pools.none { take(it) }) break
        }
        while (picked.size < input.count && backups.any { take(it) }) Unit

        val sources = listOf(fromStation, found.flatMap { it.similar }, found.flatMap { it.same })
        val counts = sources.map { s -> picked.count { it in s } }
        log(
            "${picked.size} songs like ${artists.joinToString()}: ${counts[0]} from the station, ${counts[1]} by similar artists, " +
                "${counts[2]} by the same artists, ${picked.size - counts.sum()} of the user's own",
        )
        Discovery.spread(picked)
    }

    /** The artist's best-known songs, and songs by artists like them. */
    private suspend fun around(name: String, similarArtists: Int, random: Random): Around = coroutineScope {
        val dir = directory ?: return@coroutineScope Around(emptyList(), emptyList(), emptyList())
        val ref = resolve(dir, name) ?: return@coroutineScope Around(emptyList(), emptyList(), emptyList())
        val top = async { safe("top songs of ${ref.name}", emptyList()) { dir.topTracks(ref, 6) } }
        val related = safe("artists like ${ref.name}", emptyList()) { dir.similar(ref) }.take(8)
        val chosen = related.shuffled(random).take(similarArtists)
        val theirs = chosen.map { r -> async { safe("top songs of ${r.name}", emptyList()) { dir.topTracks(r, 5) }.shuffled(random).take(3) } }
        Around(top.await().shuffled(random).take(3), roundRobin(theirs.awaitAll()), related.map { it.name })
    }

    /** Who [name] is in the catalogue; "not found" is remembered, a failed lookup isn't. */
    private suspend fun resolve(dir: ArtistDirectory, name: String): ArtistRef? {
        val key = Credits.key(name)
        if (key.isEmpty()) return null
        cache.lookup(key)?.getOrNull()?.let { return it }
        if (cache.lookup(key)?.isSuccess == true) return null
        return try {
            dir.find(name).also { cache.store(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("couldn't look up $name: ${e.message}")
            null
        }
    }

    private suspend fun <T> safe(what: String, empty: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$what failed: ${e.message}")
        empty
    }

    companion object {
        /** No more than this many songs by one artist in a batch. */
        const val MAX_PER_ARTIST = 2

        /** Which pool each pick comes from first: 0 the station, 1 similar artists, 2 the same artists, 3 the user's own. */
        private val MIX = intArrayOf(0, 1, 0, 2, 0, 1, 3, 0, 1, 2)

        private fun <T> roundRobin(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>()
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
            return out
        }
    }
}
