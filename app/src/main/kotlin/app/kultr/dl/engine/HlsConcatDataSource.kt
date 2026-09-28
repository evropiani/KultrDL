package app.kultr.dl.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Plays an HLS audio stream (SoundCloud serves these) as one continuous
 * file: the segments are fetched in order and joined, which gives a valid
 * MP3, ADTS, TS or fragmented MP4 stream for the progressive extractors.
 */
@OptIn(UnstableApi::class)
class HlsConcatDataSource(private val client: OkHttpClient) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var headers: Map<String, String> = emptyMap()
    private val segments = ArrayDeque<String>()
    private var response: Response? = null
    private var stream: InputStream? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val target = dataSpec.uri.getQueryParameter("u") ?: throw IOException("Missing stream address")
        headers = dataSpec.httpRequestHeaders
        segments.clear()
        segments.addAll(segmentsOf(target))
        if (segments.isEmpty()) throw IOException("The stream has no segments")
        opened = true
        transferStarted(dataSpec)
        // Seeking into a joined stream: read and drop up to the position.
        var skip = dataSpec.position
        val buffer = ByteArray(32 * 1024)
        while (skip > 0) {
            val n = readInternal(buffer, 0, minOf(buffer.size.toLong(), skip).toInt())
            if (n == C.RESULT_END_OF_INPUT) break
            skip -= n
        }
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = readInternal(buffer, offset, length)
        if (n > 0) bytesTransferred(n)
        return n
    }

    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val current = stream ?: run {
                val next = segments.removeFirstOrNull() ?: return C.RESULT_END_OF_INPUT
                openSegment(next)
            }
            val n = current.read(buffer, offset, length)
            if (n >= 0) return n
            closeSegment()
        }
    }

    private fun openSegment(url: String): InputStream {
        val r = client.newCall(request(url)).execute()
        if (!r.isSuccessful) {
            r.close()
            throw IOException("Segment request failed: HTTP ${r.code}")
        }
        response = r
        return r.body.byteStream().also { stream = it }
    }

    private fun closeSegment() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
    }

    private fun request(url: String): Request = Request.Builder().url(url).headers(headers.toHeaders()).build()

    private fun fetchText(url: String): String = client.newCall(request(url)).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Playlist request failed: HTTP ${r.code}")
        r.body.string()
    }

    /** The media segments, following a master playlist to its best variant. */
    private fun segmentsOf(url: String, depth: Int = 0): List<String> {
        val text = fetchText(url)
        val base = url.toHttpUrlOrNull() ?: throw IOException("Bad playlist address")
        fun resolve(ref: String) = base.resolve(ref.trim())?.toString() ?: ref.trim()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") } && depth < 3) {
            var best: Pair<Long, String>? = null
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val next = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                    if (best == null || bandwidth > best!!.first) best = bandwidth to resolve(next)
                }
            }
            return best?.let { segmentsOf(it.second, depth + 1) } ?: emptyList()
        }
        if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) {
            throw IOException("This stream is encrypted and can't be played.")
        }
        val out = mutableListOf<String>()
        lines.firstOrNull { it.startsWith("#EXT-X-MAP") }
            ?.let { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?.let { out += resolve(it) }
        lines.filter { !it.startsWith("#") }.forEach { out += resolve(it) }
        return out
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeSegment()
        segments.clear()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        const val SCHEME = "kultrhls"

        fun uriFor(url: String): Uri = Uri.parse("$SCHEME://stream?u=" + Uri.encode(url))
    }
}

/** Sends [HlsConcatDataSource.SCHEME] links to the HLS joiner and everything else on. */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val normal: DataSource, private val hls: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        hls.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == HlsConcatDataSource.SCHEME) hls else normal
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Not open")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(private val normal: DataSource.Factory, private val client: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(normal.createDataSource(), HlsConcatDataSource(client))
    }
}
