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
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement

/** Deezer's public catalogue API (no account needed). */
class Deezer(private val http: Http) {
    suspend fun searchTracks(query: String): List<Track> =
        get("search?q=${enc(query)}&limit=30").at("data").list.mapNotNull { parseTrack(it, null) }

    suspend fun searchAlbums(query: String): List<Collection> =
        get("search/album?q=${enc(query)}&limit=20").at("data").list.mapNotNull { parseAlbum(it) }

    suspend fun album(id: String): Collection? = parseAlbumPage(get("album/$id"))

    suspend fun playlist(id: String): Collection? = parsePlaylistPage(get("playlist/$id"))

    suspend fun track(id: String): Track? = parseTrack(get("track/$id"), null)

    suspend fun chart(): List<Track> = get("chart/0/tracks?limit=30").at("data").list.mapNotNull { parseTrack(it, null) }

    private suspend fun get(path: String): JsonElement {
        val json = parseJson(http.get("https://api.deezer.com/$path"))
        json.at("error").at("message").str?.let { throw IOException("Deezer: $it") }
        return json
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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
