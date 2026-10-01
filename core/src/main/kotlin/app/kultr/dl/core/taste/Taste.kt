package app.kultr.dl.core.taste

import app.kultr.dl.core.util.Text
import kotlin.math.pow

/**
 * One piece of evidence about what the user likes: a play, a skip (a
 * negative [weight]), a heart, a file they own, a Navidrome star…
 * [at] dates it; undated evidence (files on the phone) doesn't fade.
 */
data class Signal(
    val artist: String,
    val title: String = "",
    val weight: Double,
    val at: Long? = null,
    val genre: String? = null,
)

/**
 * [solo]: credited on their own somewhere, not only as part of a shared
 * credit ("Sons" in "Mumford & Sons") or as a feature.
 */
data class ArtistScore(val key: String, val name: String, val score: Double, val genres: List<String>, val solo: Boolean = true)

/** Artists (and genres) by how much the user likes them now. */
class TasteProfile(val artists: List<ArtistScore>, val genres: Map<String, Double>) {
    private val byKey = artists.associateBy { it.key }

    val isEmpty: Boolean get() = artists.none { it.score > 0 }

    fun score(name: String): Double = byKey[Credits.key(name)]?.score ?: 0.0

    /** Known well enough that suggesting them would be nothing new. */
    fun knows(name: String): Boolean = score(name) >= KNOWN

    fun get(name: String): ArtistScore? = byKey[Credits.key(name)]

    fun top(n: Int): List<ArtistScore> = artists.filter { it.score > 0 }.take(n)

    /** The artists to build suggestions from: liked, and credited on their own somewhere. */
    fun seeds(n: Int): List<ArtistScore> = artists.filter { it.score > 0 && it.solo }.take(n)

    companion object {
        const val KNOWN = 1.5
        val EMPTY = TasteProfile(emptyList(), emptyMap())
    }
}

object Taste {
    /** Evidence loses half its weight in this many days, so taste can move on. */
    const val HALF_LIFE_DAYS = 90.0

    fun decay(at: Long?, now: Long, halfLifeDays: Double = HALF_LIFE_DAYS): Double {
        if (at == null) return 1.0
        val days = (now - at).coerceAtLeast(0) / 86_400_000.0
        return 0.5.pow(days / halfLifeDays)
    }

    /**
     * Adds the evidence up per artist. The artist field counts fully (a
     * credit like "Simon & Garfunkel" stays whole); each name in a shared
     * credit and each featured artist counts half.
     */
    fun build(signals: List<Signal>, now: Long): TasteProfile {
        val scores = HashMap<String, Double>()
        val names = HashMap<String, MutableMap<String, Double>>()
        val genres = HashMap<String, MutableMap<String, Double>>()
        val allGenres = HashMap<String, Double>()
        val solo = HashSet<String>()

        fun add(name: String, w: Double, genre: String?) {
            val key = Credits.key(name)
            if (key.isEmpty() || key == "unknown artist" || key == "various artists") return
            scores[key] = (scores[key] ?: 0.0) + w
            names.getOrPut(key) { HashMap() }.merge(name.trim(), kotlin.math.abs(w), Double::plus)
            if (genre != null && w > 0) genres.getOrPut(key) { HashMap() }.merge(genre, w, Double::plus)
        }

        for (s in signals) {
            val w = s.weight * decay(s.at, now)
            if (w == 0.0) continue
            val genre = s.genre?.trim()?.takeIf { it.isNotEmpty() }?.let(::genreName)
            val whole = s.artist.trim()
            add(whole, w, genre)
            solo += Credits.key(whole)
            val parts = Text.splitArtists(whole)
            if (parts.size > 1) parts.forEach { add(it, w * 0.5, genre) }
            Credits.featured(s.title).forEach { add(it, w * 0.5, null) }
            if (genre != null && w > 0) allGenres.merge(genre, w, Double::plus)
        }

        val artists = scores.map { (key, score) ->
            ArtistScore(
                key = key,
                name = names[key]?.maxByOrNull { it.value }?.key ?: key,
                score = score,
                genres = genres[key].orEmpty().entries.sortedByDescending { it.value }.map { it.key }.take(3),
                solo = key in solo,
            )
        }.sortedByDescending { it.score }
        return TasteProfile(artists, allGenres)
    }

    /** "hip-hop/rap", "Hip Hop" and "HIP-HOP" are one genre. */
    fun genreName(raw: String): String = raw.trim().lowercase()
        .replace(Regex("\\s*/\\s*"), "/")
        .replace(Regex("[\\s_-]+"), " ")
        .replace(Regex("\\b[a-z]")) { it.value.uppercase() }
        .replace("Hip Hop", "Hip-Hop")
}
