package app.kultr.dl.core.sources

import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.int
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.long
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A Navidrome server (or any Subsonic / OpenSubsonic server), read through
 * its API with the user's login: the collection with play counts, stars
 * and ratings, playlists, similar artists, streams, covers and scans.
 */
class Subsonic(private val http: Http, val server: Server) {
    data class Server(val url: String, val username: String, val password: String) {
        val base: String get() = baseUrl(url)
    }

    data class Info(val type: String?, val version: String?, val openSubsonic: Boolean) {
        override fun toString(): String = listOfNotNull(type?.replaceFirstChar { it.uppercase() } ?: "Subsonic server", version).joinToString(" ")
    }

    data class Song(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val albumArtist: String?,
        val albumId: String?,
        val artistId: String?,
        val genre: String?,
        val year: Int?,
        val track: Int?,
        val disc: Int?,
        val durationMs: Long?,
        val coverArt: String?,
        val playCount: Int,
        val playedAt: Long?,
        val starred: Boolean,
        val rating: Int,
        val isrc: String?,
    )

    data class Playlist(val id: String, val name: String, val songCount: Int)

    class SubsonicException(val code: Int?, message: String) : IOException(message)

    suspend fun ping(): Info {
        val r = call("ping")
        return Info(r.at("type").str, r.at("serverVersion").str ?: r.at("version").str, r.at("openSubsonic").str == "true")
    }

    /** Every song on the server, a page at a time (an empty search3 query lists everything on Navidrome). */
    suspend fun songs(pageSize: Int = 500, onPage: (Int) -> Unit = {}): List<Song> {
        val out = ArrayList<Song>()
        var offset = 0
        while (true) {
            val page = call("search3", "query" to "", "songCount" to "$pageSize", "songOffset" to "$offset", "artistCount" to "0", "albumCount" to "0")
                .at("searchResult3").at("song").list.mapNotNull(::parseSong)
            out += page
            onPage(out.size)
            if (page.size < pageSize || out.size >= MAX_SONGS) break
            offset += pageSize
        }
        return out
    }

    suspend fun playlists(): List<Playlist> = call("getPlaylists").at("playlists").at("playlist").list.mapNotNull { p ->
        Playlist(p.at("id").str ?: return@mapNotNull null, p.at("name").str ?: "Playlist", p.at("songCount").int ?: 0)
    }

    suspend fun playlistSongs(id: String): List<Song> = call("getPlaylist", "id" to id).at("playlist").at("entry").list.mapNotNull(::parseSong)

    /** Artists like this one (needs the server's Last.fm integration); [artistId] is the server's id. */
    suspend fun similarArtists(artistId: String, count: Int = 20): List<String> =
        call("getArtistInfo2", "id" to artistId, "count" to "$count", "includeNotPresent" to "true")
            .at("artistInfo2").at("similarArtist").list.mapNotNull { it.at("name").str }

    /**
     * Whether the signed-in account is an admin, or null when the server doesn't say.
     * Plays, stars and ratings belong to each account; only admins may start scans.
     */
    suspend fun isAdmin(): Boolean? = call("getUser", "username" to server.username).at("user").at("adminRole").str?.toBooleanStrictOrNull()

    /** Ask the server to look for new files now (only admins may). */
    suspend fun startScan(): Boolean = call("startScan").at("scanStatus").at("scanning").str == "true"

    /** Where a song streams from, without the login; [authenticate] adds it when the request is made. */
    fun streamUrl(songId: String): String = "${server.base}/rest/stream?id=${enc(songId)}"

    fun coverUrl(coverArt: String, size: Int = 600): String = "${server.base}/rest/getCoverArt?id=${enc(coverArt)}&size=$size"

    /** [url] with the login added (a fresh salt and token each time), for a request to this server. */
    fun authenticate(url: String): String {
        val sep = if ('?' in url) '&' else '?'
        return url + sep + authParams().entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    fun authParams(): Map<String, String> {
        val salt = salt()
        return mapOf("u" to server.username, "t" to md5(server.password + salt), "s" to salt, "v" to API_VERSION, "c" to CLIENT, "f" to "json")
    }

    fun owns(url: String): Boolean = url.startsWith(server.base + "/rest/")

    fun toTrack(song: Song): Track = Track(
        id = "navidrome:${song.id}",
        source = Source.NAVIDROME,
        title = song.title,
        artist = song.artist,
        album = song.album,
        albumArtist = song.albumArtist,
        durationMs = song.durationMs,
        artworkUrl = song.coverArt?.let { coverUrl(it) },
        streamUrl = streamUrl(song.id),
        isrc = song.isrc,
        year = song.year,
        trackNumber = song.track,
        discNumber = song.disc,
        genre = song.genre,
    )

    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonElement {
        val auth = authParams().entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val query = params.joinToString("") { (k, v) -> "&$k=${enc(v)}" }
        return check(parseJson(http.get("${server.base}/rest/$method.view?$auth$query")))
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT = "KultrDL"
        private const val MAX_SONGS = 200_000
        private val random = SecureRandom()

        /** "nas.local:4533/" → "http://nas.local:4533"; a pasted web-UI link loses its "/app/…" part. */
        fun baseUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            u = u.replace(Regex("/app(/.*)?$"), "").replace(Regex("/rest(/.*)?$"), "")
            return u.trimEnd('/')
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun salt(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** The answer inside "subsonic-response", or the server's reason for refusing. */
        fun check(json: JsonElement): JsonElement {
            val r = json.at("subsonic-response") ?: throw IOException("That doesn't look like a Navidrome or Subsonic server.")
            if (r.at("status").str == "ok") return r
            val code = r.at("error").at("code").int
            val message = r.at("error").at("message").str
            throw SubsonicException(
                code,
                when (code) {
                    40 -> "The server didn't accept the username or password."
                    41 -> "This account can't sign in with a token (LDAP accounts can't). Use a local Navidrome account."
                    50 -> "This account isn't allowed to do that${message?.let { " ($it)" } ?: ""}."
                    70 -> "Not found on the server."
                    else -> "The server refused: ${message ?: "error $code"}"
                },
            )
        }

        fun parseSong(r: JsonElement): Song? {
            if (r.at("isVideo").str == "true") return null
            val id = r.at("id").str ?: return null
            val artists = r.at("artists").list.mapNotNull { it.at("name").str }
            return Song(
                id = id,
                title = r.at("title").str ?: return null,
                artist = r.at("displayArtist").str ?: r.at("artist").str ?: artists.joinToString(", ").ifEmpty { "Unknown artist" },
                album = r.at("album").str,
                albumArtist = r.at("displayAlbumArtist").str ?: r.at("albumArtists").list.firstOrNull().at("name").str,
                albumId = r.at("albumId").str,
                artistId = r.at("artistId").str ?: r.at("artists").list.firstOrNull().at("id").str,
                genre = r.at("genre").str ?: r.at("genres").list.firstOrNull().at("name").str,
                year = r.at("year").int,
                track = r.at("track").int,
                disc = r.at("discNumber").int,
                durationMs = r.at("duration").long?.times(1000),
                coverArt = r.at("coverArt").str,
                playCount = r.at("playCount").int ?: 0,
                playedAt = r.at("played").str?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                starred = r.at("starred").str != null,
                rating = r.at("userRating").int ?: 0,
                isrc = (r.at("isrc") as? JsonArray)?.firstOrNull().str ?: r.at("isrc").str,
            )
        }
    }
}
