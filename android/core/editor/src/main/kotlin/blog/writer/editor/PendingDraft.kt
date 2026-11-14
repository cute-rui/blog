package blog.writer.editor

data class PendingSpan(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "草稿起点不能为负" }
        require(endExclusive >= start) { "草稿范围颠倒了" }
    }

    val length: Int get() = endExclusive - start
}

object PendingDrafts {
    fun acceptedText(display: String, span: PendingSpan?): String {
        if (span == null) return display
        if (span.endExclusive > display.length || span.start > display.length) return display
        return display.removeRange(span.start, span.endExclusive)
    }

    fun insert(accepted: String, offset: Int, draft: String): Pair<String, PendingSpan> {
        val at = offset.coerceIn(0, accepted.length)
        val text = accepted.substring(0, at) + draft + accepted.substring(at)
        return text to PendingSpan(at, at + draft.length)
    }

    fun prepareInsertion(accepted: String, offset: Int, sample: String): String {
        val at = offset.coerceIn(0, accepted.length)
        val body = sample.trim()
        val lead = at > 0 && accepted[at - 1] != '\n'
        val trail = at < accepted.length && accepted[at] != '\n'
        return buildString {
            if (lead) append("\n\n")
            append(body)
            if (trail) append("\n\n") else append('\n')
        }
    }

    fun reject(display: String, span: PendingSpan): String = acceptedText(display, span)

    fun toAcceptedOffset(displayOffset: Int, span: PendingSpan?): Int {
        if (span == null) return displayOffset
        return when {
            displayOffset <= span.start -> displayOffset
            displayOffset >= span.endExclusive -> displayOffset - span.length
            else -> span.start
        }
    }

    fun adjust(oldText: String, newText: String, span: PendingSpan): PendingSpan? {
        if (span.endExclusive > oldText.length) return null
        val maxPrefix = minOf(oldText.length, newText.length)
        var prefix = 0
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (
            suffix < oldText.length - prefix &&
            suffix < newText.length - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }
        val editStart = prefix
        val editEnd = oldText.length - suffix
        val newEditEnd = newText.length - suffix
        val delta = (newEditEnd - editStart) - (editEnd - editStart)
        val shifted = when {
            editEnd <= span.start -> PendingSpan(span.start + delta, span.endExclusive + delta)
            editStart == span.endExclusive && editEnd == editStart ->
                PendingSpan(span.start, span.endExclusive + delta)
            editStart >= span.endExclusive -> span
            editStart <= span.start && editEnd >= span.endExclusive -> return null
            editStart >= span.start && editEnd <= span.endExclusive ->
                PendingSpan(span.start, span.endExclusive + delta)
            else -> return null
        }
        if (shifted.start < 0 || shifted.endExclusive > newText.length || shifted.length == 0) return null
        return shifted
    }
}
