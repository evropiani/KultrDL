package app.kultr.dl.core.sources

import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.Text
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import app.kultr.dl.core.util.walk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What a music page says about itself: its Open Graph tags and any
 * schema.org JSON-LD (MusicRecording, MusicAlbum, MusicPlaylist). This is
 * how links from stores without a public API (Qobuz, Tidal playlists)
 * become a title, an artist and, where the page lists them, tracks.
 */
class WebPage(private val http: Http) {
    data class Recording(val title: String, val artist: String?, val durationMs: Long?)

    data class Meta(
        val title: String?,
        val description: String?,
        val image: String?,
        val type: String?,
        val musician: String?,
        /** Tracks listed in the page's structured data, if any. */
        val recordings: List<Recording>,
        /** The artist named in the structured data, for an album or song. */
        val byArtist: String?,
        /** "MusicRecording", "MusicAlbum" or "MusicPlaylist" from the structured data. */
        val schemaType: String?,
        val schemaName: String?,
    )

    suspend fun read(url: String): Meta = parse(http.get(url, mapOf("Accept-Language" to "en")))

    companion object {
        private fun meta(html: String, name: String): String? {
            val patterns = listOf(
                Regex("<meta[^>]+(?:property|name)=[\"']${Regex.escape(name)}[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE),
                Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']${Regex.escape(name)}[\"']", RegexOption.IGNORE_CASE),
            )
            return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }?.let(Text::unescapeHtml)?.trim()?.ifEmpty { null }
        }

        private fun name(el: JsonElement?): String? = when (el) {
            is JsonArray -> el.mapNotNull { name(it) }.joinToString(", ").ifEmpty { null }
            is JsonObject -> el.at("name").str
            else -> el.str
        }

        fun parse(html: String): Meta {
            val blocks = Regex("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(html)
                .mapNotNull { runCatching { parseJson(it.groupValues[1].trim()) }.getOrNull() }
                .toList()
            val objects = blocks.flatMap { b -> b.walk().filterIsInstance<JsonObject>().toList() }
            fun typeOf(o: JsonObject): String? = o["@type"].let { t -> (t as? JsonArray)?.firstOrNull().str ?: t.str }
            val main = objects.firstOrNull { typeOf(it) in setOf("MusicAlbum", "MusicPlaylist") }
                ?: objects.firstOrNull { typeOf(it) == "MusicRecording" }
            val recordings = if (main != null && typeOf(main) != "MusicRecording") {
                val list = main["track"].let { t -> (t as? JsonObject)?.get("itemListElement") ?: t }
                list.list.mapNotNull { item ->
                    val rec = (item as? JsonObject)?.get("item") ?: item
                    val title = rec.at("name").str ?: return@mapNotNull null
                    Recording(Text.unescapeHtml(title), name(rec.at("byArtist"))?.let(Text::unescapeHtml), Text.parseIsoDuration(rec.at("duration").str))
                }
            } else {
                emptyList()
            }
            val titleTag = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(html)?.groupValues?.get(1)?.let(Text::unescapeHtml)?.trim()
            return Meta(
                title = meta(html, "og:title") ?: meta(html, "twitter:title") ?: titleTag,
                description = meta(html, "og:description") ?: meta(html, "description"),
                image = meta(html, "og:image") ?: meta(html, "twitter:image"),
                type = meta(html, "og:type"),
                musician = meta(html, "music:musician_description") ?: meta(html, "music:musician"),
                recordings = recordings,
                byArtist = main?.let { name(it["byArtist"]) }?.let(Text::unescapeHtml),
                schemaType = main?.let(::typeOf),
                schemaName = main?.get("name").str?.let(Text::unescapeHtml),
            )
        }

        /**
         * A store page's title is usually "Title - Artist | Store" or
         * "Title by Artist on Store"; take it apart.
         */
        fun splitTitle(raw: String, store: String): Pair<String, String?> {
            var t = raw
                .replace(Regex("(?i)\\s*[|–—-]\\s*$store.*$"), "")
                .replace(Regex("(?i)\\s+on\\s+$store.*$"), "")
                .replace(Regex("(?i)^(listen to|stream|buy|download)\\s+"), "")
                .trim()
            t = t.replace(Regex("(?i)\\s*[|]\\s*(hi-?res|high resolution|download).*$"), "").trim()
            Regex("(?i)^(.+?)\\s+by\\s+(.+)$").find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
            Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
            return t to null
        }
    }
}
