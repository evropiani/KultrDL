package app.kultr.dl.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track

/**
 * Every track the app has touched: saved, favourited, downloaded, played,
 * or just queued. Catalogue metadata lives alongside what the user did
 * with it, and [matchedUrl] remembers which recording plays it.
 */
@Entity(tableName = "tracks", indices = [Index("favorite"), Index("saved"), Index("lastPlayedAt")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val source: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val pageUrl: String? = null,
    val streamUrl: String? = null,
    val matchUrl: String? = null,
    val isrc: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val explicit: Boolean = false,
    val matchedUrl: String? = null,
    val favorite: Boolean = false,
    val favoritedAt: Long? = null,
    val saved: Boolean = false,
    val savedAt: Long? = null,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val localUri: String? = null,
    val localFormat: String? = null,
    val localSize: Long? = null,
    val downloadedAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun toTrack(): Track = Track(
        id = id,
        source = runCatching { Source.valueOf(source) }.getOrDefault(Source.WEB),
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        pageUrl = pageUrl,
        streamUrl = streamUrl,
        matchUrl = matchUrl,
        isrc = isrc,
        year = year,
        trackNumber = trackNumber,
        discNumber = discNumber,
        genre = genre,
        explicit = explicit,
    )

    /** New catalogue details over what is stored, keeping what the user did with it. */
    fun withMetadata(t: Track): TrackEntity = copy(
        source = t.source.name,
        title = t.title,
        artist = t.artist,
        album = t.album ?: album,
        albumArtist = t.albumArtist ?: albumArtist,
        durationMs = t.durationMs ?: durationMs,
        artworkUrl = t.artworkUrl ?: artworkUrl,
        pageUrl = t.pageUrl ?: pageUrl,
        streamUrl = t.streamUrl ?: streamUrl,
        matchUrl = t.matchUrl ?: matchUrl,
        isrc = t.isrc ?: isrc,
        year = t.year ?: year,
        trackNumber = t.trackNumber ?: trackNumber,
        discNumber = t.discNumber ?: discNumber,
        genre = t.genre ?: genre,
        explicit = t.explicit || explicit,
    )

    companion object {
        fun from(t: Track): TrackEntity = TrackEntity(id = t.id, source = t.source.name, title = t.title, artist = t.artist).withMetadata(t)
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** The page it was imported from, if it was. */
    val sourceUrl: String? = null,
    val artworkUrl: String? = null,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"], indices = [Index("trackId")])
data class PlaylistTrackEntity(
    val playlistId: Long,
    val position: Int,
    val trackId: String,
)

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * A job in the download queue. [destination] (a [app.kultr.dl.data.Destination]
 * as JSON) sends the file to a server; [upload] means the track is already on
 * the phone and only needs sending. When a job for a server is done,
 * [message] says where the file went.
 */
@Entity(tableName = "downloads", indices = [Index("state")])
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val state: String,
    val format: String,
    val quality: String,
    val progress: Float = 0f,
    val message: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val destination: String? = null,
    @ColumnInfo(defaultValue = "0") val upload: Boolean = false,
) {
    val downloadState: DownloadState get() = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED)
}

/**
 * One listen, as the player saw it: how long it played and whether it was
 * finished or skipped. Skips tell the recommendations what not to suggest.
 */
@Entity(tableName = "plays", indices = [Index("trackId"), Index("startedAt")])
data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val artist: String,
    val title: String,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
)

/** Where a song the user owns lives. */
enum class Owner { PHONE, NAVIDROME }

/**
 * A song in the user's own collection: a music file on the phone, or a
 * song on their Navidrome server with its play count, star and rating.
 */
@Entity(tableName = "owned_songs", indices = [Index("owner"), Index("artist")])
data class OwnedSongEntity(
    @PrimaryKey val id: String,
    val owner: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val starred: Boolean,
    val rating: Int,
    val artworkUrl: String?,
    /** A content:// URI for a file on the phone; the stream address (without login) on Navidrome. */
    val streamUrl: String?,
    /** The server's id for the artist, to ask it for similar artists. */
    val artistId: String?,
    val addedAt: Long?,
) {
    fun toTrack(): Track = Track(
        id = id,
        source = if (owner == Owner.NAVIDROME.name) Source.NAVIDROME else Source.PHONE,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        year = year,
        trackNumber = trackNumber,
        genre = genre,
    )
}

@Entity(tableName = "searches")
data class SearchEntity(
    @PrimaryKey val query: String,
    val at: Long = System.currentTimeMillis(),
)

/** A playlist row with its size, for lists. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val artworkUrl: String?,
    val updatedAt: Long,
    val count: Int,
)

/** A download row with the track it is for. */
data class DownloadWithTrack(
    val trackId: String,
    val state: String,
    val format: String,
    val quality: String,
    val progress: Float,
    val message: String?,
    val updatedAt: Long,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val localUri: String?,
    val destination: String?,
    val upload: Boolean,
)

/** What the user has done with a track, for hearts and badges in lists. */
data class TrackFlags(
    val id: String,
    val favorite: Boolean,
    val saved: Boolean,
    val localUri: String?,
)
