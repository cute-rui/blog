package blog.writer.ai

import blog.writer.editor.outline
import blog.writer.editor.sectionAt

data class ContextBudgets(
    val meta: Int = 200,
    val outline: Int = 500,
    val before: Int = 1_500,
    val after: Int = 500,
)

data class AssembledContext(
    val meta: String,
    val outline: String,
    val before: String,
    val after: String,
    val dropped: List<String>,
)

fun assembleContext(
    title: String,
    description: String,
    tags: List<String>,
    body: String,
    cursor: Int,
    budgets: ContextBudgets = ContextBudgets(),
    contextCharLimit: Int = Int.MAX_VALUE,
): AssembledContext {
    val at = cursor.coerceIn(0, body.length)
    val headings = outline(body)
    val current = sectionAt(headings, at)
    val outlineText = buildString {
        if (current != null) append("当前位置：").append(current.text).append('\n')
        headings.forEach { heading ->
            append("#".repeat(heading.level)).append(' ').append(heading.text).append('\n')
        }
    }
    var meta = tailKeepStart(
        buildString {
            append("标题：").append(title).append('\n')
            append("描述：").append(description).append('\n')
            append("标签：").append(tags.joinToString("、"))
        },
        budgets.meta,
    )
    var outline = tailKeepStart(outlineText.trim(), budgets.outline)
    var before = tailWithin(body.substring(0, at), budgets.before)
    var after = headWithin(body.substring(at), budgets.after)
    val dropped = mutableListOf<String>()
    fun total() = meta.length + outline.length + before.length + after.length
    if (total() > contextCharLimit) {
        after = ""
        dropped += "后文"
    }
    if (total() > contextCharLimit) {
        outline = ""
        dropped += "大纲"
    }
    if (total() > contextCharLimit) {
        before = before.takeLast((contextCharLimit - meta.length).coerceAtLeast(0))
        dropped += "前文的远端"
    }
    return AssembledContext(meta, outline, before, after, dropped)
}

private fun tailKeepStart(text: String, budget: Int): String =
    if (text.length <= budget) text else text.take(budget)

private fun tailWithin(text: String, budget: Int): String {
    if (text.length <= budget) return text
    val slice = text.takeLast(budget)
    val breakAt = slice.indexOf("\n\n")
    return if (breakAt >= 0 && breakAt + 2 < slice.length) slice.substring(breakAt + 2) else slice
}

private fun headWithin(text: String, budget: Int): String {
    if (text.length <= budget) return text
    val slice = text.take(budget)
    val breakAt = slice.lastIndexOf("\n\n")
    return if (breakAt > 0) slice.substring(0, breakAt) else slice
}
