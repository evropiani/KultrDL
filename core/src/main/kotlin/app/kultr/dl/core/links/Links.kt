package app.kultr.dl.core.links

import app.kultr.dl.core.model.Source
import java.net.URI

/** What a pasted link points at, before anything is fetched. */
data class LinkTarget(val source: Source, val kind: Kind, val id: String?, val url: String) {
    enum class Kind { TRACK, ALBUM, PLAYLIST, ARTIST, UNKNOWN }
}

object Links {
    private val URL = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val SPOTIFY_URI = Regex("^spotify:(track|album|playlist|artist):([A-Za-z0-9]+)$")

    /** The first link in shared text ("Listen to this: https://…"). */
    fun find(text: String): String? {
        val t = text.trim()
        if (SPOTIFY_URI.matches(t)) return t
        return URL.find(t)?.value?.trimEnd('.', ',', ')', ']', '!', '?')
    }

    fun isLink(text: String): Boolean = find(text) != null && text.trim().let { it.startsWith("http", true) || it.startsWith("spotify:") }

    /** Links that must be followed to see where they lead. */
    fun isShortLink(url: String): Boolean {
        val host = host(url) ?: return false
        return host in setOf("spotify.link", "deezer.page.link", "link.deezer.com", "on.soundcloud.com", "tidal.link", "amzn.to", "qobuz.link") ||
            host.endsWith(".app.link")
    }

    fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") }.getOrNull()

