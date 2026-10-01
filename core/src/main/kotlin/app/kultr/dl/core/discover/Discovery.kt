package app.kultr.dl.core.discover

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.taste.ArtistScore
import app.kultr.dl.core.taste.Credits
import app.kultr.dl.core.taste.TasteProfile
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/** An artist as a catalogue knows them ("deezer:27", "apple:5468295"). */
@Serializable
data class ArtistRef(val id: String, val name: String, val fans: Long = 0, val pictureUrl: String? = null)

/** Where artists, their releases, similar artists and top songs come from. */
interface ArtistDirectory {
    suspend fun find(name: String): ArtistRef?

    /** Albums, EPs and singles, with [Collection.releaseDate], [Collection.recordType] and [Collection.genre] where known. */
    suspend fun releases(artist: ArtistRef): List<Collection>

    /** The artist's best-loved albums, most popular first. */
    suspend fun bestAlbums(artist: ArtistRef, limit: Int): List<Collection>

    suspend fun similar(artist: ArtistRef): List<ArtistRef>

    suspend fun topTracks(artist: ArtistRef, limit: Int): List<Track>

    /** The tracks of a release, for Release Radar. */
    suspend fun tracks(release: Collection): List<Track>
}

/** Another opinion on similar artists (Last.fm, Navidrome): names only. */
fun interface SimilarNames {
    suspend fun similar(artist: String): List<String>
}

/** Which catalogue artist a name is, remembered between runs. A null ref means "looked, not found". */
interface ArtistIdCache {
    fun lookup(key: String): Result<ArtistRef?>?

    fun store(key: String, ref: ArtistRef?)

    class Memory : ArtistIdCache {
        private val map = ConcurrentHashMap<String, Result<ArtistRef?>>()
        override fun lookup(key: String) = map[key]
        override fun store(key: String, ref: ArtistRef?) {
            map[key] = Result.success(ref)
        }
    }
}

/** A song the user has, with how often they played it. */
data class Played(val track: Track, val plays: Int, val lastPlayedAt: Long?)

/**
 * Works out the "For you" page from what the user listens to: new
 * releases from their artists, mixes of what they love and what they
 * might, albums they may like, albums missing from their collection, and
 * old favourites to rediscover. Every step tolerates a source failing;
 * with no connection at all, it still makes mixes from what is on hand.
 */
