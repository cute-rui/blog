package blog.writer.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ContinueIdea(
    val direction: String,
    val sample: String,
)

class IdeaStreamParser {
    private val buffer = StringBuilder()
    private var emitted = 0

    fun push(delta: String): List<ContinueIdea> {
        buffer.append(delta)
        val objects = extractIdeaObjects(buffer.toString())
        if (objects.size <= emitted) return emptyList()
        val fresh = objects.drop(emitted)
        emitted = objects.size
        return fresh
    }

    fun raw(): String = buffer.toString()
}

fun parseContinueIdeas(raw: String): List<ContinueIdea> {
    val objects = extractIdeaObjects(raw)
    if (objects.isEmpty()) throw IllegalArgumentException("没有 JSON 数组")
    return objects
}

internal fun extractIdeaObjects(raw: String): List<ContinueIdea> {
    val stripped = FENCE.replace(raw, "")
    val start = stripped.indexOf('[')
    val end = stripped.lastIndexOf(']')
    val json = if (start >= 0 && end > start) stripped.substring(start, end + 1) else stripped
    val complete = mutableListOf<String>()
    var index = 0
    var depth = 0
    var objectStart = -1
    var quote = false
    var escaped = false
    while (index < json.length) {
        val ch = json[index]
        if (quote) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                quote = false
            }
            index++
            continue
        }
        when (ch) {
            '"' -> quote = true
            '{' -> {
                if (depth == 0) objectStart = index
                depth++
            }
            '}' -> {
                depth--
                if (depth == 0 && objectStart >= 0) {
                    complete += json.substring(objectStart, index + 1)
                    objectStart = -1
                }
                if (depth < 0) depth = 0
            }
        }
        index++
    }
    return complete.mapNotNull { item ->
        runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<ContinueIdea>(item) }.getOrNull()
    }.filter { it.direction.isNotBlank() && it.sample.isNotBlank() }
}

private val FENCE = Regex("```(?:json)?", RegexOption.IGNORE_CASE)
