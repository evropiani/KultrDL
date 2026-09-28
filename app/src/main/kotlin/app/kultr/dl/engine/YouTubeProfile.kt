package app.kultr.dl.engine

import kotlinx.serialization.Serializable

/**
 * Ways of asking YouTube for a stream. YouTube refuses some of its clients
 * on some networks (HTTP 403 on the stream itself), so when one is refused
 * KultrDL tries the next and remembers the one that works.
 */
@Serializable
enum class YouTubeProfile(val label: String, val args: List<String>) {
    DEFAULT("Automatic", emptyList()),
    TV("TV client", listOf("--extractor-args", "youtube:player_client=tv")),
    EMBEDDED("Embedded player", listOf("--extractor-args", "youtube:player_client=web_embedded")),
    DEFAULT_IPV4("Automatic, IPv4 only", listOf("--force-ipv4")),
    TV_IPV4("TV client, IPv4 only", listOf("--force-ipv4", "--extractor-args", "youtube:player_client=tv")),
    ;

    /** The profiles to try, starting with [first]. */
    fun order(): List<YouTubeProfile> = listOf(this) + entries.filter { it != this }

    companion object {
        fun isYouTube(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com")
        }

        /** YouTube refused the stream (rather than the video being gone or the network down). */
        fun isForbidden(error: Throwable?): Boolean {
            var e = error
            while (e != null) {
                val message = e.message.orEmpty()
                if ("HTTP Error 403" in message || "403: Forbidden" in message || "Response code: 403" in message) return true
                if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && e.responseCode == 403) return true
                e = e.cause.takeIf { it !== e }
            }
            return false
        }
    }
}
