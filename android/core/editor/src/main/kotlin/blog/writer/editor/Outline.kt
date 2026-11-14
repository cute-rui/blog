package blog.writer.editor

data class Heading(
    val level: Int,
    val text: String,
    val startOffset: Int,
)

fun outline(markdown: String): List<Heading> {
    val headings = mutableListOf<Heading>()
    var offset = 0
    var inFence = false
    for (line in markdown.split('\n')) {
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
        } else if (!inFence) {
            val match = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
            if (match != null) {
                headings += Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim(),
                    startOffset = offset,
                )
            }
        }
        offset += line.length + 1
    }
    return headings
}

fun sectionAt(headings: List<Heading>, offset: Int): Heading? =
    headings.lastOrNull { it.startOffset <= offset }

fun paragraphIndex(markdown: String, offset: Int): Int {
    val clamped = offset.coerceIn(0, markdown.length)
    if (markdown.isEmpty()) return 1
    return markdown.substring(0, clamped).split("\n\n").size
}
