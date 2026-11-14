package blog.writer.sync

data class MergePiece(
    val local: List<String>,
    val remote: List<String>,
    val conflict: Boolean,
)

data class MergeResult(
    val pieces: List<MergePiece>,
) {
    val clean: Boolean get() = pieces.none { it.conflict }

    fun render(chooseLocal: (conflictIndex: Int) -> Boolean = { true }): String {
        var conflictIndex = 0
        val paragraphs = mutableListOf<String>()
        for (piece in pieces) {
            val useLocal = if (piece.conflict) {
                val choice = chooseLocal(conflictIndex)
                conflictIndex++
                choice
            } else {
                true
            }
            paragraphs += if (useLocal) piece.local else piece.remote
        }
        return paragraphs.joinToString("\n\n")
    }
}

fun mergeParagraphs(base: String, local: String, remote: String): MergeResult {
    val baseParts = paragraphs(base)
    val localParts = paragraphs(local)
    val remoteParts = paragraphs(remote)
    if (localParts == remoteParts) return kept(localParts)
    if (localParts == baseParts) return kept(remoteParts)
    if (remoteParts == baseParts) return kept(localParts)
    val localChanges = changes(baseParts, localParts)
    val remoteChanges = changes(baseParts, remoteParts)
    return MergeResult(mergeChanges(baseParts, localChanges, remoteChanges))
}

internal fun paragraphs(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return text.replace("\r\n", "\n").split("\n\n")
}

private fun kept(parts: List<String>): MergeResult =
    MergeResult(if (parts.isEmpty()) emptyList() else listOf(MergePiece(parts, parts, conflict = false)))

private data class Change(
    val baseStart: Int,
    val baseEnd: Int,
    val replacement: List<String>,
)

private sealed interface DiffOp {
    data class Equal(val text: String) : DiffOp
    data class Delete(val text: String) : DiffOp
    data class Insert(val text: String) : DiffOp
}

private fun changes(base: List<String>, edited: List<String>): List<Change> {
    val ops = diff(base, edited)
    val out = mutableListOf<Change>()
    var baseIndex = 0
    var index = 0
    while (index < ops.size) {
        if (ops[index] is DiffOp.Equal) {
            baseIndex++
            index++
            continue
        }
        val start = baseIndex
        val replacement = mutableListOf<String>()
        while (index < ops.size && ops[index] !is DiffOp.Equal) {
            when (val op = ops[index]) {
                is DiffOp.Delete -> baseIndex++
                is DiffOp.Insert -> replacement += op.text
                is DiffOp.Equal -> Unit
            }
            index++
        }
        out += Change(start, baseIndex, replacement)
    }
    return out
}

private fun diff(base: List<String>, edited: List<String>): List<DiffOp> {
    val rows = base.size
    val columns = edited.size
    val scores = Array(rows + 1) { IntArray(columns + 1) }
    for (i in rows - 1 downTo 0) {
        for (j in columns - 1 downTo 0) {
            scores[i][j] = if (base[i] == edited[j]) {
                scores[i + 1][j + 1] + 1
            } else {
                maxOf(scores[i + 1][j], scores[i][j + 1])
            }
        }
    }
    val ops = mutableListOf<DiffOp>()
    var i = 0
    var j = 0
    while (i < rows && j < columns) {
        when {
            base[i] == edited[j] -> {
                ops += DiffOp.Equal(base[i])
                i++
                j++
            }
            scores[i + 1][j] >= scores[i][j + 1] -> {
                ops += DiffOp.Delete(base[i])
                i++
            }
            else -> {
                ops += DiffOp.Insert(edited[j])
                j++
            }
        }
    }
    while (i < rows) {
        ops += DiffOp.Delete(base[i])
        i++
    }
    while (j < columns) {
        ops += DiffOp.Insert(edited[j])
        j++
    }
    return ops
}

private fun mergeChanges(
    base: List<String>,
    local: List<Change>,
    remote: List<Change>,
): List<MergePiece> {
    val pieces = mutableListOf<MergePiece>()
    var cursor = 0
    var localIndex = 0
    var remoteIndex = 0
    while (cursor < base.size || localIndex < local.size || remoteIndex < remote.size) {
        val nextLocal = local.getOrNull(localIndex)?.baseStart
        val nextRemote = remote.getOrNull(remoteIndex)?.baseStart
        val next = listOfNotNull(nextLocal, nextRemote).minOrNull()
        if (next == null) {
            if (cursor < base.size) pieces += same(base.subList(cursor, base.size))
            break
        }
        if (next > cursor) {
            pieces += same(base.subList(cursor, next))
            cursor = next
        }
        var end = cursor
        val localTaken = mutableListOf<Change>()
        val remoteTaken = mutableListOf<Change>()
        var moved = true
        while (moved) {
            moved = false
            while (localIndex + localTaken.size < local.size && local[localIndex + localTaken.size].baseStart <= end) {
                val change = local[localIndex + localTaken.size]
                localTaken += change
                end = maxOf(end, change.baseEnd)
                moved = true
            }
            while (remoteIndex + remoteTaken.size < remote.size && remote[remoteIndex + remoteTaken.size].baseStart <= end) {
                val change = remote[remoteIndex + remoteTaken.size]
                remoteTaken += change
                end = maxOf(end, change.baseEnd)
                moved = true
            }
        }
        val localText = replacementFor(base, cursor, end, localTaken)
        val remoteText = replacementFor(base, cursor, end, remoteTaken)
        pieces += when {
            localTaken.isEmpty() -> MergePiece(remoteText, remoteText, conflict = false)
            remoteTaken.isEmpty() -> MergePiece(localText, localText, conflict = false)
            localText == remoteText -> MergePiece(localText, remoteText, conflict = false)
            else -> MergePiece(localText, remoteText, conflict = true)
        }
        localIndex += localTaken.size
        remoteIndex += remoteTaken.size
        cursor = end
    }
    return pieces.filter { it.local.isNotEmpty() || it.remote.isNotEmpty() || it.conflict }
}

private fun replacementFor(base: List<String>, start: Int, end: Int, changes: List<Change>): List<String> {
    if (changes.isEmpty()) return base.subList(start, end)
    val out = mutableListOf<String>()
    var cursor = start
    for (change in changes) {
        if (change.baseStart > cursor) out += base.subList(cursor, change.baseStart)
        out += change.replacement
        cursor = change.baseEnd
    }
    if (cursor < end) out += base.subList(cursor, end)
    return out
}

private fun same(parts: List<String>): MergePiece = MergePiece(parts, parts, conflict = false)
