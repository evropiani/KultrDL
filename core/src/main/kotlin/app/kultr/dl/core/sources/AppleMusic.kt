package app.kultr.dl.core.sources

import app.kultr.dl.core.model.Collection
import app.kultr.dl.core.model.CollectionKind
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.net.Http
import app.kultr.dl.core.util.Text
import app.kultr.dl.core.util.at
import app.kultr.dl.core.util.int
import app.kultr.dl.core.util.list
import app.kultr.dl.core.util.long
import app.kultr.dl.core.util.parseJson
import app.kultr.dl.core.util.str
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement

/** The Apple Music catalogue through the public iTunes Search API. */
class AppleMusic(private val http: Http, private val country: () -> String) {
    suspend fun searchSongs(query: String, limit: Int = 30): List<Track> =
        parseResults(get("search?term=${enc(query)}&media=music&entity=song&limit=$limit")).first

    suspend fun searchAlbums(query: String, limit: Int = 20): List<Collection> =
        parseResults(get("search?term=${enc(query)}&media=music&entity=album&limit=$limit")).second

    /** An album with its tracks, or a single song (then [trackId] is set). */
    suspend fun album(collectionId: String): Collection? {
        val (tracks, albums) = parseResults(get("lookup?id=$collectionId&entity=song&limit=200"))
        val album = albums.firstOrNull() ?: return null
        return album.copy(tracks = tracks.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 })), trackCount = tracks.size)
    }

    suspend fun song(trackId: String): Track? = parseResults(get("lookup?id=$trackId")).first.firstOrNull()

    /** The most played songs in the user's country right now. */
    suspend fun topSongs(limit: Int = 25): List<Track> {
        val cc = country().lowercase().ifEmpty { "us" }
        val json = parseJson(http.get("https://rss.applemarketingtools.com/api/v2/$cc/music/most-played/$limit/songs.json"))
        return parseChart(json)
    }

    private suspend fun get(path: String): JsonElement {
        val cc = country().uppercase().ifEmpty { "US" }
        val sep = if ('?' in path) '&' else '?'
        return parseJson(http.get("https://itunes.apple.com/$path${sep}country=$cc"))
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun bigArtwork(url: String?): String? = url?.replace(Regex("/\\d+x\\d+(bb)?\\.(jpg|png|webp)$"), "/600x600bb.jpg")

        fun parseResults(root: JsonElement): Pair<List<Track>, List<Collection>> {
            val tracks = mutableListOf<Track>()
            val albums = mutableListOf<Collection>()
            for (r in root.at("results").list) {
                when (r.at("wrapperType").str) {
                    "track" -> if (r.at("kind").str == null || r.at("kind").str == "song") parseTrack(r)?.let(tracks::add)
                    "collection" -> parseAlbum(r)?.let(albums::add)
                }
            }
            return tracks to albums
        }

        private fun parseTrack(r: JsonElement): Track? {
            val id = r.at("trackId").long ?: return null
            return Track(
                id = "apple:$id",
                source = Source.APPLE_MUSIC,
                title = r.at("trackName").str ?: return null,
                artist = r.at("artistName").str ?: "Unknown artist",
                album = r.at("collectionName").str,
                albumArtist = r.at("collectionArtistName").str ?: r.at("artistName").str,
                durationMs = r.at("trackTimeMillis").long,
                artworkUrl = bigArtwork(r.at("artworkUrl100").str ?: r.at("artworkUrl60").str),
                pageUrl = r.at("trackViewUrl").str?.substringBefore("&uo="),
                year = Text.year(r.at("releaseDate").str),
                trackNumber = r.at("trackNumber").int,
                discNumber = r.at("discNumber").int,
                genre = r.at("primaryGenreName").str,
                explicit = r.at("trackExplicitness").str == "explicit",
            )
        }

        private fun parseAlbum(r: JsonElement): Collection? {
            val id = r.at("collectionId").long ?: return null
            return Collection(
                id = "apple:album:$id",
                source = Source.APPLE_MUSIC,
                kind = CollectionKind.ALBUM,
                title = r.at("collectionName").str ?: return null,
                subtitle = r.at("artistName").str,
                artworkUrl = bigArtwork(r.at("artworkUrl100").str),
                pageUrl = r.at("collectionViewUrl").str?.substringBefore("?uo="),
                year = Text.year(r.at("releaseDate").str),
                trackCount = r.at("trackCount").int,
            )
        }

        fun parseChart(root: JsonElement): List<Track> = root.at("feed").at("results").list.mapNotNull { r ->
            val id = r.at("id").str ?: return@mapNotNull null
            Track(
                id = "apple:$id",
                source = Source.APPLE_MUSIC,
                title = r.at("name").str ?: return@mapNotNull null,
                artist = r.at("artistName").str ?: "Unknown artist",
                artworkUrl = bigArtwork(r.at("artworkUrl100").str),
                pageUrl = r.at("url").str,
                year = Text.year(r.at("releaseDate").str),
                genre = r.at("genres").list.firstOrNull().at("name").str,
            )
        }
    }
}
