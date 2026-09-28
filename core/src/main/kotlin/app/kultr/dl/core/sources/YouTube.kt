package app.kultr.dl.core.sources

import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.Text
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.objectsUnder
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Plain YouTube video search, for live sets, covers and uploads that are not on YouTube Music. */
class YouTube(private val http: Http) {
    suspend fun search(query: String): List<Track> {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            put("params", "EgIQAQ%3D%3D")
        }
        val json = http.postJson(
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
            body.toString(),
            mapOf(
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to CLIENT_VERSION,
            ),
        )
        return parse(parseJson(json))
    }

    companion object {
        private const val CLIENT_VERSION = "2.20250312.04.00"

        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

        private fun text(element: JsonElement?): String =
            element.at("simpleText").str ?: element.at("runs").list.joinToString("") { it.at("text").str.orEmpty() }

        fun parse(root: JsonElement): List<Track> = root.objectsUnder("videoRenderer").mapNotNull { v -> parseVideo(v) }
            .distinctBy { it.id }
            .toList()

        private fun parseVideo(v: JsonObject): Track? {
            val id = v.at("videoId").str ?: return null
            val rawTitle = text(v.at("title")).ifEmpty { return null }
            val channel = text(v.at("ownerText")).ifEmpty { text(v.at("longBylineText")) }.ifEmpty { null }
            val (artist, title) = Text.artistAndTitle(rawTitle, channel)
            val thumb = v.at("thumbnail").at("thumbnails").list.lastOrNull().at("url").str?.substringBefore('?')
            return Track(
                id = "yt:$id",
                source = Source.YOUTUBE,
                title = title,
                artist = artist.ifEmpty { channel ?: "Unknown artist" },
                durationMs = Text.parseClock(text(v.at("lengthText"))),
                artworkUrl = thumb,
                pageUrl = watchUrl(id),
                streamUrl = watchUrl(id),
            )
        }
    }
}
