package blog.writer.content

data class BodyOverride(
    val find: String,
    val replace: String,
)

data class WechatBodyApplied(
    val text: String,
    val stale: List<BodyOverride>,
)

fun applyWechatBody(shared: String, overrides: List<BodyOverride>): WechatBodyApplied {
    if (overrides.isEmpty()) return WechatBodyApplied(shared, emptyList())
    val only = overrides.singleOrNull()
    if (only != null && only.find.isEmpty()) return WechatBodyApplied(only.replace, emptyList())
    val hits = mutableListOf<BodyHit>()
    val stale = mutableListOf<BodyOverride>()
    var cursor = 0
    for (override in overrides) {
        if (override.find.isEmpty()) {
            stale += override
            continue
        }
        val at = shared.indexOf(override.find, cursor)
        if (at < 0) {
            stale += override
            continue
        }
        val end = at + override.find.length
        if (hits.isNotEmpty() && at < hits.last().end) {
            stale += override
            continue
        }
        hits += BodyHit(at, end, override.replace)
        cursor = end
    }
    val out = StringBuilder()
    var position = 0
    for (hit in hits) {
        out.append(shared, position, hit.start)
        out.append(hit.replace)
        position = hit.end
    }
    out.append(shared, position, shared.length)
    return WechatBodyApplied(out.toString(), stale)
}

fun diffWechatBody(shared: String, edited: String): List<BodyOverride> {
    if (shared == edited) return emptyList()
    if (shared.isEmpty()) return listOf(BodyOverride("", edited))
    val raw = rawChanges(shared, edited)
    if (raw.isEmpty()) return listOf(BodyOverride(shared, edited))
    val expanded = raw.map { expand(shared, it) }
    return mergeExpanded(shared, raw, expanded).map { change ->
        BodyOverride(shared.substring(change.start, change.end), change.replace)
    }
}

fun PostDocument.wechatBody(): String = applyWechatBody(body, frontMatter.wechat.body).text

private data class BodyHit(val start: Int, val end: Int, val replace: String)

private data class Change(val start: Int, val end: Int, val replace: String)

private sealed interface LineEdit {
    data class Keep(val line: String) : LineEdit
    data class Remove(val line: String) : LineEdit
    data class Add(val line: String) : LineEdit
}

private fun rawChanges(shared: String, edited: String): List<Change> {
    val edits = lineDiff(splitLines(shared), splitLines(edited))
    val changes = mutableListOf<Change>()
    var offset = 0
    var index = 0
    while (index < edits.size) {
        when (val edit = edits[index]) {
            is LineEdit.Keep -> {
                offset += edit.line.length
                index++
            }
            is LineEdit.Remove, is LineEdit.Add -> {
                val start = offset
                val replace = StringBuilder()
                while (index < edits.size) {
                    when (val step = edits[index]) {
                        is LineEdit.Keep -> break
                        is LineEdit.Remove -> {
                            offset += step.line.length
                            index++
                        }
                        is LineEdit.Add -> {
                            replace.append(step.line)
                            index++
                        }
                    }
                }
                changes += Change(start, offset, replace.toString())
            }
        }
    }
    return changes
}

private fun lineDiff(source: List<String>, target: List<String>): List<LineEdit> {
    val rows = source.size
    val columns = target.size
    val scores = Array(rows + 1) { IntArray(columns + 1) }
    for (row in rows - 1 downTo 0) {
        for (column in columns - 1 downTo 0) {
            scores[row][column] = if (source[row] == target[column]) {
                scores[row + 1][column + 1] + 1
            } else {
                maxOf(scores[row + 1][column], scores[row][column + 1])
            }
        }
    }
    val edits = mutableListOf<LineEdit>()
    var row = 0
    var column = 0
    while (row < rows && column < columns) {
        when {
            source[row] == target[column] -> {
                edits += LineEdit.Keep(source[row])
                row++
                column++
            }
            scores[row + 1][column] >= scores[row][column + 1] -> {
                edits += LineEdit.Remove(source[row])
                row++
            }
            else -> {
                edits += LineEdit.Add(target[column])
                column++
            }
        }
    }
    while (row < rows) {
        edits += LineEdit.Remove(source[row])
        row++
    }
    while (column < columns) {
        edits += LineEdit.Add(target[column])
        column++
    }
    return edits
}

private fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) {
            lines += text.substring(start)
            break
        }
        lines += text.substring(start, newline + 1)
        start = newline + 1
    }
    return lines
}

private fun expand(shared: String, change: Change): Change {
    var start = change.start
    var end = change.end
    var replace = change.replace
    var guard = shared.length + 2
    while (guard-- > 0) {
        val slice = shared.substring(start, end)
        if (slice.isNotEmpty() && uniqueAt(shared, slice, start)) return Change(start, end, replace)
        val back = if (start > 0) previousLineStart(shared, start) else start
        val forward = if (end < shared.length) nextLineEnd(shared, end) else end
        if (back == start && forward == end) break
        if (back < start) {
            replace = shared.substring(back, start) + replace
            start = back
        } else {
            replace += shared.substring(end, forward)
            end = forward
        }
    }
    val whole = shared.substring(0, change.start) + change.replace + shared.substring(change.end)
    return Change(0, shared.length, whole)
}

private fun uniqueAt(text: String, slice: String, start: Int): Boolean {
    if (text.indexOf(slice) != start) return false
    return text.indexOf(slice, start + 1) < 0
}

private fun previousLineStart(text: String, start: Int): Int {
    if (start <= 0) return 0
    val before = start - 1
    val probe = if (text[before] == '\n') before - 1 else before
    if (probe < 0) return 0
    val found = text.lastIndexOf('\n', probe)
    return if (found < 0) 0 else found + 1
}

private fun nextLineEnd(text: String, end: Int): Int {
    if (end >= text.length) return text.length
    val found = text.indexOf('\n', end)
    return if (found < 0) text.length else found + 1
}

private fun mergeExpanded(shared: String, raw: List<Change>, expanded: List<Change>): List<Change> {
    val groups = mutableListOf<MutableList<Int>>()
    for (index in expanded.indices) {
        val previous = groups.lastOrNull()?.lastOrNull()
        if (previous == null || expanded[index].start > expanded[previous].end) {
            groups += mutableListOf(index)
        } else {
            groups.last() += index
        }
    }
    return groups.map { indexes ->
        if (indexes.size == 1) expanded[indexes[0]] else expand(shared, combine(shared, indexes.map { raw[it] }))
    }
}

private fun combine(shared: String, parts: List<Change>): Change {
    val start = parts.first().start
    val end = parts.last().end
    val replace = StringBuilder()
    var cursor = start
    for (part in parts) {
        if (part.start > cursor) replace.append(shared, cursor, part.start)
        replace.append(part.replace)
        cursor = part.end
    }
    if (cursor < end) replace.append(shared, cursor, end)
    return Change(start, end, replace.toString())
}
