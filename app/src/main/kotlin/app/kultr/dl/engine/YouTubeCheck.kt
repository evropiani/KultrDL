package app.kultr.dl.engine

import android.os.Build
import app.kultr.dl.BuildConfig
import app.kultr.dl.data.SettingsRepository
import app.kultr.dl.data.describe
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Settings → Engine → Test YouTube: asks YouTube for one video with each
 * profile, fetches the start of the audio, switches to the first profile
 * that works, and writes a report the user can copy. Stream links and
 * the phone's IP address are left out of the report.
 */
class YouTubeCheck(
    private val ytDlp: YtDlp,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    data class Outcome(val working: YouTubeProfile?, val report: String)

    suspend fun run(onStep: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        val lines = mutableListOf(
            "KultrDL ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
            "yt-dlp ${ytDlp.version() ?: "(bundled)"}",
            "Test video: $TEST_URL",
            "",
        )
        var working: YouTubeProfile? = null
        for (profile in YouTubeProfile.entries) {
            onStep("Trying ${profile.label}…")
            val (ok, detail) = try {
                probe(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YoutubeDLException) {
                false to listOf("yt-dlp: ${describe(e)}") + tail(e.message)
            } catch (e: Exception) {
                false to listOf(describe(e))
            }
            lines += "${profile.label}: ${if (ok) "works" else "refused"}"
            detail.forEach { lines += "  $it" }
            if (ok && working == null) working = profile
        }
        lines += ""
        if (working != null) {
            settings.update { it.copy(youtubeProfile = working) }
            lines += "Using: ${working.label}"
        } else {
            lines += "No profile worked on this network."
        }
        Outcome(working, lines.joinToString("\n"))
    }

    private suspend fun probe(profile: YouTubeProfile): Pair<Boolean, List<String>> {
        val response = ytDlp.run(
            listOf("-J", "--no-playlist", "-f", "bestaudio[protocol^=http]/bestaudio") + profile.args + TEST_URL,
            warnings = true,
        )
        val info = mutableListOf<String>()
        info += tail(response.err, 6)
        val json = parseJson(response.out)
        val stream = StreamResolver.pickStream(json) ?: return false to info + "no audio format"
        StreamDns.pin(stream.url)
        val url = stream.url.toHttpUrlOrNull()
        val family = url?.queryParameter("ip")?.let { if (':' in it) "IPv6" else "IPv4" } ?: "no ip"
        val format = listOfNotNull(
            json.at("format_id").str?.let { "format $it" },
            json.at("ext").str,
            json.at("acodec").str,
        ).joinToString(" ")
        val request = Request.Builder()
            .url(stream.url)
            .headers(stream.headers.toHeaders())
            .header("Range", "bytes=0-65535")
            .build()
        val code = http.newCall(request).execute().use { it.code }
        info += "$format, link bound to $family, HTTP $code"
        return (code in 200..299) to info
    }

    private fun tail(text: String?, count: Int = 8): List<String> =
        text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(count).map(::scrub)

    private fun scrub(line: String): String = line
        .replace(Regex("https?://[^\\s]*googlevideo\\.com[^\\s]*"), "<stream link>")
        .replace(Regex("\\bip=[^&\\s]+"), "ip=…")
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "<ip>")

    companion object {
        const val TEST_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }
}
