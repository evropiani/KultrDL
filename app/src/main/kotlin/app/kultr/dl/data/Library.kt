package app.kultr.dl.data

import androidx.room.withTransaction
import app.kultr.dl.core.model.Track
import app.kultr.dl.data.db.KultrDLDatabase
import app.kultr.dl.data.db.OwnedSongEntity
import app.kultr.dl.data.db.PlayEntity
import app.kultr.dl.data.db.PlaylistEntity
import app.kultr.dl.data.db.PlaylistSummary
import app.kultr.dl.data.db.PlaylistTrackEntity
import app.kultr.dl.data.db.SearchEntity
import app.kultr.dl.data.db.TrackEntity
import app.kultr.dl.data.db.TrackFlags
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The user's library: favourites, saved tracks, playlists, downloads and
 * listening history, all on the phone.
 */
class Library(private val db: KultrDLDatabase, scope: CoroutineScope) {
    private val tracks = db.tracks()
    private val playlists = db.playlists()

    /** Tracks handed to the player, so the playback service can find them at once. */
    private val registry = ConcurrentHashMap<String, Track>()

    val flags: StateFlow<Map<String, TrackFlags>> = tracks.flags()
        .map { list -> list.associateBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val favorites: Flow<List<Track>> = tracks.favorites().map { it.map(TrackEntity::toTrack) }
    val saved: Flow<List<Track>> = tracks.saved().map { it.map(TrackEntity::toTrack) }
    val downloaded: Flow<List<TrackEntity>> = tracks.downloaded()
    val playlistSummaries: Flow<List<PlaylistSummary>> = playlists.summaries()
    val recentSearches: Flow<List<String>> = db.searches().recent(12)

    fun history(limit: Int = 100): Flow<List<Track>> = tracks.history(limit).map { it.map(TrackEntity::toTrack) }

    fun mostPlayed(limit: Int = 20): Flow<List<Track>> = tracks.mostPlayed(limit).map { it.map(TrackEntity::toTrack) }

    fun observe(id: String): Flow<TrackEntity?> = tracks.observe(id)

    suspend fun entity(id: String): TrackEntity? = tracks.get(id)

    suspend fun track(id: String): Track? = registry[id] ?: tracks.get(id)?.toTrack()

    /** Store (or refresh) tracks without touching what the user did with them. */
    suspend fun remember(list: List<Track>) {
        if (list.isEmpty()) return
        list.forEach { registry[it.id] = it }
        db.withTransaction {
            val existing = tracks.getAll(list.map { it.id }.distinct()).associateBy { it.id }
            for (t in list.distinctBy { it.id }) {
                val old = existing[t.id]
                if (old == null) tracks.insertIgnore(TrackEntity.from(t)) else tracks.update(old.withMetadata(t))
            }
        }
    }

    suspend fun setFavorite(list: List<Track>, on: Boolean) {
        remember(list)
        val now = System.currentTimeMillis()
        list.forEach { tracks.setFavorite(it.id, on, if (on) now else null) }
    }

    suspend fun setSaved(list: List<Track>, on: Boolean) {
        remember(list)
        val now = System.currentTimeMillis()
        list.forEach { tracks.setSaved(it.id, on, if (on) now else null) }
    }

    suspend fun markPlayed(id: String) = tracks.markPlayed(id, System.currentTimeMillis())

    suspend fun clearHistory() {
        tracks.clearHistory()
        db.plays().clear()
    }

    // ------------------------------------------------- for recommendations --

    suspend fun recordPlay(play: PlayEntity) {
        db.plays().insert(play)
        // A year of listens is plenty for taste; older ones go.
        if (kotlin.random.Random.nextInt(200) == 0) db.plays().prune(System.currentTimeMillis() - 400L * 86_400_000)
    }

    suspend fun playsSince(since: Long): List<PlayEntity> = db.plays().since(since)

    /** Swap in a fresh list of the songs on the phone or on Navidrome. */
    suspend fun replaceOwned(owner: String, songs: List<OwnedSongEntity>) = db.withTransaction {
        db.owned().clear(owner)
        songs.chunked(500).forEach { db.owned().insertAll(it) }
    }

    /** Everything the user has played, hearted, saved or downloaded. */
    suspend fun known(): List<TrackEntity> = tracks.known()

    suspend fun playlistsWithTracks(): Map<PlaylistEntity, List<String>> {
        val entries = playlists.allEntries().groupBy { it.playlistId }
        return playlists.all().associateWith { p -> entries[p.id].orEmpty().sortedBy { it.position }.map { it.trackId } }
    }

    /** Replace a followed mix's tracks with today's. */
    suspend fun replacePlaylistTracks(id: Long, list: List<Track>) {
        remember(list)
        db.withTransaction { writeOrder(id, list.map { it.id }) }
    }

    suspend fun setMatchedUrl(id: String, url: String?) = tracks.setMatchedUrl(id, url)

    suspend fun setLocal(id: String, uri: String?, format: String?, size: Long?) =
        tracks.setLocal(id, uri, format, size, if (uri != null) System.currentTimeMillis() else null)

    // ------------------------------------------------------------ playlists --

    fun playlist(id: Long): Flow<PlaylistEntity?> = playlists.observe(id)

    fun playlistTracks(id: Long): Flow<List<Track>> = playlists.tracks(id).map { it.map(TrackEntity::toTrack) }

    suspend fun createPlaylist(name: String, tracksToAdd: List<Track> = emptyList(), sourceUrl: String? = null, artworkUrl: String? = null): Long {
        val id = playlists.insert(PlaylistEntity(name = name.trim().ifEmpty { "New playlist" }, sourceUrl = sourceUrl, artworkUrl = artworkUrl))
        if (tracksToAdd.isNotEmpty()) addToPlaylist(id, tracksToAdd)
        return id
    }

    suspend fun renamePlaylist(id: Long, name: String) {
        val p = playlists.get(id) ?: return
        playlists.update(p.copy(name = name.trim().ifEmpty { p.name }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePlaylist(id: Long) = db.withTransaction {
        playlists.clear(id)
        playlists.delete(id)
    }

    suspend fun addToPlaylist(id: Long, list: List<Track>) {
        remember(list)
        db.withTransaction {
            val current = playlists.trackIds(id)
            writeOrder(id, current + list.map { it.id })
        }
    }

    suspend fun removeFromPlaylist(id: Long, position: Int) = db.withTransaction {
        val current = playlists.trackIds(id).toMutableList()
        if (position in current.indices) current.removeAt(position)
        writeOrder(id, current)
    }

    suspend fun movePlaylistTrack(id: Long, from: Int, to: Int) = db.withTransaction {
        val current = playlists.trackIds(id).toMutableList()
        if (from !in current.indices || to !in current.indices) return@withTransaction
        current.add(to, current.removeAt(from))
        writeOrder(id, current)
    }

    private suspend fun writeOrder(id: Long, ids: List<String>) {
        playlists.clear(id)
        playlists.insertEntries(ids.mapIndexed { i, trackId -> PlaylistTrackEntity(id, i, trackId) })
        playlists.get(id)?.let { playlists.update(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    // --------------------------------------------------------------- backup --

    suspend fun snapshot(settings: Settings?): BackupFile {
        val keep = tracks.keepers()
        val entries = playlists.allEntries().groupBy { it.playlistId }
        return BackupFile(
            settings = settings,
            tracks = keep.map {
                BackupTrack(it.toTrack(), it.favorite, it.favoritedAt, it.saved, it.savedAt, it.playCount, it.lastPlayedAt, it.matchedUrl)
            },
            playlists = playlists.all().map { p ->
                BackupPlaylist(p.name, entries[p.id].orEmpty().sortedBy { it.position }.map { it.trackId }, p.sourceUrl, p.artworkUrl)
            },
        )
    }

    /** Merge a backup in: nothing already here is lost, and playlists are added alongside. */
    suspend fun restore(file: BackupFile): Int {
        db.withTransaction {
            for (b in file.tracks) {
                val old = tracks.get(b.track.id)
                val base = old?.withMetadata(b.track) ?: TrackEntity.from(b.track)
                tracks.upsert(
                    base.copy(
                        favorite = base.favorite || b.favorite,
                        favoritedAt = base.favoritedAt ?: b.favoritedAt,
                        saved = base.saved || b.saved,
                        savedAt = base.savedAt ?: b.savedAt,
                        playCount = maxOf(base.playCount, b.playCount),
                        lastPlayedAt = listOfNotNull(base.lastPlayedAt, b.lastPlayedAt).maxOrNull(),
                        matchedUrl = base.matchedUrl ?: b.matchedUrl,
                    ),
                )
            }
            val known = file.tracks.map { it.track.id }.toSet()
            for (p in file.playlists) {
                val id = playlists.insert(PlaylistEntity(name = p.name, sourceUrl = p.sourceUrl, artworkUrl = p.artworkUrl))
                playlists.insertEntries(p.trackIds.filter { it in known }.mapIndexed { i, trackId -> PlaylistTrackEntity(id, i, trackId) })
            }
        }
        return file.tracks.size
    }

    // ------------------------------------------------------------- searches --

    suspend fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.length >= 2) db.searches().upsert(SearchEntity(q))
    }

    suspend fun forgetSearch(query: String) = db.searches().delete(query)

    suspend fun clearSearches() = db.searches().clear()
}
