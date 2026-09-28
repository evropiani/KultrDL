package app.kultr.dl.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kultr.dl.core.model.Track
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes

/**
 * Writes the track's details and cover into the downloaded file, so it
 * shows up properly in any player. FLAC, MP3, M4A (AAC and ALAC), Ogg
 * Vorbis and WAV; Opus files keep the tags ffmpeg wrote.
 */
object Tagger {
    private val SUPPORTED = setOf("flac", "mp3", "m4a", "mp4", "ogg", "wav", "aif", "aiff")

    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun canTag(file: File): Boolean = file.extension.lowercase() in SUPPORTED

    fun tag(file: File, track: Track, cover: ByteArray?) {
        if (!canTag(file)) return
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        fun set(key: FieldKey, value: String?) {
            if (!value.isNullOrBlank()) runCatching { tag.setField(key, value) }
        }
        set(FieldKey.TITLE, track.title)
        set(FieldKey.ARTIST, track.artist)
        set(FieldKey.ALBUM, track.album)
        set(FieldKey.ALBUM_ARTIST, track.albumArtist)
        set(FieldKey.YEAR, track.year?.toString())
        set(FieldKey.TRACK, track.trackNumber?.toString())
        set(FieldKey.DISC_NO, track.discNumber?.toString())
        set(FieldKey.GENRE, track.genre)
        set(FieldKey.ISRC, track.isrc)
        if (cover != null) {
            runCatching {
                tag.deleteArtworkField()
                tag.setField(CoverArt(cover))
            }
        }
        audio.commit()
    }

    /** Cover art as a square JPEG: video thumbnails are 16:9 with the art in the middle. */
    fun squareJpeg(bytes: ByteArray, max: Int = 1200): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        var square = if (x == 0 && y == 0) bitmap else Bitmap.createBitmap(bitmap, x, y, side, side)
        if (side > max) square = Bitmap.createScaledBitmap(square, max, max, true)
        return ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }
    }

    /** jaudiotagger's Android artwork, able to describe its own size (FLAC and Ogg need it). */
    private class CoverArt(bytes: ByteArray) : AndroidArtwork() {
        init {
            binaryData = bytes
            mimeType = "image/jpeg"
            pictureType = PictureTypes.DEFAULT_ID
            description = ""
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            width = bounds.outWidth.coerceAtLeast(0)
            height = bounds.outHeight.coerceAtLeast(0)
        }

        override fun setImageFromData(): Boolean = true
    }
}
