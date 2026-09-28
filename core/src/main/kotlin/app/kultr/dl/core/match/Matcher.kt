package app.kultr.dl.core.match

import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import app.kultr.dl.core.util.Text
import kotlin.math.abs

/**
 * Picks the recording on YouTube Music (or YouTube) that is the catalogue
 * track the user chose: same title, same artist, about the same length,
 * and not a live take, remix or cover unless that is what was asked for.
 */
object Matcher {
    /** Versions that make a different recording of the same song. */
    private val VERSION_WORDS = listOf(
        "live", "remix", "cover", "karaoke", "instrumental", "acoustic", "sped up", "slowed", "reverb",
        "nightcore", "8d", "extended", "demo", "mashup", "bass boosted", "a cappella", "acapella", "reprise",
        "piano version", "orchestral", "tribute", "lyrics video", "concert",
    )

    const val ACCEPT = 55.0

    fun query(track: Track): String {
        val artist = Text.splitArtists(track.artist).firstOrNull() ?: track.artist
        return "$artist ${track.title}".trim()
    }

    fun best(target: Track, candidates: List<Track>): Track? =
        candidates.map { it to score(target, it) }
            .filter { it.second >= ACCEPT }
            .maxByOrNull { it.second }
            ?.first

    fun score(target: Track, candidate: Track): Double {
        var score = 0.0
        val targetTitle = Text.coreTitle(target.title)
        val candidateTitle = Text.coreTitle(candidate.title)
        score += 45 * Text.similarity(targetTitle, candidateTitle)
        if (targetTitle.isNotEmpty() && candidateTitle.contains(targetTitle)) score += 5

        // Artist: any of the credited artists on either side.
        val targetArtists = Text.splitArtists(target.artist).map(Text::normalize).filter { it.isNotEmpty() }
        val candidateArtistText = Text.normalize(candidate.artist)
        val candidateTitleText = Text.normalize(candidate.title)
        val artistHit = targetArtists.any { a -> candidateArtistText.contains(a) || a.contains(candidateArtistText) && candidateArtistText.isNotEmpty() }
        score += when {
            artistHit -> 30.0
            targetArtists.any { candidateTitleText.contains(it) } -> 20.0
            else -> 30 * (targetArtists.maxOfOrNull { Text.similarity(it, candidateArtistText) } ?: 0.0) - 5
        }

        val a = target.durationMs
        val b = candidate.durationMs
        if (a != null && b != null && a > 0 && b > 0) {
            val diff = abs(a - b) / 1000
            score += when {
                diff <= 2 -> 20.0
                diff <= 5 -> 14.0
                diff <= 10 -> 6.0
                diff <= 20 -> 0.0
                diff <= 60 -> -15.0
                else -> -40.0
            }
        }

        val targetText = " " + Text.normalize("${target.title} ${target.album.orEmpty()}") + " "
        val candidateText = " " + Text.normalize(candidate.title) + " "
        for (word in VERSION_WORDS) {
            val w = " ${Text.normalize(word)} "
            val inTarget = targetText.contains(w)
            val inCandidate = candidateText.contains(w)
            if (inCandidate && !inTarget) score -= 30
            if (inTarget && !inCandidate) score -= 10
        }
        if (candidate.source == Source.YOUTUBE_MUSIC) score += 4
        return score
    }
}
