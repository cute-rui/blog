package blog.writer.content

import blog.writer.copy.Copy

sealed interface YamlValue {
    data class Text(val value: String) : YamlValue
    data class Bool(val value: Boolean) : YamlValue
    data class Number(val value: String) : YamlValue
    data class ListValue(val items: List<YamlValue>) : YamlValue
    data class MapValue(val entries: Map<String, YamlValue>) : YamlValue
    data object Empty : YamlValue
}

fun YamlValue.asString(): String? = when (this) {
    is YamlValue.Text -> value
    is YamlValue.Number -> value
    is YamlValue.Bool -> if (value) "true" else "false"
    YamlValue.Empty -> null
    is YamlValue.ListValue, is YamlValue.MapValue -> null
}

fun YamlValue.asBoolean(): Boolean? = when (this) {
    is YamlValue.Bool -> value
    is YamlValue.Text -> when (value) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}

fun YamlValue.asStringList(): List<String> = when (this) {
    is YamlValue.ListValue -> items.mapNotNull { it.asString() }
    is YamlValue.Text -> if (value.isBlank()) emptyList() else listOf(value)
    else -> emptyList()
}

fun YamlValue.asMap(): Map<String, YamlValue> = when (this) {
    is YamlValue.MapValue -> entries
    else -> emptyMap()
}

internal fun parseYamlMap(source: String): Map<String, YamlValue> {
    val lines = source.replace("\r\n", "\n").replace("\t", "  ").lines()
    val parser = YamlParser(lines)
    return parser.parseMap(0)
}

internal fun writeYamlMap(entries: Map<String, YamlValue>): String {
    val out = StringBuilder()
    writeMap(out, entries, 0)
    return out.toString()
}

private class YamlParser(private val lines: List<String>) {
    private var index = 0

    fun parseMap(indent: Int): Map<String, YamlValue> {
        val map = linkedMapOf<String, YamlValue>()
        while (index < lines.size) {
            val raw = lines[index]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) {
                index++
                continue
            }
            val current = indentOf(raw)
            if (current < indent) break
            if (current > indent) {
                throw FrontMatterException(Copy.format("yaml_indent", "line" to raw.trim()))
            }
            val trimmed = raw.trim()
            if (trimmed.startsWith("- ")) break
            val colon = trimmed.indexOf(':')
            if (colon <= 0) throw FrontMatterException(Copy.format("yaml_line", "line" to trimmed))
            val key = trimmed.substring(0, colon).trim()
            val rest = trimmed.substring(colon + 1).trim()
            index++
            val next = if (rest.isEmpty()) peekMeaningful() else null
            map[key] = if (rest.isNotEmpty()) {
                parseScalar(rest)
            } else if (next != null && indentOf(next) > current) {
                if (next.trimStart().startsWith("- ")) {
                    parseList(indentOf(next))
                } else {
                    YamlValue.MapValue(parseMap(indentOf(next)))
                }
            } else {
                YamlValue.Empty
            }
        }
        return map
    }

    private fun parseList(indent: Int): YamlValue.ListValue {
        val items = mutableListOf<YamlValue>()
        while (index < lines.size) {
            val raw = lines[index]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) {
                index++
                continue
            }
            val current = indentOf(raw)
            if (current < indent) break
            val trimmed = raw.trim()
            if (!trimmed.startsWith("- ")) break
            val rest = trimmed.removePrefix("- ").trim()
            index++
            items += if (rest.isEmpty()) {
                if (index < lines.size && indentOf(lines[index]) > current && lines[index].isNotBlank()) {
                    YamlValue.MapValue(parseMap(indentOf(lines[index])))
                } else {
                    YamlValue.Empty
                }
            } else {
                parseScalar(rest)
            }
        }
        return YamlValue.ListValue(items)
    }

    private fun peekMeaningful(): String? {
        var cursor = index
        while (cursor < lines.size) {
            val line = lines[cursor]
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                cursor++
                continue
            }
            return line
        }
        return null
    }
}

