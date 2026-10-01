package app.kultr.dl.core

import app.kultr.dl.core.links.LinkTarget
import app.kultr.dl.core.links.Links
import app.kultr.dl.core.match.Matcher
import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.LinkResult
import app.kultr.dl.core.model.MediaExtractor
import app.kultr.dl.core.model.SearchResults
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.sources.AppleMusic
import app.kultr.dl.core.sources.Bandcamp
import app.kultr.dl.core.sources.Deezer
import app.kultr.dl.core.sources.Odesli
import app.kultr.dl.core.sources.Spotify
import app.kultr.dl.core.sources.WebPage
import app.kultr.dl.core.sources.YouTube
import app.kultr.dl.core.sources.YouTubeMusic
import app.kultr.dl.core.util.Text
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class NotSupportedException(message: String) : IOException(message)

/**
 * Everything KultrDL can search and every link it can open, in one place:
 * each source's own API where there is one, song.link and page metadata
 * for stores without one, and yt-dlp for the rest.
 */
class Catalog(
    http: Http,
    private val extractor: MediaExtractor,
    private val country: () -> String,
    spotifyCredentials: () -> Pair<String, String>?,
) {
    val youTubeMusic = YouTubeMusic(http)
    val youTube = YouTube(http)
    val apple = AppleMusic(http, country)
    val deezer = Deezer(http)
    val spotify = Spotify(http, spotifyCredentials)
    val bandcamp = Bandcamp(http)
    private val odesli = Odesli(http)
    private val web = WebPage(http)
    private val http = http

    suspend fun search(source: Source, query: String): SearchResults = coroutineScope {
        val q = query.trim()
        when (source) {
            Source.YOUTUBE_MUSIC -> {
                val songs = async { runCatching { youTubeMusic.searchSongs(q) }.rethrowCancel() }
                val albums = async { runCatching { youTubeMusic.searchAlbums(q) }.rethrowCancel() }
                val tracks = songs.await().getOrElse { fallbackSearch("https://music.youtube.com/search?q=${enc(q)}#songs", it) }
                SearchResults(tracks, albums.await().getOrDefault(emptyList()))
            }
            Source.YOUTUBE -> SearchResults(
                runCatching { youTube.search(q) }.rethrowCancel().getOrElse { fallbackSearch("ytsearch30:$q", it) },
            )
            Source.APPLE_MUSIC -> {
                val songs = async { apple.searchSongs(q) }
                val albums = async { runCatching { apple.searchAlbums(q) }.rethrowCancel().getOrDefault(emptyList()) }
                SearchResults(songs.await(), albums.await())
            }
            Source.DEEZER -> {
                val songs = async { deezer.searchTracks(q) }
                val albums = async { runCatching { deezer.searchAlbums(q) }.rethrowCancel().getOrDefault(emptyList()) }
                SearchResults(songs.await(), albums.await())
            }
            Source.SPOTIFY -> {
                if (!spotify.canSearch) {
                    throw NotSupportedException("Searching Spotify needs a free Spotify developer Client ID and secret (Settings → Sources). Spotify links work without one.")
                }
                val songs = async { spotify.searchTracks(q) }
                val albums = async { runCatching { spotify.searchAlbums(q) }.rethrowCancel().getOrDefault(emptyList()) }
                SearchResults(songs.await(), albums.await())
            }
            Source.SOUNDCLOUD -> SearchResults(extractor.search("scsearch", q, 25))
            Source.BANDCAMP -> bandcamp.search(q).let { (t, a) -> SearchResults(t, a) }
            else -> throw NotSupportedException("${source.label} can't be searched. Paste a ${source.label} link instead.")
        }
    }

    private suspend fun fallbackSearch(target: String, cause: Throwable): List<Track> {
        val (prefix, query) = if (target.contains(':') && !target.startsWith("http")) {
            target.substringBefore(':').removeSuffix("30") to target.substringAfter(':')
        } else {
            "url" to target
        }
        return try {
            if (prefix == "url") {
                (extractor.extract(target) as? LinkResult.Many)?.collection?.tracks.orEmpty()
            } else {
                extractor.search(prefix, query, 30)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw cause
        }
    }

    /** An album or playlist from search results, with its tracks. */
    suspend fun load(collection: Collection): Collection {
        if (collection.tracks.isNotEmpty()) return collection
        val id = collection.id
        val loaded = when {
            id.startsWith("ytm:") -> youTubeMusic.album(id.removePrefix("ytm:"))
            id.startsWith("apple:album:") -> apple.album(id.removePrefix("apple:album:"))
            id.startsWith("deezer:album:") -> deezer.album(id.removePrefix("deezer:album:"))
            id.startsWith("deezer:playlist:") -> deezer.playlist(id.removePrefix("deezer:playlist:"))
            id.startsWith("spotify:album:") -> spotify.load("album", id.removePrefix("spotify:album:")) as? Collection
            id.startsWith("spotify:playlist:") -> spotify.load("playlist", id.removePrefix("spotify:playlist:")) as? Collection
            collection.pageUrl != null -> (extractor.extract(collection.pageUrl) as? LinkResult.Many)?.collection
            else -> null
        } ?: throw IOException("Couldn't load “${collection.title}”.")
        return loaded.copy(
            title = loaded.title.ifEmpty { collection.title },
            subtitle = loaded.subtitle ?: collection.subtitle,
            artworkUrl = loaded.artworkUrl ?: collection.artworkUrl,
            year = loaded.year ?: collection.year,
        )
    }

    /** Whatever a pasted or shared link points at. */
    suspend fun resolve(text: String): LinkResult {
        var url = Links.find(text) ?: throw IOException("That doesn't look like a link.")
        if (Links.isShortLink(url)) url = runCatching { http.finalUrl(url) }.rethrowCancel().getOrDefault(url)
        val target = Links.classify(url)
        return when (target.source) {
            Source.SPOTIFY -> spotifyLink(target)
            Source.APPLE_MUSIC -> appleLink(target)
            Source.DEEZER -> deezerLink(target)
            Source.YOUTUBE_MUSIC -> if (target.kind == LinkTarget.Kind.ALBUM && target.id != null) {
                LinkResult.Many(youTubeMusic.album(target.id) ?: throw IOException("Couldn't open that album."))
            } else {
                extract(target.url)
            }
            Source.TIDAL, Source.AMAZON_MUSIC -> odesliLink(target)
            Source.QOBUZ -> pageLink(target)
            Source.YOUTUBE, Source.SOUNDCLOUD, Source.BANDCAMP, Source.WEB -> extract(target.url)
            Source.NAVIDROME, Source.PHONE, Source.LISTENBRAINZ -> extract(target.url)
        }
    }

    private suspend fun extract(url: String): LinkResult =
        extractor.extract(url) ?: throw IOException("Nothing playable was found at that link.")

    private suspend fun spotifyLink(target: LinkTarget): LinkResult {
        val id = target.id ?: throw NotSupportedException("That Spotify link isn't a track, album or playlist.")
        return when (target.kind) {
            LinkTarget.Kind.TRACK -> {
                val track = spotify.load("track", id) as? Track ?: throw IOException("Couldn't read that Spotify track.")
                LinkResult.Single(withOdesliHint(track, target.url))
            }
            LinkTarget.Kind.ALBUM -> LinkResult.Many(spotify.load("album", id) as? Collection ?: throw IOException("Couldn't read that Spotify album."))
            LinkTarget.Kind.PLAYLIST -> LinkResult.Many(spotify.load("playlist", id) as? Collection ?: throw IOException("Couldn't read that Spotify playlist."))
            else -> throw NotSupportedException("Artist links aren't supported yet. Open a track, album or playlist.")
        }
    }

    private suspend fun appleLink(target: LinkTarget): LinkResult {
        val id = target.id?.filter(Char::isDigit)?.ifEmpty { null }
        return when (target.kind) {
            LinkTarget.Kind.TRACK -> LinkResult.Single(apple.song(id ?: throw IOException("Unknown Apple Music song.")) ?: throw IOException("Apple Music didn't find that song."))
            LinkTarget.Kind.ALBUM -> LinkResult.Many(apple.album(id ?: throw IOException("Unknown Apple Music album.")) ?: throw IOException("Apple Music didn't find that album."))
            LinkTarget.Kind.PLAYLIST -> pageLink(target)
            else -> throw NotSupportedException("Artist links aren't supported yet. Open a song, album or playlist.")
        }
    }

    private suspend fun deezerLink(target: LinkTarget): LinkResult {
        val id = target.id ?: throw NotSupportedException("That Deezer link isn't a track, album or playlist.")
        return when (target.kind) {
            LinkTarget.Kind.TRACK -> LinkResult.Single(deezer.track(id) ?: throw IOException("Deezer didn't find that track."))
            LinkTarget.Kind.ALBUM -> LinkResult.Many(deezer.album(id) ?: throw IOException("Deezer didn't find that album."))
            LinkTarget.Kind.PLAYLIST -> LinkResult.Many(deezer.playlist(id) ?: throw IOException("Deezer didn't find that playlist."))
            else -> throw NotSupportedException("Artist links aren't supported yet. Open a track, album or playlist.")
        }
    }

    /** Tidal and Amazon Music: song.link knows what the link is, and where it is on YouTube. */
    private suspend fun odesliLink(target: LinkTarget): LinkResult {
        if (target.kind == LinkTarget.Kind.PLAYLIST) return pageLink(target)
        val entity = odesli.lookup(target.url, country().uppercase().ifEmpty { "US" })
            ?: return pageLink(target)
        val artist = entity.artist ?: "Unknown artist"
        return if (entity.type == "album") {
            LinkResult.Many(albumByName(entity.title, artist, target, entity.artworkUrl))
        } else {
            LinkResult.Single(
                Track(
                    id = "${target.source.name.lowercase()}:${target.id ?: Text.normalize(entity.title)}",
                    source = target.source,
                    title = entity.title,
                    artist = artist,
                    artworkUrl = entity.artworkUrl,
                    pageUrl = target.url,
                    matchUrl = entity.youtubeUrl,
                ),
            )
        }
    }

    /** Qobuz, and playlists on stores without an API: read the page itself. */
    private suspend fun pageLink(target: LinkTarget): LinkResult {
        val meta = web.read(target.url)
        val store = target.source.label.substringBefore(' ')
        val (pageTitle, pageArtist) = WebPage.splitTitle(meta.schemaName ?: meta.title ?: "", store)
        val artist = meta.byArtist ?: pageArtist ?: meta.musician
        if (meta.recordings.isNotEmpty()) {
            val isAlbum = meta.schemaType == "MusicAlbum" || target.kind == LinkTarget.Kind.ALBUM
            val tracks = meta.recordings.mapIndexed { i, r ->
                Track(
                    id = "${target.source.name.lowercase()}:${target.id ?: "page"}:$i",
                    source = target.source,
                    title = r.title,
                    artist = r.artist ?: artist ?: "Unknown artist",
                    album = if (isAlbum) pageTitle else null,
                    albumArtist = if (isAlbum) artist else null,
                    durationMs = r.durationMs,
                    artworkUrl = if (isAlbum) meta.image else null,
                    pageUrl = target.url,
                    trackNumber = if (isAlbum) i + 1 else null,
                )
            }
            return LinkResult.Many(
                Collection(
                    id = "${target.source.name.lowercase()}:${if (isAlbum) "album" else "playlist"}:${target.id ?: "page"}",
                    source = target.source,
                    kind = if (isAlbum) CollectionKind.ALBUM else CollectionKind.PLAYLIST,
                    title = pageTitle.ifEmpty { "Untitled" },
                    subtitle = artist,
                    artworkUrl = meta.image,
                    pageUrl = target.url,
                    trackCount = tracks.size,
                    tracks = tracks,
                ),
            )
        }
        if (pageTitle.isEmpty()) throw IOException("Couldn't read that ${target.source.label} page.")
        return when (target.kind) {
            LinkTarget.Kind.ALBUM -> LinkResult.Many(albumByName(pageTitle, artist ?: "", target, meta.image))
            LinkTarget.Kind.TRACK -> LinkResult.Single(
                Track(
                    id = "${target.source.name.lowercase()}:${target.id ?: Text.normalize(pageTitle)}",
                    source = target.source,
                    title = pageTitle,
                    artist = artist ?: "Unknown artist",
                    artworkUrl = meta.image,
                    pageUrl = target.url,
                ),
            )
            else -> throw NotSupportedException("${target.source.label} ${target.kind.name.lowercase()} links can't be read yet. Try a track or album link.")
        }
    }

    /** An album known only by name: its track list from Deezer, or else Apple Music. */
    private suspend fun albumByName(title: String, artist: String, target: LinkTarget, artwork: String?): Collection {
        val query = "$artist $title".trim()
        fun pick(options: List<Collection>): Collection? = options.maxByOrNull {
            Text.similarity(Text.coreTitle(title), Text.coreTitle(it.title)) * 2 + Text.similarity(artist, it.subtitle.orEmpty())
        }?.takeIf { Text.similarity(Text.coreTitle(title), Text.coreTitle(it.title)) > 0.6 }
        val found = runCatching { pick(deezer.searchAlbums(query))?.let { load(it) } }.rethrowCancel().getOrNull()
            ?: runCatching { pick(apple.searchAlbums(query))?.let { load(it) } }.rethrowCancel().getOrNull()
            ?: throw IOException("Couldn't find the tracks of “$title”.")
        return found.copy(
            id = "${target.source.name.lowercase()}:album:${target.id ?: found.id}",
            source = target.source,
            pageUrl = target.url,
            artworkUrl = artwork ?: found.artworkUrl,
            tracks = found.tracks.map { it.copy(source = target.source, id = "${target.source.name.lowercase()}:${it.id}", pageUrl = target.url) },
        )
    }

    private suspend fun withOdesliHint(track: Track, url: String): Track {
        val hint = runCatching { odesli.lookup(url)?.youtubeUrl }.rethrowCancel().getOrNull() ?: return track
        return track.copy(matchUrl = hint)
    }

    /**
     * The recording to play or download for a catalogue track: YouTube
     * Music songs first, then music videos, then YouTube.
     */
    suspend fun match(track: Track): Track? {
        if (!track.needsMatch) return track
        val query = Matcher.query(track)
        val songs = runCatching { youTubeMusic.searchSongs(query) }.rethrowCancel().getOrDefault(emptyList())
        Matcher.best(track, songs)?.let { return it }
        val videos = runCatching { youTubeMusic.searchVideos(query) }.rethrowCancel().getOrDefault(emptyList())
        Matcher.best(track, videos)?.let { return it }
        val uploads = runCatching { youTube.search(query) }.rethrowCancel()
            .getOrElse { runCatching { extractor.search("ytsearch", query, 10) }.rethrowCancel().getOrDefault(emptyList()) }
        Matcher.best(track, uploads)?.let { return it }
        // Nothing clearly right: the top song result is still better than nothing when it is close.
        return (songs + videos).maxByOrNull { Matcher.score(track, it) }?.takeIf { Matcher.score(track, it) >= Matcher.ACCEPT - 15 }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

private fun <T> Result<T>.rethrowCancel(): Result<T> {
    exceptionOrNull()?.let { if (it is CancellationException) throw it }
    return this
}
