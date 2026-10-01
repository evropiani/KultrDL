package app.kultr.dl.core.util

import java.text.Normalizer

/** Text helpers for comparing titles and names across services. */
object Text {
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val FEAT = Regex("(?i)[(\\[]\\s*(feat\\.?|ft\\.?|featuring|with)\\s[^)\\]]*[)\\]]")
    private val FEAT_TAIL = Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s.*$")
    private val DASH_VERSION = Regex("(?i)\\s+-\\s+(\\d{4}\\s+)?(remaster(ed)?|mono|stereo|single version|album version|original mix|bonus track).*$")
    private val BRACKET_VERSION = Regex("(?i)[(\\[][^)\\]]*(remaster(ed)?|mono|stereo|single version|album version|explicit|clean)[^)\\]]*[)\\]]")
    private val VIDEO_NOISE = Regex(
        "(?i)[(\\[][^)\\]]*(official|video|audio|lyrics?|visuali[sz]er|hd|4k|mv|m/v|clip)[^)\\]]*[)\\]]",
    )

    /** Lower case, no accents, "&" as "and", words separated by single spaces. */
    fun normalize(text: String): String {
        val stripped = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
        return NON_WORD.replace(stripped.lowercase().replace("&", " and "), " ").trim()
    }

    /** A title without featured artists and remaster notes, for comparing recordings. */
    fun coreTitle(title: String): String {
        var t = FEAT.replace(title, "")
        t = FEAT_TAIL.replace(t, "")
        t = DASH_VERSION.replace(t, "")
        t = BRACKET_VERSION.replace(t, "")
        return normalize(t)
    }

    /** "Song (Official Music Video) [HD]" → "Song". */
    fun stripVideoNoise(title: String): String =
        VIDEO_NOISE.replace(title, "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { title }

    fun tokens(text: String): Set<String> = normalize(text).split(' ').filter { it.isNotEmpty() }.toSet()

    /** Dice coefficient of the word sets, falling back on letter pairs for one-word titles. */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val ta = na.split(' ').toSet()
        val tb = nb.split(' ').toSet()
        val words = 2.0 * ta.intersect(tb).size / (ta.size + tb.size)
        return maxOf(words, bigramSimilarity(na, nb))
    }

    private fun bigramSimilarity(a: String, b: String): Double {
        fun pairs(s: String): List<String> = s.replace(" ", "").windowed(2)
        val pa = pairs(a)
        val pb = pairs(b).toMutableList()
        if (pa.isEmpty() || pb.isEmpty()) return 0.0
        var hits = 0
        for (p in pa) {
            val i = pb.indexOf(p)
            if (i >= 0) {
                hits++
                pb.removeAt(i)
            }
        }
        return 2.0 * hits / (pa.size + pairs(b).size)
    }

    /**
     * Artist credits split on the usual separators: "A, B & C feat. D", "A / B".
     * A slash needs a space next to it, so "AC/DC" stays one artist.
     */
    fun splitArtists(artist: String): List<String> =
        artist.split(Regex("(?i)\\s*(,|&|\\bx\\b|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|(?<=\\s)/|/(?=\\s)|;)\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * A YouTube upload's artist and title. "Artist - Title (Official Video)"
     * on an artist's own channel splits on the dash; auto-generated "Topic"
     * channels are named after the artist.
     */
    fun artistAndTitle(rawTitle: String, channel: String?): Pair<String, String> {
        val cleanChannel = channel.orEmpty().removeSuffix(" - Topic").removeSuffix("VEVO").trim()
        val title = stripVideoNoise(rawTitle)
        val dash = Regex("\\s[-–—]\\s").find(title)
        if (dash != null && channel?.endsWith(" - Topic") != true) {
            val left = title.substring(0, dash.range.first).trim()
            val right = title.substring(dash.range.last + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) return left to right
        }
        return cleanChannel to title
    }

    /** "3:45" or "1:02:03" → milliseconds. */
    fun parseClock(text: String?): Long? {
        val parts = text?.trim()?.split(':') ?: return null
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000
    }

    /** ISO 8601 durations as used by JSON-LD: "PT3M45S" → milliseconds. */
    fun parseIsoDuration(text: String?): Long? {
        val m = Regex("^P(?:T)?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$").matchEntire(text?.trim() ?: return null) ?: return null
        val (h, min, s) = m.destructured
        if (h.isEmpty() && min.isEmpty() && s.isEmpty()) return null
        return ((h.toLongOrNull() ?: 0) * 3600_000) + ((min.toLongOrNull() ?: 0) * 60_000) + ((s.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }

    /** The year at the start of a date such as "2019-05-17". */
    fun year(date: String?): Int? = date?.let { Regex("^(\\d{4})").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }

    /** Unescape the few HTML entities that turn up in meta tags. */
    fun unescapeHtml(text: String): String = text
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    /** A name safe for a file on any file system. */
    fun fileName(text: String, max: Int = 120): String {
        val cleaned = text.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim().trim('.')
        return cleaned.take(max).ifEmpty { "track" }
    }
}
