package app.kultr.dl.engine

import kotlinx.serialization.Serializable

/** A bitrate or bit depth to download at. */
@Serializable
enum class Quality(val label: String, val ytDlp: String?) {
    ORIGINAL("Original, no re-encode", null),
    K320("320 kbps", "320K"),
    K256("256 kbps", "256K"),
    K192("192 kbps", "192K"),
    K160("160 kbps", "160K"),
    K128("128 kbps", "128K"),
    K96("96 kbps", "96K"),
    V0("VBR V0 (~245 kbps)", "0"),
    BIT16("16-bit", null),
    BIT24("24-bit", null),
}

/**
 * The file formats KultrDL writes. yt-dlp fetches the best audio the
 * source has and ffmpeg converts it; lossless formats keep that audio
 * exactly (they can't add detail the source never had).
 */
@Serializable
enum class AudioFormat(
    val label: String,
    val extension: String,
    val lossless: Boolean,
    val qualities: List<Quality>,
    val mime: String,
) {
    FLAC("FLAC", "flac", true, listOf(Quality.BIT16, Quality.BIT24), "audio/flac"),
    MP3("MP3", "mp3", false, listOf(Quality.K320, Quality.V0, Quality.K256, Quality.K192, Quality.K128), "audio/mpeg"),
    AAC("AAC (M4A)", "m4a", false, listOf(Quality.ORIGINAL, Quality.K256, Quality.K192, Quality.K128), "audio/mp4"),
    OPUS("Opus", "opus", false, listOf(Quality.ORIGINAL, Quality.K160, Quality.K128, Quality.K96), "audio/ogg"),
    ALAC("ALAC (M4A)", "m4a", true, listOf(Quality.BIT16, Quality.BIT24), "audio/mp4"),
    WAV("WAV", "wav", true, listOf(Quality.BIT16, Quality.BIT24), "audio/wav"),
    VORBIS("Ogg Vorbis", "ogg", false, listOf(Quality.K320, Quality.K256, Quality.K192, Quality.K128), "audio/ogg"),
    ORIGINAL("Original file", "", false, listOf(Quality.ORIGINAL), "audio/*"),
    ;

    fun normalise(quality: Quality): Quality = if (quality in qualities) quality else qualities.first()

    companion object {
        fun mimeFor(extension: String): String = when (extension.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac", "alac" -> "audio/mp4"
            "opus", "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "webm", "weba" -> "audio/webm"
            else -> "audio/*"
        }
    }
}

@Serializable
data class DownloadPreset(val format: AudioFormat = AudioFormat.MP3, val quality: Quality = Quality.K320) {
    val label: String
        get() = if (format == AudioFormat.ORIGINAL) format.label else "${format.label} · ${format.normalise(quality).label}"

    /** yt-dlp options that choose the source stream and convert it. */
    fun ytDlpOptions(): List<String> {
        val q = format.normalise(quality)
        val out = mutableListOf<String>()
        fun extract(codec: String) {
            out += listOf("-x", "--audio-format", codec)
            q.ytDlp?.let { out += listOf("--audio-quality", it) }
        }
        when (format) {
            AudioFormat.FLAC -> {
                out += listOf("-f", "bestaudio/best")
                extract("flac")
                out += listOf("--postprocessor-args", "ExtractAudio:" + if (q == Quality.BIT24) "-sample_fmt s32 -bits_per_raw_sample 24" else "-sample_fmt s16")
            }
            AudioFormat.MP3 -> {
                out += listOf("-f", "bestaudio/best")
                extract("mp3")
            }
            AudioFormat.AAC -> {
                out += listOf("-f", if (q == Quality.ORIGINAL) "bestaudio[acodec^=mp4a]/bestaudio/best" else "bestaudio/best")
                extract("m4a")
            }
            AudioFormat.OPUS -> {
                out += listOf("-f", if (q == Quality.ORIGINAL) "bestaudio[acodec=opus]/bestaudio/best" else "bestaudio/best")
                extract("opus")
            }
            AudioFormat.ALAC -> {
                out += listOf("-f", "bestaudio/best")
                extract("alac")
                out += listOf("--postprocessor-args", "ExtractAudio:-sample_fmt " + if (q == Quality.BIT24) "s32p" else "s16p")
            }
            AudioFormat.WAV -> {
                out += listOf("-f", "bestaudio/best")
                extract("wav")
                out += listOf("--postprocessor-args", "ExtractAudio:-c:a " + if (q == Quality.BIT24) "pcm_s24le" else "pcm_s16le")
            }
            AudioFormat.VORBIS -> {
                out += listOf("-f", "bestaudio/best")
                extract("vorbis")
            }
            AudioFormat.ORIGINAL -> out += listOf("-f", "bestaudio/best")
        }
        return out
    }
}
