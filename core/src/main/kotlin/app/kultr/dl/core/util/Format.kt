package app.kultr.dl.core.util

import java.util.Locale

object Format {
    fun duration(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** "1 track", "12 tracks". */
    fun count(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", n / (1L shl 10).toDouble())
        else -> "$n B"
    }

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Up late"
    }

    /** "Today", "Yesterday", "3 days ago", "2 weeks ago" or "12 Mar 2026" for a "2026-03-12" release date. */
    fun released(date: String?, today: java.time.LocalDate = java.time.LocalDate.now()): String? {
        val d = date?.take(10)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        return when {
            days < 0 -> "Out ${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}"
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            else -> "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"
        }
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    fun initials(text: String): String =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "♪" }
}
