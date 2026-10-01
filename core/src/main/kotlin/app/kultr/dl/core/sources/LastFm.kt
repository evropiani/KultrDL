package app.kultr.dl.core.sources

import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.long
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement

/** Last.fm's public API, with the user's own (free) API key: their top artists, and similar artists. */
class LastFm(private val http: Http, private val apiKey: () -> String) {
    val enabled: Boolean get() = apiKey().isNotBlank()

    /** The user's most played artists, with play counts. */
    suspend fun topArtists(user: String, period: String = "6month", limit: Int = 60): List<Pair<String, Long>> =
        call("user.gettopartists", "user" to user, "period" to period, "limit" to "$limit")
            .at("topartists").at("artist").list.mapNotNull { a -> a.at("name").str?.let { it to (a.at("playcount").long ?: 0) } }

    suspend fun similar(artist: String, limit: Int = 20): List<String> =
        call("artist.getsimilar", "artist" to artist, "limit" to "$limit", "autocorrect" to "1")
            .at("similarartists").at("artist").list.mapNotNull { it.at("name").str }

    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonElement {
        val key = apiKey().trim()
        if (key.isEmpty()) throw IOException("Last.fm needs an API key.")
        val query = params.joinToString("") { (k, v) -> "&$k=${URLEncoder.encode(v, "UTF-8")}" }
        val json = parseJson(http.get("https://ws.audioscrobbler.com/2.0/?method=$method&api_key=$key&format=json$query"))
        json.at("message").str?.let { if (json.at("error") != null) throw IOException("Last.fm: $it") }
        return json
    }
}
