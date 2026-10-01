package app.kultr.dl.core.model

import kotlinx.serialization.Serializable

/**
 * Where a track or collection was found.
 *
 * Streaming sources ([streams] = true) carry audio that yt-dlp can fetch
 * directly. The others are catalogues: their tracks are played and
 * downloaded from the matching recording on YouTube Music, with the
 * catalogue's own title, artist, album and artwork kept for tags.
 */
@Serializable
enum class Source(val label: String, val streams: Boolean, val searchable: Boolean) {
    YOUTUBE_MUSIC("YouTube Music", streams = true, searchable = true),
    YOUTUBE("YouTube", streams = true, searchable = true),
    SPOTIFY("Spotify", streams = false, searchable = true),
    APPLE_MUSIC("Apple Music", streams = false, searchable = true),
    DEEZER("Deezer", streams = false, searchable = true),
    SOUNDCLOUD("SoundCloud", streams = true, searchable = true),
    BANDCAMP("Bandcamp", streams = true, searchable = true),
    TIDAL("Tidal", streams = false, searchable = false),
    QOBUZ("Qobuz", streams = false, searchable = false),
    AMAZON_MUSIC("Amazon Music", streams = false, searchable = false),
    WEB("Web", streams = true, searchable = false),
    /** Songs on the user's Navidrome (Subsonic) server, streamed from it. */
    NAVIDROME("Navidrome", streams = true, searchable = false),
    /** Music files already on the phone. */
    PHONE("This phone", streams = true, searchable = false),
    /** Tracks from ListenBrainz's weekly playlists: names only, matched like a catalogue. */
    LISTENBRAINZ("ListenBrainz", streams = false, searchable = false),
    ;

    companion object {
        val searchSources: List<Source> = entries.filter { it.searchable }
    }
}

@Serializable
data class Track(
    /** Stable across searches: "<source>:<id on that source>". */
    val id: String,
    val source: Source,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    /** The track's page on its source, for sharing and "open in". */
    val pageUrl: String? = null,
    /** A URL yt-dlp can play and download directly; null for catalogue tracks. */
    val streamUrl: String? = null,
    /** A recording known to be this track (from a link service), tried before searching. */
    val matchUrl: String? = null,
    val isrc: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val explicit: Boolean = false,
) {
    val needsMatch: Boolean get() = streamUrl == null
}

@Serializable
enum class CollectionKind { ALBUM, PLAYLIST }

@Serializable
data class Collection(
    val id: String,
    val source: Source,
    val kind: CollectionKind,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val pageUrl: String? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
    /** "2026-09-26", when the source gives the full date. */
    val releaseDate: String? = null,
    /** "album", "single", "ep" or "compile", when the source says. */
    val recordType: String? = null,
    val genre: String? = null,
    /** Empty until loaded (search results list albums without their tracks). */
    val tracks: List<Track> = emptyList(),
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val collections: List<Collection> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && collections.isEmpty()
}

/** What a pasted link turned out to be. */
sealed interface LinkResult {
    data class Single(val track: Track) : LinkResult
    data class Many(val collection: Collection) : LinkResult
}

/**
 * The part of the app that runs yt-dlp: searching sites without an API of
 * their own, and reading any link yt-dlp understands.
 */
interface MediaExtractor {
    /** [prefix] is a yt-dlp search prefix such as "scsearch". */
    suspend fun search(prefix: String, query: String, limit: Int): List<Track>

    suspend fun extract(url: String): LinkResult?
}
