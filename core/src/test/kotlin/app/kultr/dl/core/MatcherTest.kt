package app.kultr.dl.core

import app.kultr.dl.core.match.Matcher
import app.kultr.dl.core.model.Source
import app.kultr.dl.core.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatcherTest {
    private fun t(id: String, title: String, artist: String, seconds: Long?, source: Source = Source.YOUTUBE_MUSIC) =
        Track(id = id, source = source, title = title, artist = artist, durationMs = seconds?.times(1000), streamUrl = "https://x/$id")

    private val target = Track(id = "spotify:1", source = Source.SPOTIFY, title = "Paper Boats (feat. Guest)", artist = "Some Band, Guest", durationMs = 214_000)

    @Test
    fun prefersTheStudioRecording() {
        val candidates = listOf(
            t("live", "Paper Boats (Live at Somewhere)", "Some Band", 260),
            t("remix", "Paper Boats (Club Remix)", "Some Band", 330),
            t("studio", "Paper Boats", "Some Band", 215),
            t("cover", "Paper Boats (Cover)", "Other Person", 214, Source.YOUTUBE),
        )
        assertEquals("studio", Matcher.best(target, candidates)?.id)
    }

    @Test
    fun durationBreaksTies() {
        val candidates = listOf(t("long", "Paper Boats", "Some Band", 400), t("right", "Paper Boats", "Some Band", 213))
        assertEquals("right", Matcher.best(target, candidates)?.id)
    }

    @Test
    fun rejectsUnrelated() {
        assertNull(Matcher.best(target, listOf(t("x", "Glass Houses", "Other Person", 180))))
    }

    @Test
    fun asksForTheVersionWhenTheTargetIsLive() {
        val live = target.copy(title = "Paper Boats - Live", durationMs = 260_000)
        val candidates = listOf(t("studio", "Paper Boats", "Some Band", 215), t("live", "Paper Boats (Live)", "Some Band", 261))
        assertEquals("live", Matcher.best(live, candidates)?.id)
    }
}
