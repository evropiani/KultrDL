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
import app.kultr.dl.core.util.walk
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Spotify metadata. Links work without an account: the public embed page
 * carries a track's details, or an album's or playlist's track list.
 * Searching needs the Web API, so it is available once a Client ID and
 * secret from developer.spotify.com are entered in Settings.
 */
class Spotify(private val http: Http, private val credentials: () -> Pair<String, String>?) {
    private var token: String? = null
    private var tokenExpires = 0L

    val canSearch: Boolean get() = credentials() != null

    suspend fun searchTracks(query: String): List<Track> {
        val json = api("search?type=track&limit=30&q=${enc(query)}")
        return json.at("tracks").at("items").list.mapNotNull { parseApiTrack(it, null) }
    }

    suspend fun searchAlbums(query: String): List<Collection> {
        val json = api("search?type=album&limit=20&q=${enc(query)}")
        return json.at("albums").at("items").list.mapNotNull { parseApiAlbum(it) }
    }

    /** A track, album or playlist by its Spotify id, from the embed page (or the API when signed in). */
    suspend fun load(kind: String, id: String): Any? {
        if (canSearch && kind == "album") {
            runCatching { return parseApiAlbumPage(api("albums/$id")) }
        }
        val html = http.get("https://open.spotify.com/embed/$kind/$id")
        val data = nextData(html) ?: throw IOException("Spotify did not return the $kind")
        return when (kind) {
            "track" -> parseEmbedTrack(data, id)
            else -> parseEmbedList(data, kind, id)
        }
    }

    private suspend fun api(path: String): JsonElement {
        val bearer = accessToken() ?: throw IOException("Add a Spotify Client ID and secret in Settings to search Spotify.")
        return parseJson(http.get("https://api.spotify.com/v1/$path", mapOf("Authorization" to "Bearer $bearer")))
    }

