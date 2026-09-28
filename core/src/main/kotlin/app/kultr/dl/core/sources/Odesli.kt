package app.kultr.dl.core.sources

import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement

/**
 * song.link: turns a link on one service into the same song or album on
 * the others. Used for services without a public catalogue API (Tidal,
 * Amazon Music), and to find a track's recording on YouTube directly.
 */
class Odesli(private val http: Http) {
    data class Entity(
        val type: String,
        val title: String,
        val artist: String?,
        val artworkUrl: String?,
        /** The same song or album on YouTube Music or YouTube, when song.link knows it. */
        val youtubeUrl: String?,
        val links: Map<String, String>,
    )

    suspend fun lookup(url: String, country: String = "US"): Entity? {
        val json = http.get("https://api.song.link/v1-alpha.1/links?userCountry=$country&url=${URLEncoder.encode(url, "UTF-8")}")
        return parse(parseJson(json))
    }

    companion object {
        fun parse(root: JsonElement): Entity? {
            val entities = root.at("entitiesByUniqueId") as? kotlinx.serialization.json.JsonObject ?: return null
            val main = root.at("entityUniqueId").str?.let { entities[it] } ?: entities.values.firstOrNull() ?: return null
            val links = (root.at("linksByPlatform") as? kotlinx.serialization.json.JsonObject)
                ?.mapNotNull { (platform, value) -> value.at("url").str?.let { platform to it } }
                ?.toMap()
                .orEmpty()
            return Entity(
                type = main.at("type").str ?: "song",
                title = main.at("title").str ?: return null,
                artist = main.at("artistName").str,
                artworkUrl = main.at("thumbnailUrl").str,
                youtubeUrl = links["youtubeMusic"] ?: links["youtube"],
                links = links,
            )
        }
    }
}
