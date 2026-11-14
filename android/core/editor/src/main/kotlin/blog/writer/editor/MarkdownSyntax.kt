package blog.writer.editor

enum class MarkdownKind {
    HeadingMark,
    Heading,
    Bold,
    Italic,
    Code,
    LinkText,
    LinkMark,
}

data class MarkdownSpan(
    val start: Int,
    val end: Int,
    val kind: MarkdownKind,
)

object MarkdownSyntax {
    fun spans(text: String): List<MarkdownSpan> {
        if (text.isEmpty()) return emptyList()
        val found = mutableListOf<MarkdownSpan>()
        var inFence = false
        var offset = 0
        for (line in text.split('\n')) {
            val lineEnd = offset + line.length
            if (line.trimStart().startsWith("```")) {
                addSplit(found, offset, lineEnd, MarkdownKind.Code, text)
                inFence = !inFence
            } else if (inFence) {
                addSplit(found, offset, lineEnd, MarkdownKind.Code, text)
            } else {
                heading(line, offset, found, text)
                inline(line, offset, found, text)
            }
            offset = lineEnd + 1
        }
        return found.filter { it.end > it.start }
    }

    private fun heading(line: String, offset: Int, found: MutableList<MarkdownSpan>, text: String) {
        val match = HEADING.matchEntire(line) ?: return
        val marks = match.groupValues[1]
        val content = match.groupValues[2]
        addSplit(found, offset, offset + marks.length, MarkdownKind.HeadingMark, text)
        val contentStart = offset + line.indexOf(content)
        addSplit(found, contentStart, contentStart + content.length, MarkdownKind.Heading, text)
    }

    private fun inline(line: String, offset: Int, found: MutableList<MarkdownSpan>, text: String) {
        val occupied = mutableListOf<IntRange>()
        fun free(start: Int, end: Int) = occupied.none { it.first < end && start < it.last }
        fun take(start: Int, end: Int, kind: MarkdownKind) {
            if (end <= start || !free(start, end)) return
            addSplit(found, offset + start, offset + end, kind, text)
            occupied += start until end
        }
        for (match in CODE.findAll(line)) {
            take(match.range.first, match.range.last + 1, MarkdownKind.Code)
        }
        for (match in BOLD.findAll(line)) {
            take(match.range.first, match.range.last + 1, MarkdownKind.Bold)
        }
        for (match in ITALIC.findAll(line)) {
            take(match.range.first, match.range.last + 1, MarkdownKind.Italic)
        }
        for (match in LINK.findAll(line)) {
            val wholeStart = match.range.first
            val label = match.groupValues[1]
            val labelStart = wholeStart + 1
            take(wholeStart, labelStart, MarkdownKind.LinkMark)
            take(labelStart, labelStart + label.length, MarkdownKind.LinkText)
            take(labelStart + label.length, match.range.last + 1, MarkdownKind.LinkMark)
        }
    }

    private fun addSplit(
        found: MutableList<MarkdownSpan>,
        start: Int,
        end: Int,
        kind: MarkdownKind,
        text: String,
    ) {
        var cursor = start
        while (cursor < end) {
            val newline = text.indexOf('\n', cursor).let { if (it < 0 || it >= end) end else it }
            if (newline > cursor) found += MarkdownSpan(cursor, newline, kind)
            cursor = newline + 1
        }
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val CODE = Regex("`[^`\\n]+`")
    private val BOLD = Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__")
    private val ITALIC = Regex("(?<!\\*)\\*[^*\\n]+\\*(?!\\*)|(?<!_)_[^_\\n]+_(?!_)")
    private val LINK = Regex("\\[([^\\]\\n]+)]\\([^)\\n]+\\)")
}
