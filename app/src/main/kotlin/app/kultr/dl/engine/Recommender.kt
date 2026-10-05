package app.kultr.dl.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kultr.dl.KultrDLApp
import app.kultr.dl.MainActivity
import app.kultr.dl.R
import app.kultr.dl.core.Catalog
import app.kultr.dl.core.discover.ArtistIdCache
import app.kultr.dl.core.discover.ArtistRef
import app.kultr.dl.core.discover.CatalogDirectory
import app.kultr.dl.core.discover.Discovery
import app.kultr.dl.core.discover.Feed
import app.kultr.dl.core.discover.Karousel
import app.kultr.dl.core.discover.Keys
import app.kultr.dl.core.discover.Mix
import app.kultr.dl.core.discover.Owned
import app.kultr.dl.core.discover.Pick
import app.kultr.dl.core.discover.Played
import app.kultr.dl.core.discover.Rules
import app.kultr.dl.core.discover.SimilarNames
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.sources.LastFm
import app.kultr.dl.core.sources.ListenBrainz
import app.kultr.dl.core.sources.Subsonic
import app.kultr.dl.core.sources.YouTubeMusic
import app.kultr.dl.core.taste.Credits
import app.kultr.dl.core.taste.Signal
import app.kultr.dl.core.taste.Taste
import app.kultr.dl.core.util.LenientJson
import app.kultr.dl.data.Destination
import app.kultr.dl.data.Library
import app.kultr.dl.data.NavidromeRepository
import app.kultr.dl.data.ReleaseAlerts
import app.kultr.dl.data.Settings
import app.kultr.dl.data.SettingsRepository
import app.kultr.dl.data.TasteStore
import app.kultr.dl.data.Verdict
import app.kultr.dl.data.db.OwnedSongDao
import app.kultr.dl.data.db.OwnedSongEntity
import app.kultr.dl.data.db.Owner
import app.kultr.dl.data.describe
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Recommendations: learns what the user likes from KultrDL's own history
 * (plays, skips, hearts, saves, downloads, playlists), the music files on
 * the phone, their Navidrome server, Last.fm and ListenBrainz, and builds
 * the "For you" page once a day — new releases (with alerts), mixes,
 * albums to try, albums missing from their collection, and old favourites.
 */
