package blog.writer.editor
import blog.writer.copy.Copy

object MarkdownEdit {
    fun wrapSelection(text: String, start: Int, end: Int, marker: String): Edit {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val selected = text.substring(safeStart, safeEnd)
        val next = text.substring(0, safeStart) + marker + selected + marker + text.substring(safeEnd)
        val cursor = safeStart + marker.length + selected.length + marker.length
        return Edit(next, cursor)
    }

    fun prefixLine(text: String, cursor: Int, prefix: String): Edit {
        val at = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (it < 0 || at == 0) 0 else it + 1 }
        val next = text.substring(0, lineStart) + prefix + text.substring(lineStart)
        return Edit(next, at + prefix.length)
    }

    fun insertLink(text: String, start: Int, end: Int): Edit {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val label = text.substring(safeStart, safeEnd).ifBlank { Copy["link_text"] }
        val snippet = "[$label](https://)"
        val next = text.substring(0, safeStart) + snippet + text.substring(safeEnd)
        return Edit(next, safeStart + snippet.length)
    }

    fun insertImage(text: String, cursor: Int, relativePath: String): Edit {
        val at = cursor.coerceIn(0, text.length)
        val snippet = "\n\n![]($relativePath)\n"
        val next = text.substring(0, at) + snippet + text.substring(at)
        return Edit(next, at + snippet.length)
    }

    data class Edit(val text: String, val cursor: Int)
}
