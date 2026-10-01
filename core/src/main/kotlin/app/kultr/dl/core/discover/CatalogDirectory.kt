package app.kultr.dl.core.discover

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.sources.AppleMusic
import app.kultr.dl.core.sources.Deezer
import app.kultr.dl.core.taste.Credits

/**
 * Artists through Deezer's public API (discographies with dates and
 * genres, related artists, top songs), with Apple Music as a second
 * source for artists Deezer doesn't know or when Deezer is switched off.
 */
class CatalogDirectory(
    private val deezer: Deezer,
    private val apple: AppleMusic,
    private val useDeezer: () -> Boolean = { true },
) : ArtistDirectory {
    override suspend fun find(name: String): ArtistRef? {
        val key = Credits.key(name)
        if (key.isEmpty()) return null
        if (useDeezer()) {
            // The same name, and the best-known artist of that name.
            deezer.searchArtists(name).filter { Credits.key(it.name) == key }.maxByOrNull { it.fans }?.let { return it }
        }
        return apple.searchArtists(name).firstOrNull { Credits.key(it.name) == key }
    }

    override suspend fun releases(artist: ArtistRef): List<Collection> = when {
        artist.id.startsWith("deezer:") -> deezer.artistAlbums(artist.id.removePrefix("deezer:"), artist.name)
        artist.id.startsWith("apple:") -> apple.artistAlbums(artist.id.removePrefix("apple:"))
        else -> emptyList()
    }

    override suspend fun bestAlbums(artist: ArtistRef, limit: Int): List<Collection> = when {
        artist.id.startsWith("deezer:") -> deezer.popularAlbums(artist.id.removePrefix("deezer:"), artist.name).take(limit)
        artist.id.startsWith("apple:") -> apple.artistAlbums(artist.id.removePrefix("apple:")).filter { it.recordType == "album" }.take(limit)
        else -> emptyList()
    }

    override suspend fun similar(artist: ArtistRef): List<ArtistRef> =
        if (artist.id.startsWith("deezer:")) deezer.related(artist.id.removePrefix("deezer:")) else emptyList()

    override suspend fun topTracks(artist: ArtistRef, limit: Int): List<Track> =
        if (artist.id.startsWith("deezer:")) deezer.top(artist.id.removePrefix("deezer:"), limit) else emptyList()

    override suspend fun tracks(release: Collection): List<Track> = when {
        release.id.startsWith("deezer:album:") -> deezer.album(release.id.removePrefix("deezer:album:"))?.tracks.orEmpty()
        release.id.startsWith("apple:album:") -> apple.album(release.id.removePrefix("apple:album:"))?.tracks.orEmpty()
        else -> emptyList()
    }
}
