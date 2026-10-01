package app.kultr.dl.core.sources

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.Text
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.bool
import app.kultr.dl.core.util.int
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.long
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import app.kultr.dl.core.discover.ArtistRef
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/** Deezer's public catalogue API (no account needed). */
class Deezer(private val http: Http) {
    private val pace = Mutex()
    private val recent = ArrayDeque<Long>()
    private var genreNames: Map<Long, String>? = null

    suspend fun searchArtists(name: String): List<ArtistRef> =
        get("search/artist?q=${enc(name)}&limit=8").at("data").list.mapNotNull { parseArtist(it) }

    /** An artist's albums, EPs and singles, newest first. */
    suspend fun artistAlbums(artistId: String, artistName: String): List<Collection> {
        val names = genres()
        return get("artist/$artistId/albums?limit=100").at("data").list
            .mapNotNull { parseArtistAlbum(it, artistName, names) }
            .sortedByDescending { it.releaseDate.orEmpty() }
    }

    /** The same albums, most loved (by Deezer fans) first; albums only. */
    suspend fun popularAlbums(artistId: String, artistName: String): List<Collection> {
        val names = genres()
        return get("artist/$artistId/albums?limit=100").at("data").list
            .mapNotNull { r -> parseArtistAlbum(r, artistName, names)?.let { it to (r.at("fans").long ?: 0) } }
            .filter { (c, _) -> c.recordType == "album" }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    suspend fun related(artistId: String): List<ArtistRef> =
        get("artist/$artistId/related?limit=25").at("data").list.mapNotNull { parseArtist(it) }

    suspend fun top(artistId: String, limit: Int): List<Track> =
        get("artist/$artistId/top?limit=$limit").at("data").list.mapNotNull { parseTrack(it, null) }

    /** Deezer genre ids to names ("Rap/Hip Hop"), fetched once. */
    private suspend fun genres(): Map<Long, String> = genreNames ?: runCatching {
        get("genre").at("data").list.mapNotNull { g -> g.at("id").long?.let { id -> g.at("name").str?.let { id to it } } }.toMap()
    }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) genreNames = it }
    suspend fun searchTracks(query: String): List<Track> =
        get("search?q=${enc(query)}&limit=30").at("data").list.mapNotNull { parseTrack(it, null) }

    suspend fun searchAlbums(query: String): List<Collection> =
        get("search/album?q=${enc(query)}&limit=20").at("data").list.mapNotNull { parseAlbum(it) }

    suspend fun album(id: String): Collection? = parseAlbumPage(get("album/$id"))

    suspend fun playlist(id: String): Collection? = parsePlaylistPage(get("playlist/$id"))

    suspend fun track(id: String): Track? = parseTrack(get("track/$id"), null)

    suspend fun chart(): List<Track> = get("chart/0/tracks?limit=30").at("data").list.mapNotNull { parseTrack(it, null) }

    private suspend fun get(path: String): JsonElement {
        throttle()
        var json = parseJson(http.get("https://api.deezer.com/$path"))
        // Error 4 is Deezer's "too many requests": wait and ask once more.
        if (json.at("error").at("code").long == 4L) {
            delay(5_000)
            json = parseJson(http.get("https://api.deezer.com/$path"))
        }
        json.at("error").at("message").str?.let { throw IOException("Deezer: $it") }
        return json
    }

    /** Deezer allows 50 requests in 5 seconds; stay well under that. */
    private suspend fun throttle() {
        pace.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
                if (recent.size < MAX_IN_WINDOW) {
                    recent.addLast(now)
                    return
                }
                delay(WINDOW_MS - (now - recent.first()) + 10)
            }
        }
    }

    companion object {
        private const val WINDOW_MS = 5_000L
        private const val MAX_IN_WINDOW = 35

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun parseArtist(r: JsonElement): ArtistRef? {
            val id = r.at("id").long ?: return null
            if (r.at("type").str?.let { it != "artist" } == true) return null
            return ArtistRef(
                id = "deezer:$id",
                name = r.at("name").str ?: return null,
                fans = r.at("nb_fan").long ?: 0,
                pictureUrl = r.at("picture_xl").str ?: r.at("picture_big").str,
            )
        }

        /** An album in an artist's discography (it doesn't name the artist; [artistName] does). */
        fun parseArtistAlbum(r: JsonElement, artistName: String, genres: Map<Long, String>): Collection? =
            parseAlbum(r)?.copy(
                subtitle = r.at("artist").at("name").str ?: artistName,
                releaseDate = r.at("release_date").str,
                recordType = r.at("record_type").str,
                genre = r.at("genre_id").long?.let { genres[it] },
            )

        fun parseTrack(r: JsonElement, album: Collection?): Track? {
            val id = r.at("id").long ?: return null
            if (r.at("type").str?.let { it != "track" } == true) return null
            val albumJson = r.at("album")
            return Track(
                id = "deezer:$id",
                source = Source.DEEZER,
                title = r.at("title").str ?: return null,
                artist = r.at("artist").at("name").str ?: album?.subtitle ?: "Unknown artist",
                album = albumJson.at("title").str ?: album?.title,
                albumArtist = album?.subtitle,
                durationMs = r.at("duration").long?.times(1000),
                artworkUrl = albumJson.at("cover_xl").str ?: albumJson.at("cover_big").str ?: album?.artworkUrl,
                pageUrl = r.at("link").str,
                isrc = r.at("isrc").str,
                year = Text.year(r.at("release_date").str) ?: album?.year,
                trackNumber = r.at("track_position").int,
                discNumber = r.at("disk_number").int,
                explicit = r.at("explicit_lyrics").bool == true,
            )
        }

        fun parseAlbum(r: JsonElement): Collection? {
            val id = r.at("id").long ?: return null
            return Collection(
                id = "deezer:album:$id",
                source = Source.DEEZER,
                kind = CollectionKind.ALBUM,
                title = r.at("title").str ?: return null,
                subtitle = r.at("artist").at("name").str,
                artworkUrl = r.at("cover_xl").str ?: r.at("cover_big").str,
                pageUrl = r.at("link").str,
                year = Text.year(r.at("release_date").str),
                trackCount = r.at("nb_tracks").int,
            )
        }

        fun parseAlbumPage(r: JsonElement): Collection? {
            val shell = parseAlbum(r) ?: return null
            val genre = r.at("genres").at("data").list.firstOrNull().at("name").str
            val tracks = r.at("tracks").at("data").list.mapIndexedNotNull { i, t ->
                parseTrack(t, shell)?.let { it.copy(trackNumber = it.trackNumber ?: (i + 1), genre = genre, album = shell.title) }
            }
            return shell.copy(tracks = tracks, trackCount = tracks.size)
        }

        fun parsePlaylistPage(r: JsonElement): Collection? {
            val id = r.at("id").long ?: return null
            val tracks = r.at("tracks").at("data").list.mapNotNull { parseTrack(it, null) }
            return Collection(
                id = "deezer:playlist:$id",
                source = Source.DEEZER,
                kind = CollectionKind.PLAYLIST,
                title = r.at("title").str ?: "Playlist",
                subtitle = r.at("creator").at("name").str,
                artworkUrl = r.at("picture_xl").str ?: r.at("picture_big").str,
                pageUrl = r.at("link").str,
                trackCount = tracks.size,
                tracks = tracks,
            )
        }
    }
}
