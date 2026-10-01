package app.kultr.dl.core.discover

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.taste.ArtistBlocks
import app.kultr.dl.core.taste.Credits
import app.kultr.dl.core.taste.Taste
import app.kultr.dl.core.util.Text
import kotlinx.serialization.Serializable

/** An album or single suggested, and why. */
@Serializable
data class Pick(
    val collection: Collection,
    val artist: String,
    val reason: String,
    val key: String,
)

/** A playlist made for the user; [id] stays the same from day to day, so it can be followed. */
@Serializable
data class Mix(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>,
) {
    val artworkUrls: List<String> get() = tracks.mapNotNull { it.artworkUrl }.distinct().take(4)

    fun asCollection(): Collection = Collection(
        id = "mix:$id",
        source = Source.WEB,
        kind = CollectionKind.PLAYLIST,
        title = title,
        subtitle = subtitle,
        artworkUrl = artworkUrls.firstOrNull(),
        trackCount = tracks.size,
        tracks = tracks,
    )
}

/** Everything on the "For you" page, as last worked out. */
@Serializable
data class Feed(
    val builtAt: Long = 0,
    val releases: List<Pick> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    val albums: List<Pick> = emptyList(),
    val missing: List<Pick> = emptyList(),
    val rediscover: List<Track> = emptyList(),
    /** The artists the suggestions grew from, most liked first. */
    val seeds: List<String> = emptyList(),
    /** Nothing could be fetched; only suggestions from what is on the phone. */
    val offline: Boolean = false,
) {
    val isEmpty: Boolean get() = releases.isEmpty() && mixes.isEmpty() && albums.isEmpty() && missing.isEmpty() && rediscover.isEmpty()

    /** The same feed with anything now blocked or dismissed left out. */
    fun filtered(rules: Rules): Feed = copy(
        releases = releases.filter { rules.allows(it) },
        albums = albums.filter { rules.allows(it) },
        missing = missing.filter { rules.allows(it) },
        mixes = mixes.map { m -> m.copy(tracks = m.tracks.filter(rules::allows)) }.filter { it.tracks.isNotEmpty() },
        rediscover = rediscover.filter(rules::allows),
    )
}

/** Stable keys for "not interested": the same song or album from any source gets the same key. */
object Keys {
    private val EDITION = Regex("(?i)\\s*[(\\[][^)\\]]*(deluxe|edition|expanded|anniversary|bonus|explicit|clean|version|remaster)[^)\\]]*[)\\]]")

    fun primary(artist: String?): String = artist.orEmpty().let { a -> Text.splitArtists(a).firstOrNull() ?: a }

    fun albumTitle(title: String): String = Text.coreTitle(EDITION.replace(title, ""))

    fun artist(name: String): String = "artist:" + Credits.key(name)

    fun album(artist: String?, title: String): String = "album:" + Credits.key(primary(artist)) + "|" + albumTitle(title)

    fun track(artist: String, title: String): String = "track:" + Credits.key(primary(artist)) + "|" + Text.coreTitle(title)
}

/** What the user already has: files on the phone, Navidrome, and the KultrDL library. */
class Owned private constructor(
    private val albums: Set<String>,
    private val songs: Set<String>,
    private val artists: Set<String>,
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty()

    fun hasAlbum(artist: String?, title: String): Boolean = Keys.album(artist, title) in albums

    fun hasSong(artist: String, title: String): Boolean = Keys.track(artist, title) in songs

    fun hasArtist(name: String): Boolean = Credits.key(name) in artists || Credits.key(Keys.primary(name)) in artists

    class Builder {
        private val albums = HashSet<String>()
        private val songs = HashSet<String>()
        private val artists = HashSet<String>()

        fun add(artist: String, title: String, album: String? = null, albumArtist: String? = null): Builder {
            if (title.isNotBlank()) songs += Keys.track(artist, title)
            if (!album.isNullOrBlank()) albums += Keys.album(albumArtist?.takeIf { it.isNotBlank() } ?: artist, album)
            Credits.main(artist).forEach { artists += Credits.key(it) }
            albumArtist?.let { aa -> Credits.main(aa).forEach { artists += Credits.key(it) } }
            return this
        }

        fun build() = Owned(albums, songs, artists)
    }

    companion object {
        val NONE = Builder().build()
    }
}

/**
 * What must never be suggested: blocked artists (and songs they are on),
 * anything marked "not interested", and genres left out.
 */
class Rules(
    val blocks: ArtistBlocks = ArtistBlocks.NONE,
    val dismissed: Set<String> = emptySet(),
    excludedGenres: Iterable<String> = emptyList(),
    /** Each artist's genres (by [Credits.key]), as far as they are known. */
    val artistGenres: Map<String, List<String>> = emptyMap(),
) {
    private val excluded: Set<String> = excludedGenres.map { Taste.genreName(it).lowercase() }.toSet()

    fun withGenres(more: Map<String, List<String>>) = Rules(blocks, dismissed, excluded, artistGenres + more)

    private fun excludedGenre(genre: String?): Boolean = genre != null && Taste.genreName(genre).lowercase() in excluded

    private fun excludedArtist(name: String): Boolean {
        if (excluded.isEmpty()) return false
        val genres = artistGenres[Credits.key(name)] ?: artistGenres[Credits.key(Keys.primary(name))] ?: return false
        return genres.firstOrNull()?.let(::excludedGenre) == true
    }

    fun allowsArtist(name: String): Boolean = !blocks.blocksArtist(name) && Keys.artist(name) !in dismissed && !excludedArtist(name)

    fun allows(track: Track): Boolean = !blocks.blocks(track) &&
        Keys.track(track.artist, track.title) !in dismissed &&
        Keys.artist(Keys.primary(track.artist)) !in dismissed &&
        !excludedGenre(track.genre) && !excludedArtist(track.artist)

    fun allows(collection: Collection): Boolean {
        val artist = collection.subtitle.orEmpty()
        return !blocks.blocks(collection) &&
            Keys.album(artist, collection.title) !in dismissed &&
            (artist.isEmpty() || allowsArtist(artist)) &&
            !excludedGenre(collection.genre)
    }

    fun allows(pick: Pick): Boolean = pick.key !in dismissed && allows(pick.collection)
}