class Discovery(
    private val directory: ArtistDirectory,
    private val similarNames: List<SimilarNames> = emptyList(),
    private val radio: (suspend (Track) -> List<Track>)? = null,
    private val cache: ArtistIdCache = ArtistIdCache.Memory(),
    private val parallel: Int = 4,
    private val log: (String) -> Unit = {},
) {
    data class Input(
        val profile: TasteProfile,
        val owned: Owned,
        val rules: Rules,
        /** Songs the user knows and can play: their library and their files, most played first. */
        val familiar: List<Played>,
        /** 0 = mostly what they know, 1 = mostly new to them. */
        val discover: Double = 0.5,
        val releaseWindowDays: Int = 30,
        val today: LocalDate = LocalDate.now(),
        val seedCount: Int = 25,
        /** Ready-made mixes from elsewhere (ListenBrainz's weekly playlists). */
        val extraMixes: List<Mix> = emptyList(),
        val now: Long = System.currentTimeMillis(),
    )

    private val failures = AtomicInteger()
    private val successes = AtomicInteger()
    private val gate = Semaphore(parallel)

    /** One request to a source; a failure is logged and counted, never fatal. */
    private suspend fun <T> fetch(what: String, block: suspend () -> T): T? = gate.withPermit {
        try {
            block().also { successes.incrementAndGet() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.incrementAndGet()
            log("$what: ${e.message}")
            null
        }
    }

    suspend fun build(input: Input): Feed = coroutineScope {
        failures.set(0)
        successes.set(0)

        val base = input.rules
        val seeds = input.profile.seeds(input.seedCount * 2).filter { base.allowsArtist(it.name) }.take(input.seedCount)
        log("Seeds: ${seeds.take(10).joinToString { it.name }}${if (seeds.size > 10) " and ${seeds.size - 10} more" else ""}")

        // Who each seed artist is in the catalogue.
        val refs: Map<String, ArtistRef> = seeds.map { s -> async { s.key to resolve(s.name) } }
            .awaitAll()
            .mapNotNull { (key, ref) -> ref?.let { key to it } }
            .toMap()

        // Their releases: new ones, missing ones, and their genres.
        val releases: Map<String, List<Collection>> = refs.map { (key, ref) ->
            async { key to (fetch("releases of ${ref.name}") { directory.releases(ref) } ?: emptyList()) }
        }.awaitAll().toMap()
        val genres = releases.mapValues { (_, list) ->
            list.mapNotNull { it.genre }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        }.filterValues { it.isNotEmpty() }
        val profileGenres = seeds.filter { it.genres.isNotEmpty() }.associate { it.key to it.genres }
        val rules = base.withGenres(profileGenres + genres)
        val byKey = seeds.associateBy { it.key }

        val newReleases = pickNewReleases(releases, byKey, input, rules)
        val missing = pickMissing(releases, byKey, input, rules)

        // Similar artists, new to the user, weighted by how much they like the artists they resemble.
        val similar = findSimilar(seeds, refs, input, rules)
        val topSimilar = similar.take(14)
        val similarTracks: Map<String, List<Track>> = topSimilar.map { s ->
            async { s.ref.id to (fetch("top tracks of ${s.ref.name}") { directory.topTracks(s.ref, 6) } ?: emptyList()) }
        }.awaitAll().toMap()
        val similarAlbums: Map<String, List<Collection>> = topSimilar.take(12).map { s ->
            async { s.ref.id to (fetch("albums of ${s.ref.name}") { directory.bestAlbums(s.ref, 2) } ?: emptyList()) }
        }.awaitAll().toMap()

        // Songs of the seed artists the user doesn't have yet: familiar artists, new songs.
        val seedTracks: Map<String, List<Track>> = refs.entries.sortedByDescending { byKey[it.key]?.score ?: 0.0 }.take(10).map { (key, ref) ->
            async { key to (fetch("top tracks of ${ref.name}") { directory.topTracks(ref, 8) } ?: emptyList()) }
        }.awaitAll().toMap().mapValues { (_, list) -> list.filterNot { input.owned.hasSong(it.artist, it.title) } }

        val albums = pickAlbums(topSimilar, similarAlbums, input, rules)

        val day = input.today.toEpochDay()
        val mixes = mutableListOf<Mix>()
        releaseRadar(newReleases, input, rules)?.let(mixes::add)
        mixes += dailyMixes(seeds, rules, input, similar, similarTracks, seedTracks, day)
        discoverMix(topSimilar, similarTracks, input, rules, day)?.let(mixes::add)
        mixes += becauseMixes(seeds, refs, similar, similarTracks, seedTracks, input, rules, day)
        mixes += input.extraMixes.map { m -> m.copy(tracks = m.tracks.filter(rules::allows)) }.filter { it.tracks.isNotEmpty() }

        val rediscover = rediscover(input, rules)
        val offline = successes.get() == 0 && failures.get() > 0
        log("Feed: ${newReleases.size} new releases, ${mixes.size} mixes, ${albums.size} albums, ${missing.size} missing, ${rediscover.size} to rediscover${if (offline) " (offline)" else ""}")
        Feed(
            builtAt = input.now,
            releases = newReleases,
            mixes = mixes.filter { it.tracks.isNotEmpty() },
            albums = albums,
            missing = missing,
            rediscover = rediscover,
            seeds = seeds.map { it.name },
            offline = offline,
        )
    }

    /** The catalogue artist for a name: remembered, or looked up (and "not found" remembered too, but not a failed lookup). */
    private suspend fun resolve(name: String): ArtistRef? {
        val key = Credits.key(name)
        cache.lookup(key)?.let { return it.getOrNull() }
        val result = gate.withPermit {
            try {
                Result.success(directory.find(name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val error = result.exceptionOrNull()
        if (error != null) {
            failures.incrementAndGet()
            log("find $name: ${error.message}")
            return null
        }
        successes.incrementAndGet()
        cache.store(key, result.getOrNull())
        return result.getOrNull()
    }

    private fun pickNewReleases(releases: Map<String, List<Collection>>, seeds: Map<String, ArtistScore>, input: Input, rules: Rules): List<Pick> {
        val from = input.today.minusDays(input.releaseWindowDays.toLong())
        return releases.flatMap { (key, list) ->
            val seed = seeds[key] ?: return@flatMap emptyList()
            list.mapNotNull { c ->
                val date = c.releaseDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@mapNotNull null
                if (date.isBefore(from) || date.isAfter(input.today.plusDays(1))) return@mapNotNull null
                val withArtist = c.copy(subtitle = c.subtitle ?: seed.name)
                if (input.owned.hasAlbum(withArtist.subtitle, c.title) || !rules.allows(withArtist)) return@mapNotNull null
                Triple(date, seed.score, Pick(withArtist, seed.name, kindLabel(c.recordType), Keys.album(withArtist.subtitle, c.title)))
            }
        }
            .sortedWith(compareByDescending<Triple<LocalDate, Double, Pick>> { it.first }.thenByDescending { it.second })
            .map { it.third }
            .distinctBy { it.key }
            .take(40)
    }

    private fun pickMissing(releases: Map<String, List<Collection>>, seeds: Map<String, ArtistScore>, input: Input, rules: Rules): List<Pick> {
        if (input.owned.isEmpty) return emptyList()
        val recent = input.today.minusDays(input.releaseWindowDays.toLong())
        return releases.entries
            .mapNotNull { (key, list) -> seeds[key]?.let { it to list } }
            .filter { (seed, _) -> input.owned.hasArtist(seed.name) }
            .sortedByDescending { it.first.score }
            .flatMap { (seed, list) ->
                list.filter { c ->
                    c.recordType == null || c.recordType == "album"
                }.filter { c ->
                    val date = c.releaseDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                    date == null || date.isBefore(recent)
                }.map { it.copy(subtitle = it.subtitle ?: seed.name) }
                    .filter { !input.owned.hasAlbum(it.subtitle, it.title) && rules.allows(it) }
                    .distinctBy { Keys.album(it.subtitle, it.title) }
                    .take(2)
                    .map { Pick(it, seed.name, "You have other music by ${seed.name}", Keys.album(it.subtitle, it.title)) }
            }
            .take(24)
    }

    private class Similar(val ref: ArtistRef, val score: Double, val because: String, val seedKey: String)

    private suspend fun findSimilar(
        seeds: List<ArtistScore>,
        refs: Map<String, ArtistRef>,
        input: Input,
        rules: Rules,
    ): List<Similar> = coroutineScope {
        class Tally(var score: Double, var because: ArtistScore, var best: Double, var ref: ArtistRef?, val name: String)
        val tally = ConcurrentHashMap<String, Tally>()
        fun add(name: String, ref: ArtistRef?, weight: Double, seed: ArtistScore) {
            val key = Credits.key(name)
            if (key.isEmpty() || input.profile.knows(name) || input.owned.hasArtist(name) || !rules.allowsArtist(name)) return
            synchronized(tally) {
                val t = tally.getOrPut(key) { Tally(0.0, seed, 0.0, ref, name) }
                t.score += weight
                if (weight > t.best) {
                    t.best = weight
                    t.because = seed
                }
                if (t.ref == null) t.ref = ref
            }
        }
        val asked = seeds.take(12)
        asked.map { seed ->
            async {
                refs[seed.key]?.let { ref ->
                    val list = fetch("similar to ${seed.name}") { directory.similar(ref) } ?: emptyList()
                    list.take(20).forEachIndexed { i, s -> add(s.name, s, seed.score * (1.0 - i / 25.0), seed) }
                }
                for (source in similarNames) {
                    val names = fetch("more similar to ${seed.name}") { source.similar(seed.name) } ?: emptyList()
                    names.take(15).forEachIndexed { i, n -> add(n, null, seed.score * 0.8 * (1.0 - i / 20.0), seed) }
                }
            }
        }.awaitAll()
        // Names without a catalogue entry yet: look up the strongest ones.
        val ranked = tally.values.sortedByDescending { it.score }.take(24)
        ranked.filter { it.ref == null }.map { t ->
            async { t.ref = resolve(t.name) }
        }.awaitAll()
        ranked.mapNotNull { t -> t.ref?.let { Similar(it, t.score, t.because.name, t.because.key) } }
            .filter { rules.allowsArtist(it.ref.name) }
            .distinctBy { it.ref.id }
    }

    private fun pickAlbums(similar: List<Similar>, albums: Map<String, List<Collection>>, input: Input, rules: Rules): List<Pick> =
        similar.mapNotNull { s ->
            albums[s.ref.id].orEmpty()
                .map { it.copy(subtitle = it.subtitle ?: s.ref.name) }
                .firstOrNull { !input.owned.hasAlbum(it.subtitle, it.title) && rules.allows(it) }
                ?.let { Pick(it, s.ref.name, "Because you play ${s.because}", Keys.album(it.subtitle, it.title)) }
        }.take(20)

    private suspend fun releaseRadar(
        releases: List<Pick>,
        input: Input,
        rules: Rules,
    ): Mix? = coroutineScope {
        if (releases.isEmpty()) return@coroutineScope null
        val tracks = releases.take(15).map { pick ->
            async {
                val list = fetch("tracks of ${pick.collection.title}") { directory.tracks(pick.collection) } ?: emptyList()
                list.filter(rules::allows).filterNot { input.owned.hasSong(it.artist, it.title) }.take(if (pick.collection.recordType == "single") 1 else 2)
            }
        }.awaitAll().flatten()
        if (tracks.isEmpty()) return@coroutineScope null
        Mix("release-radar", "Release Radar", "New songs from artists you play", spread(tracks.distinctBy { Keys.track(it.artist, it.title) }))
    }

    private fun dailyMixes(
        seeds: List<ArtistScore>,
        rules: Rules,
        input: Input,
        similar: List<Similar>,
        similarTracks: Map<String, List<Track>>,
        seedTracks: Map<String, List<Track>>,
        day: Long,
    ): List<Mix> {
        val groups = group(seeds, rules)
        return groups.mapIndexedNotNull { i, group ->
            val keys = group.map { it.key }.toSet()
            val familiar = input.familiar.filter { p -> Credits.keys(p.track.artist, p.track.title).any { it in keys } }.map { it.track }
            val fresh = group.flatMap { seedTracks[it.key].orEmpty() } +
                similar.filter { it.seedKey in keys }.flatMap { similarTracks[it.ref.id].orEmpty() }
            val tracks = compose(familiar, fresh, input, rules, day * 31 + i)
            if (tracks.size < 5) return@mapIndexedNotNull null
            val names = group.sortedByDescending { it.score }.map { it.name }
            Mix(
                id = "daily-${i + 1}",
                title = "Daily Mix ${i + 1}",
                subtitle = names.take(3).joinToString(", ") + if (names.size > 3) " and more" else "",
                tracks = tracks,
            )
        }
    }

    /** Seed artists in up to three groups that sound alike: by genre where known, otherwise by rank. */
    private fun group(seeds: List<ArtistScore>, rules: Rules): List<List<ArtistScore>> {
        if (seeds.isEmpty()) return emptyList()
        val genreOf = seeds.associate { s -> s.key to (rules.artistGenres[s.key]?.firstOrNull() ?: s.genres.firstOrNull()) }
        val byGenre = seeds.filter { genreOf[it.key] != null }.groupBy { genreOf[it.key]!! }
            .entries.sortedByDescending { e -> e.value.sumOf { it.score } }
        val groups: List<MutableList<ArtistScore>> = if (byGenre.size >= 2) {
            byGenre.take(3).map { it.value.toMutableList() }
        } else {
            val count = ((seeds.size + 3) / 4).coerceIn(1, 3)
            List(count) { mutableListOf() }
        }
        val placed = groups.flatten().map { it.key }.toSet()
        // Artists without a genre (or beyond the third genre) go round the groups, strongest first.
        seeds.filter { it.key !in placed }.forEachIndexed { i, s -> groups[i % groups.size] += s }
        return groups.filter { it.isNotEmpty() }
    }

    private fun discoverMix(similar: List<Similar>, tracks: Map<String, List<Track>>, input: Input, rules: Rules, day: Long): Mix? {
        val fresh = similar.flatMap { s -> tracks[s.ref.id].orEmpty().take(3) }
            .filter(rules::allows)
            .filterNot { input.owned.hasSong(it.artist, it.title) }
            .distinctBy { Keys.track(it.artist, it.title) }
        if (fresh.size < 5) return null
        val list = limitPerArtist(fresh.shuffled(Random(day * 7 + 3)), 2).take(MIX_SIZE)
        return Mix("discover", "Discover", "Artists new to you", spread(list))
    }

    private suspend fun becauseMixes(
        seeds: List<ArtistScore>,
        refs: Map<String, ArtistRef>,
        similar: List<Similar>,
        similarTracks: Map<String, List<Track>>,
        seedTracks: Map<String, List<Track>>,
        input: Input,
        rules: Rules,
        day: Long,
    ): List<Mix> = coroutineScope {
        seeds.filter { it.key in refs }.take(2).mapIndexed { i, seed ->
            async {
                val own = input.familiar.firstOrNull { p -> Credits.keys(p.track.artist, p.track.title).contains(seed.key) }?.track
                val radioTracks = if (radio != null && own != null) {
                    fetch("radio of ${own.title}") { radio.invoke(own) } ?: emptyList()
                } else {
                    emptyList()
                }
                // Mostly artists like them, with a few of their own songs.
                val fresh = seedTracks[seed.key].orEmpty().take(2) +
                    similar.filter { it.seedKey == seed.key }.flatMap { similarTracks[it.ref.id].orEmpty().take(3) } +
                    radioTracks
                val familiar = input.familiar.filter { p -> Credits.keys(p.track.artist, p.track.title).contains(seed.key) }.map { it.track }.take(3)
                val tracks = compose(familiar, fresh, input.copy(discover = maxOf(input.discover, 0.6)), rules, day * 13 + i)
                if (tracks.size < 5) null else Mix("because-${seed.key.replace(' ', '-')}", "Because you play ${seed.name}", "${seed.name} and artists like them", tracks)
            }
        }.awaitAll().filterNotNull()
    }

    private fun rediscover(input: Input, rules: Rules): List<Track> {
        val cutoff = input.now - 45L * 86_400_000
        return input.familiar
            .filter { it.plays >= 3 && (it.lastPlayedAt ?: 0) in 1 until cutoff }
            .sortedByDescending { it.plays }
            .map { it.track }
            .filter(rules::allows)
            .distinctBy { Keys.track(it.artist, it.title) }
            .let { limitPerArtist(it, 3) }
            .take(30)
    }

    /**
     * A mix of songs the user knows and songs new to them, in the
     * proportion [Input.discover] asks for, at most three by one artist
     * and never the same artist twice in a row where it can be helped.
     */
    private fun compose(familiar: List<Track>, fresh: List<Track>, input: Input, rules: Rules, seed: Long): List<Track> {
        val random = Random(seed)
        val known = familiar.filter(rules::allows).distinctBy { Keys.track(it.artist, it.title) }
        val knownKeys = known.map { Keys.track(it.artist, it.title) }.toSet()
        val new = fresh.filter(rules::allows)
            .filterNot { input.owned.hasSong(it.artist, it.title) }
            .distinctBy { Keys.track(it.artist, it.title) }
            .filter { Keys.track(it.artist, it.title) !in knownKeys }
        var freshCount = (MIX_SIZE * (0.15 + 0.7 * input.discover.coerceIn(0.0, 1.0))).roundToInt()
        freshCount = minOf(freshCount, new.size)
        val familiarCount = minOf(MIX_SIZE - freshCount, known.size)
        if (familiarCount < MIX_SIZE - freshCount) freshCount = minOf(new.size, MIX_SIZE - familiarCount)
        // No artist takes over: three songs each, or more when there are only a few artists to choose from.
        val artists = (known + new).map { Credits.key(Keys.primary(it.artist)) }.toSet().size.coerceAtLeast(1)
        val cap = ((MIX_SIZE + artists - 1) / artists).coerceIn(3, 10)
        // Favourites first, but not always the same ones: a shuffled pick from the most played.
        val pickedKnown = limitPerArtist(known.take(maxOf(familiarCount * 2, 10)).shuffled(random), cap).take(familiarCount)
        val counts = pickedKnown.groupingBy { Credits.key(Keys.primary(it.artist)) }.eachCount().toMutableMap()
        val pickedNew = limitPerArtist(new.shuffled(random), 2).filter { t ->
            val key = Credits.key(Keys.primary(t.artist))
            val n = counts[key] ?: 0
            counts[key] = n + 1
            n < cap
        }.take(freshCount)
        return spread((pickedKnown + pickedNew).shuffled(random))
    }

    companion object {
        const val MIX_SIZE = 30

        fun kindLabel(recordType: String?): String = when (recordType) {
            "single" -> "Single"
            "ep" -> "EP"
            "compile" -> "Compilation"
            else -> "Album"
        }

        fun limitPerArtist(tracks: List<Track>, max: Int): List<Track> {
            val counts = HashMap<String, Int>()
            return tracks.filter { t ->
                val key = Credits.key(Keys.primary(t.artist))
                val n = counts[key] ?: 0
                counts[key] = n + 1
                n < max
            }
        }

        /** Reorders so the same artist doesn't play twice in a row, where another can go between. */
        fun spread(tracks: List<Track>): List<Track> {
            val left = tracks.toMutableList()
            val out = ArrayList<Track>(tracks.size)
            var last: String? = null
            while (left.isNotEmpty()) {
                val i = left.indexOfFirst { Credits.key(Keys.primary(it.artist)) != last }.takeIf { it >= 0 } ?: 0
                val next = left.removeAt(i)
                out += next
                last = Credits.key(Keys.primary(next.artist))
            }
            return out
        }
    }
}