private fun parseScalar(raw: String): YamlValue {
    val text = raw.trim()
    if (text.isEmpty() || text == "null" || text == "~") return YamlValue.Empty
    if (text == "true") return YamlValue.Bool(true)
    if (text == "false") return YamlValue.Bool(false)
    if (text.startsWith("[") && text.endsWith("]")) {
        return YamlValue.ListValue(splitCollection(text, '[', ']').map(::parseScalar))
    }
    if (text.startsWith("{") && text.endsWith("}")) {
        val entries = linkedMapOf<String, YamlValue>()
        for (part in splitCollection(text, '{', '}')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            entries[part.substring(0, colon).trim()] = parseScalar(part.substring(colon + 1))
        }
        return YamlValue.MapValue(entries)
    }
    if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
        return YamlValue.Text(text.substring(1, text.length - 1))
    }
    if (text.matches(NUMBER)) return YamlValue.Number(text)
    return YamlValue.Text(text)
}

private fun splitCollection(body: String, open: Char, close: Char): List<String> {
    val inner = body.trim().removePrefix(open.toString()).removeSuffix(close.toString())
    if (inner.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var depth = 0
    for (ch in inner) {
        if (quote != null) {
            current.append(ch)
            if (ch == quote) quote = null
            continue
        }
        when (ch) {
            '"', '\'' -> {
                quote = ch
                current.append(ch)
            }
            '{', '[' -> {
                depth++
                current.append(ch)
            }
            '}', ']' -> {
                depth--
                current.append(ch)
            }
            ',' -> if (depth == 0) {
                parts += current.toString().trim()
                current.clear()
            } else {
                current.append(ch)
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotBlank()) parts += current.toString().trim()
    return parts.filter { it.isNotEmpty() }
}

private fun writeMap(out: StringBuilder, entries: Map<String, YamlValue>, indent: Int) {
    val pad = " ".repeat(indent)
    for ((key, value) in entries) {
        when (value) {
            is YamlValue.MapValue -> {
                out.append(pad).append(key).append(":\n")
                writeMap(out, value.entries, indent + 2)
            }
            is YamlValue.ListValue -> {
                if (value.items.all { it is YamlValue.Text || it is YamlValue.Number || it is YamlValue.Bool }) {
                    out.append(pad).append(key).append(": [")
                    out.append(value.items.joinToString(", ") { scalarText(it) })
                    out.append("]\n")
                } else {
                    out.append(pad).append(key).append(":\n")
                    for (item in value.items) {
                        out.append(pad).append("  - ")
                        when (item) {
                            is YamlValue.MapValue -> {
                                out.append("\n")
                                writeMap(out, item.entries, indent + 4)
                            }
                            else -> out.append(scalarText(item)).append("\n")
                        }
                    }
                }
            }
            else -> out.append(pad).append(key).append(": ").append(scalarText(value)).append("\n")
        }
    }
}

private fun scalarText(value: YamlValue): String = when (value) {
    is YamlValue.Text -> quoteIfNeeded(value.value)
    is YamlValue.Bool -> if (value.value) "true" else "false"
    is YamlValue.Number -> value.value
    YamlValue.Empty -> ""
    is YamlValue.MapValue -> {
        "{" + value.entries.entries.joinToString(", ") { (key, child) -> "$key: ${scalarText(child)}" } + "}"
    }
    is YamlValue.ListValue -> "[" + value.items.joinToString(", ") { scalarText(it) } + "]"
}

private fun quoteIfNeeded(value: String): String {
    val needsQuote = value.isEmpty() ||
        value != value.trim() ||
        value.any { it == ':' || it == '#' || it == ',' || it == '[' || it == ']' || it == '{' || it == '}' } ||
        value == "true" || value == "false" || value == "null" || value.matches(NUMBER)
    return if (needsQuote) "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else value
}

private fun indentOf(line: String): Int = line.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }

private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")

class FrontMatterException(message: String) : IllegalArgumentException(message)
