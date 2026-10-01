package app.kultr.dl.core.sources

import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.Text
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.long
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** ListenBrainz's open API: a user's top artists and the playlists it makes for them each week. */
class ListenBrainz(private val http: Http) {
    data class PlaylistRef(val id: String, val title: String)

    suspend fun topArtists(user: String, range: String = "half_yearly", count: Int = 60): List<Pair<String, Long>> {
        val text = http.get("$BASE/stats/user/${enc(user)}/artists?range=$range&count=$count")
        // 204 No Content: statistics not worked out yet for this user.
        if (text.isBlank()) return emptyList()
        return parseJson(text).at("payload").at("artists").list.mapNotNull { a ->
            a.at("artist_name").str?.let { it to (a.at("listen_count").long ?: 0) }
        }
    }

    /** Playlists ListenBrainz made for the user (Weekly Exploration, Weekly Jams…), newest first. */
    suspend fun createdFor(user: String): List<PlaylistRef> {
        val text = http.get("$BASE/user/${enc(user)}/playlists/createdfor?count=25")
        if (text.isBlank()) return emptyList()
        return parseJson(text).at("playlists").list.mapNotNull { p ->
            val pl = p.at("playlist")
            val id = pl.at("identifier").str?.trimEnd('/')?.substringAfterLast('/') ?: return@mapNotNull null
            PlaylistRef(id, pl.at("title").str ?: "Playlist")
        }
    }

    suspend fun playlist(id: String): List<Track> = parsePlaylist(parseJson(http.get("$BASE/playlist/${enc(id)}")))

    companion object {
        private const val BASE = "https://api.listenbrainz.org/1"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** A JSPF playlist's tracks, as names to match on YouTube Music. */
        fun parsePlaylist(root: JsonElement): List<Track> = root.at("playlist").at("track").list.mapNotNull { t ->
            val title = t.at("title").str ?: return@mapNotNull null
            val artist = t.at("creator").str ?: return@mapNotNull null
            val identifier = (t.at("identifier") as? JsonArray)?.firstOrNull().str ?: t.at("identifier").str
            val mbid = identifier?.trimEnd('/')?.substringAfterLast('/')
            Track(
                id = "listenbrainz:" + (mbid ?: Text.normalize("$artist $title").replace(' ', '-')),
                source = Source.LISTENBRAINZ,
                title = title,
                artist = artist,
                album = t.at("album").str,
                durationMs = t.at("duration").long,
                pageUrl = mbid?.let { "https://musicbrainz.org/recording/$it" },
            )
        }

        /** Which kind of weekly playlist a title is, as a stable id: "weekly-exploration", "weekly-jams"… */
        fun kind(title: String): String? = Regex("(?i)^(weekly exploration|weekly jams|daily jams|top discoveries|top missed recordings)")
            .find(title)?.value?.lowercase()?.replace(' ', '-')
    }
}
