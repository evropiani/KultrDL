package app.kultr.dl.engine

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.kultr.dl.data.db.OwnedSongEntity
import app.kultr.dl.data.db.Owner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The music files on the phone, read from Android's media library: what
 * the user owns tells the recommendations who they like, and the files
 * play in mixes without a connection.
 */
class PhoneMusic(private val context: Context) {
    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun scan(): List<OwnedSongEntity> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(ALBUM_ARTIST)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        val out = ArrayList<OwnedSongEntity>()
        context.contentResolver.query(collection, columns, selection, null, null)?.use { c ->
            fun col(name: String) = c.getColumnIndex(name)
            val id = col(MediaStore.Audio.Media._ID)
            val title = col(MediaStore.Audio.Media.TITLE)
            val artist = col(MediaStore.Audio.Media.ARTIST)
            val album = col(MediaStore.Audio.Media.ALBUM)
            val albumId = col(MediaStore.Audio.Media.ALBUM_ID)
            val albumArtist = col(ALBUM_ARTIST)
            val year = col(MediaStore.Audio.Media.YEAR)
            val track = col(MediaStore.Audio.Media.TRACK)
            val duration = col(MediaStore.Audio.Media.DURATION)
            val added = col(MediaStore.Audio.Media.DATE_ADDED)
            val genre = if (Build.VERSION.SDK_INT >= 30) col(MediaStore.Audio.Media.GENRE) else -1
            fun str(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i)?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN } else null
            fun num(i: Int) = if (i >= 0 && !c.isNull(i)) c.getLong(i) else null
            while (c.moveToNext()) {
                val mediaId = num(id) ?: continue
                val name = str(title) ?: continue
                out += OwnedSongEntity(
                    id = "phone:$mediaId",
                    owner = Owner.PHONE.name,
                    title = name,
                    artist = str(artist) ?: str(albumArtist) ?: "Unknown artist",
                    album = str(album),
                    albumArtist = str(albumArtist),
                    genre = str(genre),
                    year = num(year)?.toInt()?.takeIf { it > 0 },
                    // MediaStore keeps the disc in the thousands: 2005 is disc 2, track 5.
                    trackNumber = num(track)?.toInt()?.rem(1000)?.takeIf { it > 0 },
                    durationMs = num(duration),
                    playCount = 0,
                    lastPlayedAt = null,
                    starred = false,
                    rating = 0,
                    artworkUrl = num(albumId)?.let { ContentUris.withAppendedId(ALBUM_ART, it).toString() },
                    streamUrl = ContentUris.withAppendedId(collection, mediaId).toString(),
                    artistId = null,
                    addedAt = num(added)?.times(1000),
                )
            }
        }
        out
    }

    companion object {
        private const val ALBUM_ARTIST = "album_artist"
        private const val UNKNOWN = "<unknown>"
        private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
