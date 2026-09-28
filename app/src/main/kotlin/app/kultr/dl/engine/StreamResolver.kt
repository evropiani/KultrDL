package app.kultr.dl.engine

import android.net.Uri
import app.kultr.dl.core.Catalog
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import app.kultr.dl.data.Library
import app.kultr.dl.data.StreamQuality
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    private val scope: CoroutineScope,
    private val quality: () -> StreamQuality,
) {
    sealed interface Resolved {
        data class Local(val uri: Uri) : Resolved
        data class Remote(val url: String, val headers: Map<String, String>, val hls: Boolean) : Resolved
    }

    private data class Cached(val value: Resolved.Remote, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Resolved>>()

    fun invalidate(trackId: String) {
        cache.remove(trackId)
    }

    /** Start resolving in the background, so the next track starts at once. */
    fun prefetch(trackId: String) {
        if (cache[trackId]?.let { it.expiresAt > System.currentTimeMillis() } == true) return
        scope.async { runCatching { resolve(trackId) } }
    }

    suspend fun resolve(trackId: String): Resolved {
        local(trackId)?.let { return it }
        cache[trackId]?.let { if (it.expiresAt > System.currentTimeMillis()) return it.value }
        val job = inFlight.getOrPut(trackId) { scope.async { fetch(trackId) } }
        return try {
            job.await()
        } finally {
            inFlight.remove(trackId, job)
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
        val format = when (quality()) {
            StreamQuality.HIGH -> "bestaudio[protocol^=http][ext=m4a]/bestaudio[protocol^=http]/bestaudio/best"
            StreamQuality.SAVER -> "worstaudio[abr>=64][protocol^=http]/bestaudio[abr<=96]/worstaudio/bestaudio/best"
        }
        val json = parseJson(ytDlp.describe(source, "-f", format))
        val remote = pickStream(json) ?: throw IOException("No audio stream found for “${track.title}”.")
        cache[trackId] = Cached(remote, expiry(remote.url))
        return remote
    }

    /**
     * The page yt-dlp should read for [track]: its own stream, or for a
     * catalogue track the recording it was matched to (found once, then
     * remembered).
     */
    suspend fun sourceUrl(track: Track): String {
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
