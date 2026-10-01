package app.kultr.dl.data

import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.LenientJson
import kotlinx.serialization.Serializable

/**
 * The library and settings as one JSON file: favourites, saved tracks,
 * listening history, playlists and saved servers (without their secrets). Downloaded files stay where they are
 * (Music/KultrDL); they are not part of the backup.
 */
@Serializable
data class BackupFile(
    val app: String = "KultrDL",
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: Settings? = null,
    val tracks: List<BackupTrack> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    /** Saved servers, without passwords or keys. */
    val servers: List<SavedServer> = emptyList(),
    /** Blocked artists and answers to suggestions. */
    val taste: TasteData? = null,
    /** The Navidrome connection, without its password. */
    val navidrome: NavidromeConfig? = null,
) {
    fun encode(): String = LenientJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): BackupFile = LenientJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class BackupTrack(
    val track: Track,
    val favorite: Boolean = false,
    val favoritedAt: Long? = null,
    val saved: Boolean = false,
    val savedAt: Long? = null,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val matchedUrl: String? = null,
)

@Serializable
data class BackupPlaylist(
    val name: String,
    val trackIds: List<String>,
    val sourceUrl: String? = null,
    val artworkUrl: String? = null,
)
