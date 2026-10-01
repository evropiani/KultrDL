package app.kultr.dl.data

import android.content.Context
import androidx.core.content.edit
import app.kultr.dl.core.discover.Keys
import app.kultr.dl.core.taste.ArtistBlocks
import app.kultr.dl.core.taste.Credits
import app.kultr.dl.core.util.LenientJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class BlockedArtist(val name: String, val at: Long = System.currentTimeMillis())

@Serializable
enum class Verdict { LIKE, DISMISS }

/** "More like this" or "Not interested" on a suggestion. */
@Serializable
data class FeedbackEntry(
    val key: String,
    val verdict: Verdict,
    val artist: String,
    val label: String,
    val at: Long = System.currentTimeMillis(),
)

@Serializable
data class TasteData(
    val blocked: List<BlockedArtist> = emptyList(),
    val feedback: List<FeedbackEntry> = emptyList(),
)

/**
 * What the user told KultrDL about their taste: artists they never want
 * to hear, and their answers to suggestions. Kept on the phone, part of
 * backups.
 */
class TasteStore(context: Context) {
    private val prefs = context.getSharedPreferences("kultrdl.taste", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val data: StateFlow<TasteData> = state.asStateFlow()

    private val blockState = MutableStateFlow(ArtistBlocks(state.value.blocked.map { it.name }))

    /** Blocked artists, ready to filter tracks with. */
    val blocks: StateFlow<ArtistBlocks> = blockState.asStateFlow()

    private fun load(): TasteData = prefs.getString(KEY, null)
        ?.let { runCatching { LenientJson.decodeFromString(TasteData.serializer(), it) }.getOrNull() }
        ?: TasteData()

    private fun write(transform: (TasteData) -> TasteData) {
        state.update(transform)
        blockState.value = ArtistBlocks(state.value.blocked.map { it.name })
        prefs.edit { putString(KEY, LenientJson.encodeToString(TasteData.serializer(), state.value)) }
    }

    fun isBlocked(name: String): Boolean = state.value.blocked.any { Credits.key(it.name) == Credits.key(name) }

    fun block(names: Collection<String>) = write { d ->
        val known = d.blocked.map { Credits.key(it.name) }.toSet()
        d.copy(blocked = d.blocked + names.filter { Credits.key(it).isNotEmpty() && Credits.key(it) !in known }.distinctBy(Credits::key).map { BlockedArtist(it.trim()) })
    }

    fun unblock(name: String) = write { d -> d.copy(blocked = d.blocked.filterNot { Credits.key(it.name) == Credits.key(name) }) }

    fun like(key: String, artist: String, label: String) = answer(FeedbackEntry(key, Verdict.LIKE, artist, label))

    fun dismiss(key: String, artist: String, label: String) = answer(FeedbackEntry(key, Verdict.DISMISS, artist, label))

    private fun answer(entry: FeedbackEntry) = write { d ->
        // The newest answer per item, and no more than a few thousand in all.
        d.copy(feedback = (d.feedback.filterNot { it.key == entry.key } + entry).takeLast(MAX_FEEDBACK))
    }

    fun forget(key: String) = write { d -> d.copy(feedback = d.feedback.filterNot { it.key == key }) }

    fun dismissedKeys(): Set<String> = state.value.feedback.filter { it.verdict == Verdict.DISMISS }.map { it.key }.toSet()

    fun verdict(key: String): Verdict? = state.value.feedback.lastOrNull { it.key == key }?.verdict

    /** From a backup: added to what is here. */
    fun restore(from: TasteData) {
        block(from.blocked.map { it.name })
        write { d -> d.copy(feedback = (d.feedback + from.feedback.filter { f -> d.feedback.none { it.key == f.key } }).takeLast(MAX_FEEDBACK)) }
    }

    companion object {
        private const val KEY = "taste"
        private const val MAX_FEEDBACK = 3000

        fun artistKey(name: String) = Keys.artist(name)
    }
}
