package app.kultr.dl.core.sources

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.Text
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.firstString
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.objectsUnder
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.path
import app.kultr.dl.core.util.str
import app.kultr.dl.core.util.walk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * YouTube Music's own search (the InnerTube API its web player uses).
 * Results are read loosely: rows are found wherever they sit in the
 * response, so a rearranged page still gives tracks.
 */
class YouTubeMusic(private val http: Http) {
    enum class Filter(val params: String) {
        SONGS("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
        VIDEOS("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"),
        ALBUMS("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
        PLAYLISTS("EgeKAQQoAEABagoQAxAEEAoQCRAF"),
    }

    suspend fun searchSongs(query: String): List<Track> = parseTracks(search(query, Filter.SONGS), Source.YOUTUBE_MUSIC)

    suspend fun searchVideos(query: String): List<Track> = parseTracks(search(query, Filter.VIDEOS), Source.YOUTUBE_MUSIC)

    suspend fun searchAlbums(query: String): List<Collection> = parseCollections(search(query, Filter.ALBUMS))

    suspend fun album(browseId: String): Collection? = parseAlbumPage(browseId, browse(browseId))

    private suspend fun search(query: String, filter: Filter): JsonElement {
        val body = buildJsonObject {
            putJsonObject("context") { putJsonObject("client") { client() } }
            put("query", query)
            put("params", filter.params)
        }
        return parseJson(http.postJson("$BASE/youtubei/v1/search?prettyPrint=false", body.toString(), HEADERS))
    }

    private suspend fun browse(browseId: String): JsonElement {
        val body = buildJsonObject {
            putJsonObject("context") { putJsonObject("client") { client() } }
            put("browseId", browseId)
        }
        return parseJson(http.postJson("$BASE/youtubei/v1/browse?prettyPrint=false", body.toString(), HEADERS))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.client() {
        put("clientName", "WEB_REMIX")
        put("clientVersion", CLIENT_VERSION)
        put("hl", "en")
        put("gl", "US")
    }

    companion object {
        private const val BASE = "https://music.youtube.com"
        private const val CLIENT_VERSION = "1.20250310.01.00"
        private val HEADERS = mapOf(
            "Origin" to BASE,
            "Referer" to "$BASE/",
            "X-YouTube-Client-Name" to "67",
            "X-YouTube-Client-Version" to CLIENT_VERSION,
        )
        private val TYPE_WORDS = setOf("song", "video", "album", "single", "ep", "playlist", "artist", "episode", "podcast")
        private val VIEWS = Regex("(?i)^[\\d.,]+\\s*[KMB]?\\s+(views|plays)$")

        fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

        /** Thumbnails come small; the same URL serves any size. */
        fun bigThumbnail(url: String?): String? = url
            ?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
            ?.replace(Regex("/(default|mqdefault|hqdefault|sddefault)\\.jpg"), "/hqdefault.jpg")

        private fun runs(element: JsonElement?): List<JsonObject> = element.at("runs").list.mapNotNull { it as? JsonObject }

        private fun pageType(run: JsonObject): String? = run.firstString("pageType")

        private fun column(row: JsonObject, index: Int): List<JsonObject> =
            runs(row.at("flexColumns").at(index).at("musicResponsiveListItemFlexColumnRenderer").at("text"))

        private fun thumbnail(row: JsonElement): String? =
            row.walk().firstNotNullOfOrNull { (it as? JsonObject)?.get("thumbnails")?.list?.lastOrNull().at("url").str }

        /** Split a subtitle's runs into its " • "-separated parts. */
        private fun segments(runs: List<JsonObject>): List<List<JsonObject>> {
            val out = mutableListOf(mutableListOf<JsonObject>())
            for (run in runs) {
                val text = run.at("text").str.orEmpty()
                if (text.trim() == "•") out += mutableListOf<JsonObject>() else out.last() += run
            }
            return out.filter { seg -> seg.any { it.at("text").str.orEmpty().isNotBlank() } }
        }

        private fun text(runs: List<JsonObject>): String = runs.joinToString("") { it.at("text").str.orEmpty() }.trim()

        fun parseTracks(root: JsonElement, source: Source): List<Track> =
            root.objectsUnder("musicResponsiveListItemRenderer").mapNotNull { parseRow(it, source, album = null) }
                .distinctBy { it.id }
                .toList()

        /** One row of a song, video or album-track list; null for rows that are not playable. */
        fun parseRow(row: JsonObject, source: Source, album: Collection?): Track? {
            val videoId = row.path("playlistItemData", "videoId").str
                ?: row.walk().firstNotNullOfOrNull { (it as? JsonObject)?.get("watchEndpoint").at("videoId").str }
                ?: return null
            val title = text(column(row, 0)).ifEmpty { return null }
            val subtitle = column(row, 1)
            var artist: String? = null
            var albumName: String? = album?.title
            var durationMs: Long? = null
            val artistRuns = subtitle.filter { run -> pageType(run)?.let { "ARTIST" in it || "USER_CHANNEL" in it } == true }
            if (artistRuns.isNotEmpty()) artist = artistRuns.joinToString(", ") { it.at("text").str.orEmpty() }
            subtitle.firstOrNull { pageType(it)?.contains("ALBUM") == true }?.let { albumName = it.at("text").str }
            for (segment in segments(subtitle)) {
                val t = text(segment)
                val clock = Text.parseClock(t)
                when {
                    clock != null -> durationMs = clock
                    t.lowercase() in TYPE_WORDS -> Unit
                    VIEWS.matches(t) -> Unit
                    artist == null -> artist = t
                }
            }
            if (durationMs == null) {
                val fixed = row.at("fixedColumns").list.firstOrNull()
                    .path("musicResponsiveListItemFixedColumnRenderer", "text")
                durationMs = Text.parseClock(text(runs(fixed)).ifEmpty { fixed.at("simpleText").str.orEmpty() })
            }
            val finalArtist = artist?.removeSuffix(" - Topic") ?: album?.subtitle ?: "Unknown artist"
            return Track(
                id = "yt:$videoId",
                source = source,
                title = title,
                artist = finalArtist,
                album = albumName,
                albumArtist = album?.subtitle,
                durationMs = durationMs,
                artworkUrl = bigThumbnail(thumbnail(row)) ?: album?.artworkUrl,
                pageUrl = watchUrl(videoId),
                streamUrl = watchUrl(videoId),
                year = album?.year,
            )
        }

        fun parseCollections(root: JsonElement): List<Collection> =
            root.objectsUnder("musicResponsiveListItemRenderer").mapNotNull { row ->
                val browse = row.at("navigationEndpoint").at("browseEndpoint") ?: return@mapNotNull null
                val browseId = browse.at("browseId").str ?: return@mapNotNull null
                val type = browse.firstString("pageType").orEmpty()
                val kind = when {
                    "ALBUM" in type -> CollectionKind.ALBUM
                    "PLAYLIST" in type -> CollectionKind.PLAYLIST
                    else -> return@mapNotNull null
                }
                val title = text(column(row, 0)).ifEmpty { return@mapNotNull null }
                val parts = segments(column(row, 1)).map { text(it) }
                val year = parts.firstNotNullOfOrNull { p -> p.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() }
                val artist = parts.firstOrNull { it.lowercase() !in TYPE_WORDS && (it.length != 4 || !it.all(Char::isDigit)) }
                Collection(
                    id = "ytm:$browseId",
                    source = Source.YOUTUBE_MUSIC,
                    kind = kind,
                    title = title,
                    subtitle = artist,
                    artworkUrl = bigThumbnail(thumbnail(row)),
                    pageUrl = "$BASE/browse/$browseId",
                    year = year,
                )
            }.distinctBy { it.id }.toList()

        fun parseAlbumPage(browseId: String, root: JsonElement): Collection? {
            val header = root.walk().firstNotNullOfOrNull { element ->
                (element as? JsonObject)?.entries?.firstOrNull { (key, value) ->
                    key.endsWith("HeaderRenderer") && value is JsonObject && value.containsKey("title")
                }?.value as? JsonObject
            }
            val title = header?.let { text(runs(it.at("title"))) }?.ifEmpty { null } ?: "Album"
            val strap = header?.let { text(runs(it.at("straplineTextOne"))) }?.ifEmpty { null }
            val subtitleParts = header?.let { segments(runs(it.at("subtitle"))).map { seg -> text(seg) } }.orEmpty()
            val year = subtitleParts.firstNotNullOfOrNull { p -> p.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() }
            val artist = strap ?: subtitleParts.firstOrNull { it.lowercase() !in TYPE_WORDS && it.toIntOrNull() == null }
            val shell = Collection(
                id = "ytm:$browseId",
                source = Source.YOUTUBE_MUSIC,
                kind = CollectionKind.ALBUM,
                title = title,
                subtitle = artist,
                artworkUrl = bigThumbnail(header?.let { thumbnail(it) }),
                pageUrl = "$BASE/browse/$browseId",
                year = year,
            )
            val tracks = root.objectsUnder("musicResponsiveListItemRenderer")
                .mapNotNull { parseRow(it, Source.YOUTUBE_MUSIC, shell) }
                .distinctBy { it.id }
                .mapIndexed { i, t -> t.copy(trackNumber = i + 1) }
                .toList()
            if (tracks.isEmpty()) return null
            return shell.copy(tracks = tracks, trackCount = tracks.size)
        }
    }
}
