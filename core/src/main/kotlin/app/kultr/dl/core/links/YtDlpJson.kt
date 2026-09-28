package app.kultr.dl.core.links

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.LinkResult
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Text
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.double
import app.kultr.dl.core.util.int
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/** Reads what `yt-dlp -J --flat-playlist` prints for a link or a search. */
object YtDlpJson {
    fun parse(json: String): LinkResult? = parse(parseJson(json))

    fun parse(root: JsonElement): LinkResult? {
        if (root.at("_type").str == "playlist" || root.at("entries") != null) {
            val entries = root.at("entries").list.mapNotNull { entry(it, root) }
            if (entries.isEmpty()) return null
            val title = root.at("title").str ?: "Playlist"
            val album = root.at("album").str
            val kind = if (album != null || (root.at("webpage_url").str ?: "").contains("/album/")) CollectionKind.ALBUM else CollectionKind.PLAYLIST
            val sourceId = root.at("id").str ?: hash(root.at("webpage_url").str ?: title)
            val source = sourceOf(root)
            return LinkResult.Many(
                Collection(
                    id = "${key(source)}:list:$sourceId",
                    source = source,
                    kind = kind,
                    title = album ?: title,
                    subtitle = root.at("artist").str ?: root.at("uploader").str ?: root.at("channel").str,
                    artworkUrl = thumbnail(root) ?: entries.firstNotNullOfOrNull { it.artworkUrl },
                    pageUrl = root.at("webpage_url").str ?: root.at("original_url").str,
                    trackCount = entries.size,
                    tracks = entries,
                ),
            )
        }
        return entry(root, null)?.let { LinkResult.Single(it) }
    }

    /** Search results: the entries of a "scsearch20:…" playlist. */
    fun parseSearch(json: String): List<Track> = when (val r = parse(json)) {
        is LinkResult.Many -> r.collection.tracks
        is LinkResult.Single -> listOf(r.track)
        null -> emptyList()
    }

    private fun entry(e: JsonElement, parent: JsonElement?): Track? {
        val url = e.at("webpage_url").str ?: e.at("url").str ?: e.at("original_url").str ?: return null
        val source = sourceOf(e, url)
        val id = e.at("id").str ?: hash(url)
        val rawTitle = e.at("track").str ?: e.at("title").str ?: return null
        val credited = e.at("artists").list.mapNotNull { it.str }.joinToString(", ").ifEmpty { null }
            ?: e.at("artist").str ?: e.at("creator").str
        val channel = e.at("uploader").str ?: e.at("channel").str
        val (artist, title) = if (credited != null || e.at("track").str != null) {
            (credited ?: channel.orEmpty()) to rawTitle
        } else if (source == Source.YOUTUBE || source == Source.YOUTUBE_MUSIC) {
            Text.artistAndTitle(rawTitle, channel)
        } else {
            channel.orEmpty() to rawTitle
        }
        val playUrl = when {
            source == Source.YOUTUBE_MUSIC && e.at("id").str != null -> "https://music.youtube.com/watch?v=$id"
            (source == Source.YOUTUBE) && e.at("id").str != null && !url.startsWith("http") -> "https://www.youtube.com/watch?v=$id"
            else -> url
        }
        return Track(
            id = "${key(source)}:$id",
            source = source,
            title = title,
            artist = artist.removeSuffix(" - Topic").ifEmpty { "Unknown artist" },
            album = e.at("album").str ?: parent?.at("album").str,
            albumArtist = e.at("album_artist").str,
            durationMs = e.at("duration").double?.let { (it * 1000).toLong() },
            artworkUrl = thumbnail(e) ?: parent?.let { thumbnail(it) },
            pageUrl = playUrl,
            streamUrl = playUrl,
            year = e.at("release_year").int ?: Text.year(e.at("release_date").str) ?: Text.year(e.at("upload_date").str),
            trackNumber = e.at("track_number").int,
            genre = e.at("genre").str,
        )
    }

    private fun thumbnail(e: JsonElement): String? =
        e.at("thumbnail").str ?: e.at("thumbnails").list.lastOrNull().at("url").str

    private fun sourceOf(e: JsonElement, url: String? = null): Source {
        val extractor = (e.at("ie_key").str ?: e.at("extractor_key").str ?: e.at("extractor").str).orEmpty().lowercase()
        val page = (url ?: e.at("webpage_url").str ?: e.at("url").str).orEmpty()
        return when {
            page.contains("music.youtube.com") -> Source.YOUTUBE_MUSIC
            extractor.startsWith("youtube") || page.contains("youtube.com") || page.contains("youtu.be") -> Source.YOUTUBE
            extractor.startsWith("soundcloud") || page.contains("soundcloud.com") -> Source.SOUNDCLOUD
            extractor.startsWith("bandcamp") || page.contains("bandcamp.com") -> Source.BANDCAMP
            else -> Source.WEB
        }
    }

    fun key(source: Source): String = when (source) {
        Source.YOUTUBE, Source.YOUTUBE_MUSIC -> "yt"
        Source.SOUNDCLOUD -> "soundcloud"
        Source.BANDCAMP -> "bandcamp"
        else -> "web"
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
