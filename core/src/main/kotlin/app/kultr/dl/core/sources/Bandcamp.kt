package app.kultr.dl.core.sources

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import app.kultr.dl.core.util.walk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bandcamp's own search box. Tracks and albums play and download through yt-dlp. */
class Bandcamp(private val http: Http) {
    suspend fun search(query: String): Pair<List<Track>, List<Collection>> {
        val tracks = parse(request(query, "t")).first
        val albums = runCatching { parse(request(query, "a")).second }.getOrDefault(emptyList())
        return tracks to albums
    }

    private suspend fun request(query: String, filter: String): JsonElement {
        val body = buildJsonObject {
            put("search_text", query)
            put("search_filter", filter)
            put("full_page", false)
            put("fan_id", JsonNull)
        }
        return parseJson(
            http.postJson(
                "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic",
                body.toString(),
                mapOf("Origin" to "https://bandcamp.com", "Referer" to "https://bandcamp.com/"),
            ),
        )
    }

    companion object {
        fun parse(root: JsonElement): Pair<List<Track>, List<Collection>> {
            val results = root.at("auto").at("results").list.ifEmpty {
                root.walk().firstNotNullOfOrNull { (it as? JsonObject)?.get("results") as? JsonArray }.list
            }
            val tracks = mutableListOf<Track>()
            val albums = mutableListOf<Collection>()
            for (r in results) {
                val url = r.at("item_url_path").str ?: r.at("item_url_root").str ?: continue
                val name = r.at("name").str ?: continue
                val artist = r.at("band_name").str ?: "Unknown artist"
                val art = r.at("img").str?.replace(Regex("_\\d+\\.jpg$"), "_10.jpg")
                when (r.at("type").str) {
                    "t" -> tracks += Track(
                        id = "bandcamp:${r.at("id").str ?: url}",
                        source = Source.BANDCAMP,
                        title = name,
                        artist = artist,
                        album = r.at("album_name").str,
                        artworkUrl = art,
                        pageUrl = url,
                        streamUrl = url,
                    )
                    "a" -> albums += Collection(
                        id = "bandcamp:album:${r.at("id").str ?: url}",
                        source = Source.BANDCAMP,
                        kind = CollectionKind.ALBUM,
                        title = name,
                        subtitle = artist,
                        artworkUrl = art,
                        pageUrl = url,
                    )
                }
            }
            return tracks to albums
        }
    }
}
