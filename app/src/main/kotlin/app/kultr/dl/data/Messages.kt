package app.kultr.dl.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class MessageKind { INFO, SUCCESS, WARNING, ERROR }

data class UiMessage(val text: String, val kind: MessageKind = MessageKind.INFO, val long: Boolean = false)

/** Snackbar messages from anywhere in the app, the playback service included. */
class Messages {
    private val flow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<UiMessage> = flow

    fun show(text: String, kind: MessageKind = MessageKind.INFO, long: Boolean = false) {
        flow.tryEmit(UiMessage(text, kind, long))
    }

    fun error(text: String) = show(text, MessageKind.ERROR, long = true)
}

/** A short, human reason for an exception. */
fun describe(error: Throwable): String {
    // Wrappers such as ExceptionInInitializerError carry the real reason in their cause.
    if (error.message.isNullOrBlank() && error.cause != null && error.cause !== error) return describe(error.cause!!)
    val lines = error.message?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    // yt-dlp reports on stderr: its own ERROR line says it best; a Python traceback ends with the exception.
    val message = lines.lastOrNull { it.startsWith("ERROR:") }
        ?: if (lines.firstOrNull()?.startsWith("Traceback") == true) lines.last() else lines.firstOrNull()
    return when {
        error is java.net.UnknownHostException -> "No connection."
        error is java.net.SocketTimeoutException -> "The server took too long to answer."
        message.isNullOrEmpty() -> error::class.java.simpleName
        message.startsWith("ERROR: ") -> message.removePrefix("ERROR: ")
            // "[youtube] abc123: Sign in to confirm … Use --cookies …" → the part meant for people.
            .replace(Regex("^\\[[^\\]]+] [^:]+: "), "")
            .substringBefore(" Use --")
            .substringBefore(" See  http")
            .trim()
            .take(220)
        else -> message.take(220)
    }
}