class Recommender(
    private val context: Context,
    private val library: Library,
    private val owned: OwnedSongDao,
    private val settings: SettingsRepository,
    private val taste: TasteStore,
    private val navidrome: NavidromeRepository,
    private val catalog: Catalog,
    private val phone: PhoneMusic,
    private val http: Http,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Idle : Status
        data class Working(val step: String) : Status
        data class Failed(val message: String) : Status
    }

    private val dir = File(context.filesDir, "discover").apply { mkdirs() }
    private val feedFile = File(dir, "feed.json")
    private val cacheFile = File(dir, "cache.json")
    private val mutex = Mutex()
    private var cache: CacheData = readCache()

    private val raw = MutableStateFlow(readFeed())
    private val state = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = state

    /** The feed as last built, without anything blocked or dismissed since. */
    val feed: StateFlow<Feed?> = combine(raw, taste.data, settings.settings) { f, _, s -> f?.filtered(rules(s)) }
        .stateIn(scope, SharingStarted.Eagerly, raw.value?.filtered(rules(settings.settings.value)))

    val phoneSongs = owned.count(Owner.PHONE.name)
    val navidromeSongs = owned.count(Owner.NAVIDROME.name)

    private val lastFm = LastFm(http) { settings.settings.value.lastFmApiKey }
    private val listenBrainz = ListenBrainz(http)

    fun rules(s: Settings = settings.settings.value): Rules = Rules(taste.blocks.value, taste.dismissedKeys(), s.excludedGenres)

    /** Rebuild if the feed is more than a day old (on opening the app). */
    fun refreshIfStale() {
        val s = settings.settings.value
        if (!s.suggestions || state.value is Status.Working) return
        if (System.currentTimeMillis() - (raw.value?.builtAt ?: 0) < 20 * HOUR) return
        refreshInBackground()
    }

    fun refreshInBackground() {
        scope.launch {
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Recommendations failed", e)
            }
        }
    }

    suspend fun refresh(fromWorker: Boolean = false): Feed = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                build(fromWorker)
            } catch (e: CancellationException) {
                state.value = Status.Idle
                throw e
            } catch (e: Exception) {
                state.value = Status.Failed(describe(e))
                throw e
            }
        }
    }

    private suspend fun build(fromWorker: Boolean): Feed {
        val s = settings.settings.value
        val now = System.currentTimeMillis()
        if (s.usePhoneMusic && phone.hasPermission() && now - cache.phoneScannedAt > 12 * HOUR) {
            state.value = Status.Working("Reading the music on this phone")
            runCatching { scanPhone() }.onFailure { Log.w(TAG, "Phone music scan failed", it) }
        }
        val nav = navidrome.config.value
        if (nav.configured && nav.useHistory && now - nav.lastSyncAt > 20 * HOUR) {
            state.value = Status.Working("Reading your Navidrome")
            runCatching { syncNavidrome() }.onFailure { Log.w(TAG, "Navidrome sync failed", it) }
        }

        state.value = Status.Working("Learning what you like")
        val songs = owned.all().filter { song ->
            when (song.owner) {
                Owner.PHONE.name -> s.usePhoneMusic
                else -> navidrome.config.value.let { it.configured && it.useHistory }
            }
        }
        val known = library.known()
        val signals = signals(s, songs, known, now)
        val profile = Taste.build(signals, now)
        Log.i(TAG, "Recommendations: ${signals.size} signals, ${profile.artists.count { it.score > 0 }} artists; top ${profile.top(8).joinToString { it.name }}")

        val ownedIndex = Owned.Builder().apply {
            songs.forEach { add(it.artist, it.title, it.album, it.albumArtist) }
            known.filter { it.localUri != null || it.favorite || it.saved }.forEach { add(it.artist, it.title, it.album, it.albumArtist) }
        }.build()
        val playedById = known.associateBy { it.id }
        val familiar = (
            known.map { e -> Played(e.toTrack(), e.playCount + (if (e.favorite) 3 else 0), e.lastPlayedAt) } +
                songs.map { song ->
                    val inApp = playedById[song.id]
                    Played(song.toTrack(), song.playCount + (inApp?.playCount ?: 0) + (if (song.starred) 3 else 0), maxOf(song.lastPlayedAt ?: 0, inApp?.lastPlayedAt ?: 0).takeIf { it > 0 })
                }
            )
            .distinctBy { it.track.id }
            .sortedByDescending { it.plays }
            .take(4000)

        state.value = Status.Working("Looking for new music")
        val discovery = Discovery(
            directory = CatalogDirectory(catalog.deezer, catalog.apple) { settings.settings.value.useDeezer },
            similarNames = similarSources(s, songs),
            radio = if (s.useYouTubeRadio) ::radio else null,
            cache = artistCache,
            log = { Log.i(TAG, "Recommendations: $it") },
        )
        val feed = discovery.build(
            Discovery.Input(
                profile = profile,
                owned = ownedIndex,
                rules = rules(s),
                familiar = familiar,
                discover = s.discoverLevel.toDouble(),
                releaseWindowDays = s.releaseWindowDays,
                extraMixes = listenBrainzMixes(s),
                now = now,
            ),
        )
        val kept = if (feed.offline && raw.value?.let { !it.offline } == true) {
            // Offline: keep the last full feed, with today's offline mixes and rediscoveries.
            raw.value!!.copy(rediscover = feed.rediscover, mixes = raw.value!!.mixes.ifEmpty { feed.mixes })
        } else {
            feed
        }
        raw.value = kept
        writeFeed(kept)
        alerts(kept, s, now)
        updateFollowedMixes(kept)
        writeCache()
        state.value = Status.Idle
        Log.i(TAG, "Recommendations ready: ${kept.releases.size} new releases, ${kept.mixes.size} mixes, ${kept.albums.size} albums, ${kept.missing.size} missing${if (fromWorker) " (daily)" else ""}")
        return kept
    }

    /** Everything the user did, as evidence for their taste. */
    private suspend fun signals(s: Settings, songs: List<OwnedSongEntity>, known: List<app.kultr.dl.data.db.TrackEntity>, now: Long): List<Signal> {
        val out = ArrayList<Signal>()
        for (e in known) {
            if (e.playCount > 0) out += Signal(e.artist, e.title, e.playCount.toDouble(), e.lastPlayedAt, e.genre)
            if (e.favorite) out += Signal(e.artist, e.title, 4.0, null, e.genre)
            if (e.saved) out += Signal(e.artist, e.title, 2.0, null, e.genre)
            if (e.localUri != null) out += Signal(e.artist, e.title, 2.5, null, e.genre)
        }
        for (p in library.playsSince(now - 365 * DAY)) {
            when {
                p.skipped -> out += Signal(p.artist, p.title, -0.6, p.startedAt)
                p.completed -> out += Signal(p.artist, p.title, 0.3, p.startedAt)
            }
        }
        val byId = known.associateBy { it.id }
        for ((playlist, ids) in library.playlistsWithTracks()) {
            if (playlist.sourceUrl?.startsWith(MIX_URL) == true) continue
            ids.mapNotNull { byId[it] }.forEach { out += Signal(it.artist, it.title, 1.0, null, it.genre) }
        }
        for (song in songs) {
            if (song.owner == Owner.PHONE.name) {
                out += Signal(song.artist, song.title, 0.15, null, song.genre)
            } else {
                out += Signal(song.artist, song.title, 0.1, null, song.genre)
                if (song.playCount > 0) out += Signal(song.artist, song.title, song.playCount.toDouble(), song.lastPlayedAt ?: song.addedAt, song.genre)
                if (song.starred) out += Signal(song.artist, song.title, 4.0, null, song.genre)
                if (song.rating > 0) out += Signal(song.artist, song.title, (song.rating - 2.5) * 1.2, null, song.genre)
            }
        }
        if (s.lastFmUser.isNotBlank() && s.lastFmApiKey.isNotBlank()) {
            runCatching { lastFm.topArtists(s.lastFmUser.trim()) }
                .onSuccess { list -> list.forEach { (name, plays) -> out += Signal(name, weight = ln(1.0 + plays) * 1.5) } }
                .onFailure { Log.w(TAG, "Last.fm: ${it.message}") }
        }
        if (s.listenBrainzUser.isNotBlank()) {
            runCatching { listenBrainz.topArtists(s.listenBrainzUser.trim()) }
                .onSuccess { list -> list.forEach { (name, plays) -> out += Signal(name, weight = ln(1.0 + plays) * 1.5) } }
                .onFailure { Log.w(TAG, "ListenBrainz: ${it.message}") }
        }
        for (f in taste.data.value.feedback) {
            out += Signal(f.artist, weight = if (f.verdict == Verdict.LIKE) 5.0 else -1.5, at = f.at)
        }
        return out
    }

    private fun similarSources(s: Settings, songs: List<OwnedSongEntity>): List<SimilarNames> = buildList {
        if (lastFm.enabled) add(SimilarNames { artist -> lastFm.similar(artist) })
        val nav = navidrome.client(http)
        if (nav != null && navidrome.config.value.useHistory) {
            val ids = songs.filter { it.owner == Owner.NAVIDROME.name && it.artistId != null }
                .associate { Credits.key(it.artist) to it.artistId!! }
            add(SimilarNames { artist -> ids[Credits.key(artist)]?.let { nav.similarArtists(it) } ?: emptyList() })
        }
    }

    /** YouTube Music's radio after one of the user's songs, when it has a YouTube recording. */
    private suspend fun radio(track: Track): List<Track> {
        val url = track.streamUrl?.takeIf { track.source == Source.YOUTUBE_MUSIC || track.source == Source.YOUTUBE }
            ?: library.entity(track.id)?.matchedUrl
        val id = YouTubeMusic.videoId(url) ?: return emptyList()
        return catalog.youTubeMusic.radio(id).take(25)
    }

    /**
     * Karousel's next songs: music like [seeds] (what has been playing, the song now
     * playing first), leaving out [exclude] ([Keys.track] keys of the queue) and
     * whatever played in the last few hours.
     */
    suspend fun karousel(seeds: List<Track>, exclude: Set<String>, count: Int = 10): List<Track> = withContext(Dispatchers.IO) {
        val s = settings.settings.value
        val now = System.currentTimeMillis()
        val recent = library.playsSince(now - 3 * HOUR).map { Keys.track(it.artist, it.title) }
        val nav = navidrome.config.value
        val songs = owned.all().filter { if (it.owner == Owner.PHONE.name) s.usePhoneMusic && phone.hasPermission() else nav.configured }
        val known = library.known().filter { it.favorite || it.saved || it.localUri != null || it.playCount > 0 }
        // The user's own music, most played first: Karousel's fallback, and what it mixes in.
        val mine = (
            songs.map { it.toTrack() to it.playCount + (if (it.starred) 3 else 0) } +
                known.map { it.toTrack() to it.playCount + (if (it.favorite) 3 else 0) }
            )
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { Keys.track(it.artist, it.title) }
        Karousel(
            directory = CatalogDirectory(catalog.deezer, catalog.apple) { settings.settings.value.useDeezer },
            radio = if (s.useYouTubeRadio) ::radio else null,
            cache = artistCache,
            log = { Log.i(TAG, "Karousel: $it") },
        ).next(Karousel.Input(seeds, exclude + recent, rules(s), mine, count))
    }

    /** ListenBrainz's newest weekly playlists for the user, one of each kind. */
    private suspend fun listenBrainzMixes(s: Settings): List<Mix> {
        val user = s.listenBrainzUser.trim().ifEmpty { return emptyList() }
        return runCatching {
            listenBrainz.createdFor(user)
                .mapNotNull { p -> ListenBrainz.kind(p.title)?.let { it to p } }
                .distinctBy { it.first }
                .take(3)
                .mapNotNull { (kind, p) ->
                    val tracks = listenBrainz.playlist(p.id)
                    if (tracks.isEmpty()) null else Mix("lb-$kind", p.title.substringBefore(" for ").ifEmpty { p.title }, "From ListenBrainz", tracks)
                }
        }.onFailure { Log.w(TAG, "ListenBrainz playlists: ${it.message}") }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- sources --

    suspend fun scanPhone(): Int = withContext(Dispatchers.IO) {
        val songs = phone.scan()
        library.replaceOwned(Owner.PHONE.name, songs)
        cache = cache.copy(phoneScannedAt = System.currentTimeMillis())
        writeCache()
        Log.i(TAG, "Phone music: ${songs.size} songs")
        songs.size
    }

    suspend fun forgetPhone() = library.replaceOwned(Owner.PHONE.name, emptyList())

    /** Reads every song on the user's Navidrome, with the signed-in account's play counts, stars and ratings. */
    suspend fun syncNavidrome(): String = withContext(Dispatchers.IO) {
        val client = navidrome.client(http) ?: throw IllegalStateException("Navidrome isn't set up, or its password needs entering again.")
        try {
            val info = client.ping()
            val admin = try {
                client.isAdmin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val songs = client.songs()
            val entities = songs.map { song ->
                OwnedSongEntity(
                    id = "navidrome:${song.id}",
                    owner = Owner.NAVIDROME.name,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    albumArtist = song.albumArtist,
                    genre = song.genre,
                    year = song.year,
                    trackNumber = song.track,
                    durationMs = song.durationMs,
                    playCount = song.playCount,
                    lastPlayedAt = song.playedAt,
                    starred = song.starred,
                    rating = song.rating,
                    artworkUrl = song.coverArt?.let { client.coverUrl(it) },
                    streamUrl = client.streamUrl(song.id),
                    artistId = song.artistId,
                    addedAt = null,
                )
            }
            library.replaceOwned(Owner.NAVIDROME.name, entities)
            val played = songs.count { it.playCount > 0 }
            val note = "${entities.size} songs ($played played by ${client.server.username}) from $info"
            navidrome.update { it.copy(lastSyncAt = System.currentTimeMillis(), lastSync = note, songCount = entities.size, isAdmin = admin) }
            Log.i(TAG, "Navidrome: synced $note")
            note
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Navidrome sync failed: ${describe(e)}", e)
            navidrome.update { it.copy(lastSync = "Couldn't sync: ${describe(e)}") }
            throw e
        }
    }

    suspend fun forgetNavidrome() = library.replaceOwned(Owner.NAVIDROME.name, emptyList())

    /** After downloads reached Navidrome's music folder: have it look for them now. */
    suspend fun afterUploads(destinations: Set<Destination>) {
        val c = navidrome.config.value
        val target = c.destination ?: return
        if (!c.rescan || destinations.none { it.serverId == target.serverId && it.folder == target.folder }) return
        val client = navidrome.scanClient(http) ?: return
        val note = try {
            client.startScan()
            "Rescan started after downloads"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Subsonic.SubsonicException) {
            if (e.code == 50) "Couldn't start a rescan: only admins can, and ${client.server.username} isn't one. Add an admin login for rescans."
            else "Couldn't start a rescan: ${describe(e)}"
        } catch (e: Exception) {
            "Couldn't start a rescan: ${describe(e)}"
        }
        Log.i(TAG, "Navidrome: $note")
        navidrome.update { it.copy(lastScan = note) }
    }

    // -------------------------------------------------------------- alerts --

    private fun alerts(feed: Feed, s: Settings, now: Long) {
        val fresh = feed.releases.filter { it.key !in cache.seenReleases }
        val firstRun = cache.seenReleases.isEmpty()
        cache = cache.copy(seenReleases = (cache.seenReleases + fresh.associate { it.key to now }).filterValues { now - it < 200 * DAY })
        if (firstRun || fresh.isEmpty() && cache.pending.isEmpty()) return
        val items = fresh.map { Alert(it.key, it.artist, it.collection.title, it.reason) }
        when (s.releaseAlerts) {
            ReleaseAlerts.OFF -> Unit
            ReleaseAlerts.AS_THEY_COME -> if (items.isNotEmpty()) notify(items)
            ReleaseAlerts.WEEKLY -> {
                val pending = (cache.pending + items).distinctBy { it.key }
                if (now - cache.lastSummaryAt >= 7 * DAY && pending.isNotEmpty()) {
                    notify(pending)
                    cache = cache.copy(pending = emptyList(), lastSummaryAt = now)
                } else {
                    cache = cache.copy(pending = pending)
                }
            }
        }
    }

    // The POST_NOTIFICATIONS check is just below; lint can't see it through the early return.
    @SuppressLint("MissingPermission")
    private fun notify(items: List<Alert>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_HOME, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (items.size == 1) "New from ${items[0].artist}" else "${items.size} new releases from artists you play"
        val text = if (items.size == 1) "${items[0].title} · ${items[0].type}" else items.take(3).joinToString(", ") { "${it.artist} – ${it.title}" }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kultrdl)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.InboxStyle().also { style -> items.take(7).forEach { style.addLine("${it.artist} – ${it.title} (${it.type})") } })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        Log.i(TAG, "New releases: $title")
    }

    /** Playlists saved from a mix with "Keep updated" get today's songs. */
    private suspend fun updateFollowedMixes(feed: Feed) {
        val mixes = feed.mixes.associateBy { it.id }
        for (playlist in library.playlistsWithTracks().keys) {
            val id = playlist.sourceUrl?.takeIf { it.startsWith(MIX_URL) }?.removePrefix(MIX_URL) ?: continue
            val mix = mixes[id] ?: continue
            library.replacePlaylistTracks(playlist.id, mix.tracks)
        }
    }

    // ----------------------------------------------------- feed and caches --

    @Serializable
    data class CachedArtist(val ref: ArtistRef? = null, val at: Long = 0)

    @Serializable
    data class Alert(val key: String, val artist: String, val title: String, val type: String)

    @Serializable
    data class CacheData(
        val artists: Map<String, CachedArtist> = emptyMap(),
        val seenReleases: Map<String, Long> = emptyMap(),
        val pending: List<Alert> = emptyList(),
        val lastSummaryAt: Long = 0,
        val phoneScannedAt: Long = 0,
    )

    /** Which Deezer/Apple artist a name is: found ones for four months, "not found" for three weeks. */
    private val artistCache = object : ArtistIdCache {
        override fun lookup(key: String): Result<ArtistRef?>? {
            val hit = cache.artists[key] ?: return null
            val age = System.currentTimeMillis() - hit.at
            return if ((hit.ref != null && age < 120 * DAY) || (hit.ref == null && age < 21 * DAY)) Result.success(hit.ref) else null
        }

        override fun store(key: String, ref: ArtistRef?) {
            synchronized(this@Recommender) { cache = cache.copy(artists = cache.artists + (key to CachedArtist(ref, System.currentTimeMillis()))) }
        }
    }

    private fun readFeed(): Feed? = runCatching { LenientJson.decodeFromString(Feed.serializer(), feedFile.readText()) }.getOrNull()

    private fun writeFeed(feed: Feed) = runCatching { feedFile.writeText(LenientJson.encodeToString(Feed.serializer(), feed)) }

    private fun readCache(): CacheData = runCatching { LenientJson.decodeFromString(CacheData.serializer(), cacheFile.readText()) }.getOrDefault(CacheData())

    private fun writeCache() = runCatching { cacheFile.writeText(LenientJson.encodeToString(CacheData.serializer(), cache)) }

    /** Run once a day in the background, within the user's limits. */
    fun schedule() {
        val s = settings.settings.value
        val manager = WorkManager.getInstance(context)
        if (!s.suggestions) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.suggestionsOnWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.suggestionsWhileCharging)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<DiscoverWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS).setConstraints(constraints).build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** A mix as a collection page, and the playlist "Keep updated" makes from it. */
    fun mixUrl(mix: Mix) = MIX_URL + mix.id

    fun releaseKeyOf(pick: Pick) = pick.key

    companion object {
        private const val TAG = "KultrDL"
        const val CHANNEL = "releases"
        const val NOTIFICATION_ID = 3001
        const val WORK_NAME = "kultrdl-discover"
        const val MIX_URL = "kultrdl:mix:"
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
    }
}

class DiscoverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        KultrDLApp.graph.recommender.refresh(fromWorker = true)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("KultrDL", "Daily recommendations failed", e)
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