    private fun query(url: String, name: String): String? =
        runCatching { URI(url).rawQuery }.getOrNull()?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }

    private fun segments(url: String): List<String> =
        runCatching { URI(url).path }.getOrNull().orEmpty().split('/').filter { it.isNotEmpty() }

    fun classify(raw: String): LinkTarget {
        val url = raw.trim()
        SPOTIFY_URI.matchEntire(url)?.let { m ->
            return LinkTarget(Source.SPOTIFY, kindOf(m.groupValues[1]), m.groupValues[2], "https://open.spotify.com/${m.groupValues[1]}/${m.groupValues[2]}")
        }
        val host = host(url) ?: return LinkTarget(Source.WEB, LinkTarget.Kind.UNKNOWN, null, url)
        val path = segments(url)
        return when {
            host == "youtu.be" -> LinkTarget(Source.YOUTUBE, LinkTarget.Kind.TRACK, path.firstOrNull(), url)
            host == "music.youtube.com" || host.endsWith("youtube.com") || host == "youtube-nocookie.com" -> {
                val music = host == "music.youtube.com"
                val source = if (music) Source.YOUTUBE_MUSIC else Source.YOUTUBE
                val video = query(url, "v") ?: path.getOrNull(1)?.takeIf { path.firstOrNull() in setOf("shorts", "live", "embed") }
                when {
                    path.firstOrNull() == "browse" && path.getOrNull(1)?.startsWith("MPRE") == true ->
                        LinkTarget(Source.YOUTUBE_MUSIC, LinkTarget.Kind.ALBUM, path[1], url)
                    video != null -> LinkTarget(source, LinkTarget.Kind.TRACK, video, url)
                    query(url, "list") != null -> LinkTarget(source, LinkTarget.Kind.PLAYLIST, query(url, "list"), url)
                    path.firstOrNull() == "channel" || path.firstOrNull()?.startsWith("@") == true ->
                        LinkTarget(source, LinkTarget.Kind.ARTIST, path.getOrNull(1) ?: path.firstOrNull(), url)
                    else -> LinkTarget(source, LinkTarget.Kind.UNKNOWN, null, url)
                }
            }
            host == "open.spotify.com" || host == "play.spotify.com" -> {
                val p = path.dropWhile { it.startsWith("intl-") || it == "embed" }
                LinkTarget(Source.SPOTIFY, kindOf(p.getOrNull(0)), p.getOrNull(1), url)
            }
            host == "music.apple.com" || host == "itunes.apple.com" || host == "geo.music.apple.com" -> {
                val song = query(url, "i")
                val kindWord = path.firstOrNull { it in setOf("album", "song", "playlist", "artist") }
                val last = path.lastOrNull()
                when {
                    song != null -> LinkTarget(Source.APPLE_MUSIC, LinkTarget.Kind.TRACK, song, url)
                    kindWord == "song" -> LinkTarget(Source.APPLE_MUSIC, LinkTarget.Kind.TRACK, last?.removePrefix("id"), url)
                    kindWord == "album" -> LinkTarget(Source.APPLE_MUSIC, LinkTarget.Kind.ALBUM, last?.removePrefix("id"), url)
                    kindWord == "playlist" -> LinkTarget(Source.APPLE_MUSIC, LinkTarget.Kind.PLAYLIST, last, url)
                    kindWord == "artist" -> LinkTarget(Source.APPLE_MUSIC, LinkTarget.Kind.ARTIST, last, url)
                    else -> LinkTarget(Source.APPLE_MUSIC, LinkTarget.Kind.UNKNOWN, null, url)
                }
            }
            host.endsWith("deezer.com") -> {
                val i = path.indexOfFirst { it in setOf("track", "album", "playlist", "artist") }
                LinkTarget(Source.DEEZER, kindOf(path.getOrNull(i)), path.getOrNull(i + 1), url)
            }
            host.endsWith("tidal.com") -> {
                val i = path.indexOfFirst { it in setOf("track", "album", "playlist", "artist", "video") }
                val word = path.getOrNull(i)?.let { if (it == "video") "track" else it }
                LinkTarget(Source.TIDAL, kindOf(word), path.getOrNull(i + 1), url)
            }
            host.endsWith("qobuz.com") -> {
                val i = path.indexOfFirst { it in setOf("track", "album", "playlist", "artist", "interpreter") }
                val word = path.getOrNull(i)?.let { if (it == "interpreter") "artist" else it }
                LinkTarget(Source.QOBUZ, kindOf(word), path.lastOrNull(), url)
            }
            host.startsWith("music.amazon.") || (host.startsWith("amazon.") && path.firstOrNull() == "music") -> {
                val track = query(url, "trackAsin")
                val i = path.indexOfFirst { it in setOf("albums", "playlists", "user-playlists", "tracks", "artists") }
                val kind = when {
                    track != null -> LinkTarget.Kind.TRACK
                    else -> when (path.getOrNull(i)) {
                        "albums" -> LinkTarget.Kind.ALBUM
                        "playlists", "user-playlists" -> LinkTarget.Kind.PLAYLIST
                        "tracks" -> LinkTarget.Kind.TRACK
                        "artists" -> LinkTarget.Kind.ARTIST
                        else -> LinkTarget.Kind.UNKNOWN
                    }
                }
                LinkTarget(Source.AMAZON_MUSIC, kind, track ?: path.getOrNull(i + 1), url)
            }
            host.endsWith("soundcloud.com") -> {
                val kind = when {
                    path.getOrNull(1) == "sets" -> LinkTarget.Kind.PLAYLIST
                    path.size >= 2 -> LinkTarget.Kind.TRACK
                    path.size == 1 -> LinkTarget.Kind.ARTIST
                    else -> LinkTarget.Kind.UNKNOWN
                }
                LinkTarget(Source.SOUNDCLOUD, kind, null, url)
            }
            host.endsWith("bandcamp.com") -> {
                val kind = when (path.firstOrNull()) {
                    "track" -> LinkTarget.Kind.TRACK
                    "album" -> LinkTarget.Kind.ALBUM
                    else -> LinkTarget.Kind.ARTIST
                }
                LinkTarget(Source.BANDCAMP, kind, null, url)
            }
            else -> LinkTarget(Source.WEB, LinkTarget.Kind.UNKNOWN, null, url)
        }
    }

    private fun kindOf(word: String?): LinkTarget.Kind = when (word) {
        "track", "song" -> LinkTarget.Kind.TRACK
        "album" -> LinkTarget.Kind.ALBUM
        "playlist" -> LinkTarget.Kind.PLAYLIST
        "artist" -> LinkTarget.Kind.ARTIST
        else -> LinkTarget.Kind.UNKNOWN
    }
}
