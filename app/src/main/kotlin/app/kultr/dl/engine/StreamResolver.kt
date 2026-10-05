package app.kultr.dl.engine

import android.net.Uri
import android.util.Log
import app.kultr.dl.core.Catalog
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.sources.Subsonic
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import app.kultr.dl.data.Library
import app.kultr.dl.data.SettingsRepository
import app.kultr.dl.data.StreamQuality
import app.kultr.dl.data.describe
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns a track into something the player can open: its downloaded file
 * if there is one, otherwise the audio stream of its recording, found by
 * matching for catalogue tracks (Spotify, Apple Music…) and read with
 * yt-dlp. Streams are cached until shortly before their links expire.
 */
class StreamResolver(
    private val library: Library,
    private val catalog: Catalog,
    private val ytDlp: YtDlp,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** The user's Navidrome server, for its songs. */
    private val navidrome: () -> Subsonic? = { null },
    /** The player's HTTP client: getting a stream ready opens the connection it will then reuse. */
    private val http: OkHttpClient? = null,
) {
    sealed interface Resolved {
        data class Local(val uri: Uri) : Resolved
        data class Remote(val url: String, val headers: Map<String, String>, val hls: Boolean) : Resolved
    }

    /** [checked]: its first bytes came back, so it will start at once. */
    private data class Cached(val value: Resolved.Remote, val expiresAt: Long, val checked: Boolean = false)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Resolved>>()
    private val preparing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** YouTube profiles already refused for a track, and the one it is on now. */
    private val tried = ConcurrentHashMap<String, MutableSet<YouTubeProfile>>()
    private val current = ConcurrentHashMap<String, YouTubeProfile>()

    private val preferred: YouTubeProfile get() = settings.settings.value.youtubeProfile

    /**
     * YouTube refused [trackId]'s stream: move it to the next profile.
     * False when it isn't a YouTube stream or every profile has been tried.
     */
    fun tryNextProfile(trackId: String): Boolean {
        val profile = current[trackId] ?: return false
        val refused = tried.getOrPut(trackId) { mutableSetOf() }.apply { add(profile) }
        val next = preferred.order().firstOrNull { it !in refused } ?: return false
        current[trackId] = next
        invalidate(trackId)
        return true
    }

    /** [trackId] is playing: its profile works, so start with it from now on. */
    fun onPlaying(trackId: String) {
        tried.remove(trackId)
        val profile = current[trackId] ?: return
        if (profile != preferred) settings.update { it.copy(youtubeProfile = profile) }
    }

    fun remember(profile: YouTubeProfile) {
        if (profile != preferred) settings.update { it.copy(youtubeProfile = profile) }
    }

    fun invalidate(trackId: String) {
        cache.remove(trackId)
    }

    /**
     * Gets a song that's coming up ready in the background, so it starts the
     * moment the one before it ends: its stream found and, for a stream from
     * YouTube, its first bytes fetched. That also opens the connection the
     * player then reuses, and a refusal is answered with another YouTube
     * client now, not with a pause when the song is due.
     */
    fun prefetch(trackId: String) {
        if (cache[trackId]?.let { it.checked && it.expiresAt > System.currentTimeMillis() } == true) return
        if (!preparing.add(trackId)) return
        scope.launch {
            try {
                Log.i(TAG, "Ready ahead: $trackId (${ready(trackId)})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't get $trackId ready ahead: ${describe(e)}")
            } finally {
                preparing.remove(trackId)
            }
        }
    }

    /** Resolves [trackId] and checks its stream; says how it will play. */
    private suspend fun ready(trackId: String): String {
        repeat(YouTubeProfile.entries.size) {
            val remote = when (val resolved = resolve(trackId)) {
                is Resolved.Local -> return "on this phone"
                is Resolved.Remote -> resolved
            }
            val youTube = current[trackId] != null
            if (remote.hls || http == null || own(trackId) != null) return "stream found"
            val code = withContext(Dispatchers.IO) { firstBytes(http, remote) }
            when {
                code in 200..299 -> {
                    cache.computeIfPresent(trackId) { _, c -> if (c.value == remote) c.copy(checked = true) else c }
                    return "stream checked"
                }
                code == 403 && youTube && tryNextProfile(trackId) ->
                    Log.i(TAG, "YouTube refused the stream of $trackId ahead of time; trying another client")
                else -> throw IOException("its stream answered HTTP $code")
            }
        }
        throw IOException("YouTube refused every client")
    }

    /** The answer to a request for the stream's first kilobyte (read, so the connection stays open for the player). */
    private fun firstBytes(client: OkHttpClient, remote: Resolved.Remote): Int {
        val request = Request.Builder().url(remote.url).headers(remote.headers.toHeaders()).header("Range", "bytes=0-1023").build()
        return client.newCall(request).execute().use { response ->
            response.body.bytes()
            response.code
        }
    }

    suspend fun resolve(trackId: String): Resolved {
        local(trackId)?.let { return it }
        own(trackId)?.let { return it }
        cache[trackId]?.let { if (it.expiresAt > System.currentTimeMillis()) return it.value }
        val job = inFlight.getOrPut(trackId) { scope.async { fetch(trackId) } }
        return try {
            job.await()
        } finally {
            inFlight.remove(trackId, job)
        }
    }

    /**
     * The user's own songs play from where they are: a file on the phone,
     * or a stream from their Navidrome (the login is added to the request
     * by the app's HTTP client).
     */
    private suspend fun own(trackId: String): Resolved? {
        val track = library.track(trackId) ?: return null
        val url = track.streamUrl ?: return null
        return when (track.source) {
            Source.PHONE -> Resolved.Local(Uri.parse(url))
            Source.NAVIDROME -> Resolved.Remote(url, emptyMap(), hls = false)
            else -> null
        }
    }

    private suspend fun local(trackId: String): Resolved? {
        val uri = library.entity(trackId)?.localUri ?: return null
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file" && parsed.path?.let { File(it).exists() } != true) return null
        return Resolved.Local(parsed)
    }

    private suspend fun fetch(trackId: String): Resolved {
        val track = library.track(trackId) ?: throw IOException("Unknown track")
        val source = sourceUrl(track)
        val profile = if (YouTubeProfile.isYouTube(source)) current.getOrPut(trackId) { preferred } else null
        val format = when (settings.settings.value.streamQuality) {
            StreamQuality.HIGH -> "bestaudio[protocol^=http][ext=m4a]/bestaudio[protocol^=http]/bestaudio/best"
            StreamQuality.SAVER -> "worstaudio[abr>=64][protocol^=http]/bestaudio[abr<=96]/worstaudio/bestaudio/best"
        }
        val json = parseJson(ytDlp.describe(source, "-f", format, *profile?.args.orEmpty().toTypedArray()))
        val remote = pickStream(json) ?: throw IOException("No audio stream found for “${track.title}”.")
        StreamDns.pin(remote.url)
        cache[trackId] = Cached(remote, expiry(remote.url))
        return remote
    }

    /**
     * The page yt-dlp should read for [track]: its own stream, or for a
     * catalogue track the recording it was matched to (found once, then
     * remembered).
     */
    suspend fun sourceUrl(track: Track): String {
        when (track.source) {
            // The original file, with the login (yt-dlp makes its own requests).
            Source.NAVIDROME -> {
                val server = navidrome() ?: throw IOException("Navidrome isn't connected any more (Settings → Recommendations → Navidrome).")
                val stream = track.streamUrl ?: throw IOException("Unknown Navidrome song.")
                return server.authenticate(stream.replace("/rest/stream?", "/rest/download?"))
            }
            Source.PHONE -> throw IOException("“${track.title}” is already on this phone.")
            else -> Unit
        }
        track.streamUrl?.let { return it }
        library.entity(track.id)?.matchedUrl?.let { return it }
        track.matchUrl?.let {
            library.setMatchedUrl(track.id, it)
            return it
        }
        val match = catalog.match(track)?.streamUrl
            ?: throw IOException("Couldn't find “${track.title}” by ${track.artist} on YouTube Music.")
        library.setMatchedUrl(track.id, match)
        return match
    }

    /** Forget the recording a catalogue track was matched to, to search again. */
    suspend fun rematch(trackId: String) {
        library.setMatchedUrl(trackId, null)
        invalidate(trackId)
    }

    companion object {
        private const val TAG = "KultrDL"

        fun pickStream(json: JsonElement): Resolved.Remote? {
            val chosen: JsonElement = json.at("requested_formats").list
                .firstOrNull { it.at("vcodec").str == "none" || it.at("acodec").str.let { a -> a != null && a != "none" } }
                ?: json
            val url = chosen.at("url").str ?: json.at("url").str ?: return null
            val headers = (chosen.at("http_headers") as? JsonObject ?: json.at("http_headers") as? JsonObject)
                ?.mapNotNull { (k, v) -> v.str?.let { k to it } }
                ?.toMap()
                .orEmpty()
            val protocol = (chosen.at("protocol").str ?: json.at("protocol").str).orEmpty()
            return Resolved.Remote(url, headers, hls = protocol.startsWith("m3u8") || url.contains(".m3u8"))
        }

        /** Stream links carry their expiry; stop using them ten minutes before. */
        fun expiry(url: String): Long {
            val now = System.currentTimeMillis()
            val expire = Regex("[?&/]expire[=/](\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            return if (expire != null) {
                minOf(expire * 1000 - 10 * 60_000, now + 4 * 3600_000L).coerceAtLeast(now + 60_000)
            } else {
                now + 20 * 60_000
            }
        }
    }
}
