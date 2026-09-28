package app.kultr.dl.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.kultr.dl.core.model.Track

/** Queue entries point at "kultrdl://track/<id>"; the player resolves them when it loads them. */
object MediaItems {
    const val SCHEME = "kultrdl"
    private const val EXTRA_SOURCE = "kultrdl.source"

    fun uriFor(trackId: String): Uri = Uri.parse("$SCHEME://track/" + Uri.encode(trackId))

    fun trackId(uri: Uri): String? = if (uri.scheme == SCHEME) uri.lastPathSegment else null

    fun from(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(uriFor(track.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setAlbumArtist(track.albumArtist)
                .setArtworkUri(track.artworkUrl?.let(Uri::parse))
                .setTrackNumber(track.trackNumber)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putString(EXTRA_SOURCE, track.source.name) })
                .build(),
        )
        .build()

    /** A controller's item arrives without its URI; give it back. */
    fun restore(item: MediaItem): MediaItem = item.buildUpon().setUri(uriFor(item.mediaId)).build()
}
