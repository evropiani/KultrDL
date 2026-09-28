package app.kultr.dl.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Forgiving access to JSON from services that change shape without notice:
 * every step may be missing, and a missing step gives null rather than an
 * exception.
 */
val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

fun parseJson(text: String): JsonElement = LenientJson.parseToJsonElement(text)

fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun JsonElement?.at(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** Follow a path of keys (String) and indexes (Int). */
fun JsonElement?.path(vararg steps: Any): JsonElement? {
    var current = this
    for (step in steps) {
        current = when (step) {
            is String -> current.at(step)
            is Int -> current.at(step)
            else -> null
        } ?: return null
    }
    return current
}

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

val JsonElement?.arr: JsonArray? get() = this as? JsonArray

val JsonElement?.list: List<JsonElement> get() = (this as? JsonArray).orEmpty()

val JsonElement?.str: String?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content.takeIf { it.isNotEmpty() }
    }

val JsonElement?.long: Long?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
    }

val JsonElement?.int: Int? get() = long?.toInt()

val JsonElement?.double: Double?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.content.toDoubleOrNull()
    }

val JsonElement?.bool: Boolean?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.booleanOrNull
    }

/** Every element in the tree, depth first, this one included. */
fun JsonElement.walk(): Sequence<JsonElement> = sequence {
    val stack = ArrayDeque<JsonElement>()
    stack.addLast(this@walk)
    while (stack.isNotEmpty()) {
        val next = stack.removeLast()
        yield(next)
        when (next) {
            is JsonObject -> next.values.reversed().forEach { stack.addLast(it) }
            is JsonArray -> next.reversed().forEach { stack.addLast(it) }
            else -> Unit
        }
    }
}

/** Objects stored under [key] anywhere in the tree, in document order. */
fun JsonElement.objectsUnder(key: String): Sequence<JsonObject> =
    walk().mapNotNull { (it as? JsonObject)?.get(key) as? JsonObject }

/** The first string stored under [key] anywhere in the tree. */
fun JsonElement.firstString(key: String): String? =
    walk().firstNotNullOfOrNull { (it as? JsonObject)?.get(key).str }
