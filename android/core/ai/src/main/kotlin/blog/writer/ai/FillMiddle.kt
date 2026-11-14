package blog.writer.ai

data class TextSpan(val start: Int, val endExclusive: Int)

data class FillTarget(
    val opening: TextSpan,
    val ending: TextSpan,
    val insertAt: Int,
)

object FillMiddle {
    const val MARKER = "<!-- fill -->"

    fun target(body: String, opening: String?, ending: String?): FillTarget? {
        val marker = body.indexOf(MARKER)
        if (marker >= 0) {
            val before = paragraphBefore(body, marker)
            val after = paragraphAfter(body, marker + MARKER.length)
            if (before != null && after != null && before.endExclusive <= after.start) {
                return FillTarget(before, after, marker)
            }
        }
        if (!opening.isNullOrBlank() && !ending.isNullOrBlank()) {
            val openAt = body.indexOf(opening)
            val endAt = if (openAt < 0) -1 else body.indexOf(ending, openAt + opening.length)
            if (openAt >= 0 && endAt > openAt) {
                return FillTarget(
                    TextSpan(openAt, openAt + opening.length),
                    TextSpan(endAt, endAt + ending.length),
                    openAt + opening.length,
                )
            }
        }
        return null
    }

    fun paragraphAt(body: String, offset: Int): String {
        val span = paragraphRange(body, offset.coerceIn(0, body.length))
        return body.substring(span.start, span.endExclusive).trim()
    }

    private fun paragraphBefore(body: String, offset: Int): TextSpan? {
        val prior = body.lastIndexOf("\n\n", (offset - 1).coerceAtLeast(0))
        if (prior < 0) {
            val text = body.substring(0, offset).trim()
            return if (text.isEmpty()) null else TextSpan(0, text.length)
        }
        val start = body.lastIndexOf("\n\n", prior - 1).let { if (it < 0) 0 else it + 2 }
        val end = prior
        return if (start >= end) null else TextSpan(start, end)
    }

    private fun paragraphAfter(body: String, offset: Int): TextSpan? {
        val rest = body.substring(offset.coerceAtMost(body.length)).trimStart()
        val absolute = body.length - rest.length
        if (rest.isEmpty()) return null
        val end = rest.indexOf("\n\n").let { if (it < 0) rest.length else it }
        return TextSpan(absolute, absolute + end)
    }

    private fun paragraphRange(body: String, offset: Int): TextSpan {
        val start = body.lastIndexOf("\n\n", (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 2 }
        val end = body.indexOf("\n\n", offset).let { if (it < 0) body.length else it }
        return TextSpan(start, end)
    }
}
