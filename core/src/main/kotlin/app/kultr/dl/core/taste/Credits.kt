package app.kultr.dl.core.taste

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Text

/**
 * Who is on a track: the artists it is credited to, and anyone featured,
 * whether the feature is in the artist field ("A feat. B", "A, B & C") or
 * in the title ("Song (feat. B)", "Song ft. B", "Song (with B)").
 */
object Credits {
    private val FEAT_BRACKET = Regex("(?i)[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+([^)\\]]+)[)\\]]")
    private val FEAT_TAIL = Regex("(?i)\\s(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$")
    private val CHANNEL_NOISE = Regex("(?i)(\\s*-\\s*topic|vevo)$")

    /** The key artists are compared by: lower case, no accents, no "- Topic"/"VEVO" channel suffixes. */
    fun key(name: String): String = Text.normalize(CHANNEL_NOISE.replace(name.trim(), "").trim())

    /** The artist field's names, main artist first. */
    fun main(artist: String): List<String> {
        val whole = artist.trim()
        if (whole.isEmpty()) return emptyList()
        return (listOf(whole) + Text.splitArtists(whole)).distinctBy { key(it) }.filter { key(it).isNotEmpty() }
    }

    /** Names featured in the title. */
    fun featured(title: String): List<String> {
        val found = FEAT_BRACKET.findAll(title).map { it.groupValues[1] } +
            listOfNotNull(FEAT_TAIL.find(FEAT_BRACKET.replace(title, ""))?.groupValues?.get(1))
        return found.flatMap { Text.splitArtists(it) }.map { it.trim() }.filter { key(it).isNotEmpty() }.distinctBy { key(it) }.toList()
    }

    /** Everyone credited, as display names: the artist field's names, then the title's features. */
    fun everyone(artist: String, title: String, albumArtist: String? = null): List<String> =
        (main(artist) + featured(title) + main(albumArtist.orEmpty())).distinctBy { key(it) }

    fun keys(artist: String, title: String, albumArtist: String? = null): Set<String> =
        everyone(artist, title, albumArtist).map(::key).filter { it.isNotEmpty() }.toSet()

    /** The people to offer in "Block artist…": each credited artist once, whole credit lines left out. */
    fun people(artist: String, title: String): List<String> {
        val parts = Text.splitArtists(artist).ifEmpty { listOf(artist) } + featured(title)
        return parts.map { it.trim() }.filter { key(it).isNotEmpty() }.distinctBy { key(it) }
    }
}

/**
 * Artists the user never wants to hear: their own songs and every song
 * they are featured on are hidden and skipped.
 */
class ArtistBlocks(keys: Iterable<String>) {
    private val blocked: Set<String> = keys.map(Credits::key).filter { it.isNotEmpty() }.toSet()

    val isEmpty: Boolean get() = blocked.isEmpty()

    fun blocksArtist(name: String?): Boolean = name != null && !isEmpty && Credits.keys(name, "").any { it in blocked }

    fun blocks(artist: String, title: String, albumArtist: String? = null): Boolean =
        !isEmpty && Credits.keys(artist, title, albumArtist).any { it in blocked }

    fun blocks(track: Track): Boolean = blocks(track.artist, track.title, track.albumArtist)

    /** An album or playlist by a blocked artist (its other songs are filtered one by one). */
    fun blocks(collection: Collection): Boolean = blocksArtist(collection.subtitle)

    fun <T> filter(items: List<T>, artist: (T) -> String, title: (T) -> String): List<T> =
        if (isEmpty) items else items.filterNot { blocks(artist(it), title(it)) }

    fun tracks(list: List<Track>): List<Track> = if (isEmpty) list else list.filterNot(::blocks)

    companion object {
        val NONE = ArtistBlocks(emptyList())
    }
}
