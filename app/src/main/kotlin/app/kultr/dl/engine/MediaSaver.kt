package app.kultr.dl.engine

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Puts finished downloads where music players find them: Music/KultrDL
 * in shared storage, or the app's own folder when asked (or when Android
 * 8–9 has no storage permission).
 */
class MediaSaver(private val context: Context) {
    data class Saved(val uri: Uri, val size: Long)

    data class Info(val name: String, val size: Long)

    fun save(file: File, fileName: String, mime: String, shared: Boolean, title: String, artist: String, album: String?): Saved {
        val size = file.length()
        if (shared && Build.VERSION.SDK_INT >= 29) return Saved(saveToMediaStore(file, fileName, mime, title, artist, album), size)
        val dir = if (shared && canWriteShared()) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"), FOLDER)
        }
        dir.mkdirs()
        val target = unique(dir, fileName)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Saved(Uri.fromFile(target), size)
    }

    @RequiresApi(29)
    private fun saveToMediaStore(file: File, fileName: String, mime: String, title: String, artist: String, album: String?): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            if (album != null) put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Android refused to create the file.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Couldn't write to shared storage.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    fun delete(uri: String): Boolean = runCatching {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "file" -> parsed.path?.let { File(it).delete() } ?: false
            "content" -> context.contentResolver.delete(parsed, null, null) > 0
            else -> false
        }
    }.getOrDefault(false)

    fun exists(uri: String): Boolean = runCatching {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "file" -> parsed.path?.let { File(it).exists() } ?: false
            "content" -> context.contentResolver.openFileDescriptor(parsed, "r")?.use { true } ?: false
            else -> false
        }
    }.getOrDefault(false)

    /** The name and size of a saved download, or null when it's gone. */
    fun describe(uri: String): Info? = runCatching {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "file" -> parsed.path?.let(::File)?.takeIf { it.isFile }?.let { Info(it.name, it.length()) }
            "content" -> context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                Info(c.getString(0) ?: "track", if (c.isNull(1)) -1 else c.getLong(1))
            }
            else -> null
        }
    }.getOrNull()

    fun open(uri: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Couldn't open the file on the phone.")

    fun needsPermission(shared: Boolean): Boolean = shared && Build.VERSION.SDK_INT < 29 && !canWriteShared()

    private fun canWriteShared(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun unique(dir: File, name: String): File {
        var candidate = File(dir, name)
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return candidate
    }

    companion object {
        const val FOLDER = "KultrDL"
    }
}