    private suspend fun accessToken(): String? {
        val (id, secret) = credentials() ?: return null
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpires) return it }
        val basic = Base64.getEncoder().encodeToString("$id:$secret".toByteArray())
        val json = parseJson(
            http.postForm(
                "https://accounts.spotify.com/api/token",
                mapOf("grant_type" to "client_credentials"),
                mapOf("Authorization" to "Basic $basic"),
            ),
        )
        val fresh = json.at("access_token").str ?: throw IOException("Spotify refused the Client ID and secret.")
        token = fresh
        tokenExpires = now + ((json.at("expires_in").long ?: 3600) - 60) * 1000
        return fresh
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun pageUrl(kind: String, id: String) = "https://open.spotify.com/$kind/$id"

        private fun biggestImage(images: JsonElement?): String? =
            images.list.maxByOrNull { it.at("width").int ?: 0 }.at("url").str

        fun parseApiTrack(r: JsonElement, album: Collection?): Track? {
            val id = r.at("id").str ?: return null
            val albumJson = r.at("album")
            return Track(
                id = "spotify:$id",
                source = Source.SPOTIFY,
                title = r.at("name").str ?: return null,
                artist = r.at("artists").list.mapNotNull { it.at("name").str }.joinToString(", ").ifEmpty { "Unknown artist" },
                album = albumJson.at("name").str ?: album?.title,
                albumArtist = albumJson.at("artists").list.firstOrNull().at("name").str ?: album?.subtitle,
                durationMs = r.at("duration_ms").long,
                artworkUrl = biggestImage(albumJson.at("images")) ?: album?.artworkUrl,
                pageUrl = pageUrl("track", id),
                isrc = r.at("external_ids").at("isrc").str,
                year = Text.year(albumJson.at("release_date").str) ?: album?.year,
                trackNumber = r.at("track_number").int,
                discNumber = r.at("disc_number").int,
                explicit = r.at("explicit").bool == true,
            )
        }

        fun parseApiAlbum(r: JsonElement): Collection? {
            val id = r.at("id").str ?: return null
            return Collection(
                id = "spotify:album:$id",
                source = Source.SPOTIFY,
                kind = CollectionKind.ALBUM,
                title = r.at("name").str ?: return null,
                subtitle = r.at("artists").list.mapNotNull { it.at("name").str }.joinToString(", "),
                artworkUrl = biggestImage(r.at("images")),
                pageUrl = pageUrl("album", id),
                year = Text.year(r.at("release_date").str),
                trackCount = r.at("total_tracks").int,
            )
        }

        fun parseApiAlbumPage(r: JsonElement): Collection? {
            val shell = parseApiAlbum(r) ?: return null
            val tracks = r.at("tracks").at("items").list.mapNotNull { parseApiTrack(it, shell) }
            return shell.copy(tracks = tracks, trackCount = tracks.size)
        }

        /** The JSON the embed page is rendered from. */
        fun nextData(html: String): JsonElement? {
            val m = Regex("<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
                ?: return null
            return runCatching { parseJson(m.groupValues[1]) }.getOrNull()
        }

        /** The object describing the embedded track, album or playlist. */
        private fun entity(data: JsonElement): JsonObject? =
            data.walk().firstNotNullOfOrNull { (it as? JsonObject)?.get("entity") as? JsonObject }
                ?: data.walk().firstOrNull { el ->
                    el is JsonObject && (el.containsKey("trackList") || (el.containsKey("uri") && el.containsKey("name")))
                } as? JsonObject

        private fun cover(entity: JsonElement): String? =
            biggestImage(entity.at("coverArt").at("sources"))
                ?: biggestImage(entity.at("visualIdentity").at("image"))
                ?: biggestImage(entity.at("images"))

        private fun idFromUri(uri: String?): String? = uri?.substringAfterLast(':')?.takeIf { it.isNotEmpty() }

        fun parseEmbedTrack(data: JsonElement, fallbackId: String): Track? {
            val e = entity(data) ?: return null
            val title = e.at("name").str ?: e.at("title").str ?: return null
            val artists = e.at("artists").list.mapNotNull { it.at("name").str }.joinToString(", ").ifEmpty { e.at("subtitle").str.orEmpty() }
            val id = idFromUri(e.at("uri").str) ?: fallbackId
            return Track(
                id = "spotify:$id",
                source = Source.SPOTIFY,
                title = title,
                artist = artists.ifEmpty { "Unknown artist" },
                durationMs = e.at("duration").long ?: e.at("maxDuration").long,
                artworkUrl = cover(e),
                pageUrl = pageUrl("track", id),
                year = Text.year(e.at("releaseDate").at("isoString").str ?: e.at("releaseDate").str),
                explicit = e.at("isExplicit").bool == true,
            )
        }

        fun parseEmbedList(data: JsonElement, kind: String, id: String): Collection? {
            val e = entity(data) ?: return null
            val title = e.at("name").str ?: e.at("title").str ?: return null
            val owner = e.at("subtitle").str ?: e.at("artists").list.mapNotNull { it.at("name").str }.joinToString(", ").ifEmpty { null }
            val art = cover(e)
            val isAlbum = kind == "album"
            val tracks = e.at("trackList").list.mapIndexedNotNull { i, t ->
                val trackId = idFromUri(t.at("uri").str) ?: return@mapIndexedNotNull null
                Track(
                    id = "spotify:$trackId",
                    source = Source.SPOTIFY,
                    title = t.at("title").str ?: return@mapIndexedNotNull null,
                    artist = t.at("subtitle").str?.replace(" ", " ") ?: owner ?: "Unknown artist",
                    album = if (isAlbum) title else null,
                    albumArtist = if (isAlbum) owner else null,
                    durationMs = t.at("duration").long,
                    artworkUrl = if (isAlbum) art else null,
                    pageUrl = pageUrl("track", trackId),
                    trackNumber = if (isAlbum) i + 1 else null,
                    explicit = t.at("isExplicit").bool == true,
                )
            }
            return Collection(
                id = "spotify:$kind:$id",
                source = Source.SPOTIFY,
                kind = if (isAlbum) CollectionKind.ALBUM else CollectionKind.PLAYLIST,
                title = title,
                subtitle = owner,
                artworkUrl = art,
                pageUrl = pageUrl(kind, id),
                year = Text.year(e.at("releaseDate").at("isoString").str),
                trackCount = tracks.size,
                tracks = tracks,
            )
        }
    }
}
