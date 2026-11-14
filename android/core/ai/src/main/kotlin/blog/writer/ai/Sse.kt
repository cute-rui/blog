package blog.writer.ai

data class SseEvent(
    val type: String,
    val data: String,
)

class SseDecoder {
    private val data = StringBuilder()
    private val event = StringBuilder()

    fun pushLine(line: String): SseEvent? {
        val trimmed = line.removeSuffix("\r")
        if (trimmed.isEmpty()) {
            if (data.isEmpty() && event.isEmpty()) return null
            val decoded = SseEvent(event.toString(), data.toString())
            data.clear()
            event.clear()
            return decoded
        }
        when {
            trimmed.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(trimmed.removePrefix("data:").removePrefix(" "))
            }
            trimmed.startsWith("event:") -> {
                event.clear()
                event.append(trimmed.removePrefix("event:").trim())
            }
        }
        return null
    }
}
