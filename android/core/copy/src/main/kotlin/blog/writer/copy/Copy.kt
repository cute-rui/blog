package blog.writer.copy

object Copy {
    @Volatile
    private var table: Map<String, String>? = null

    @Synchronized
    fun load(json: String) {
        table = parse(json)
    }

    operator fun get(key: String): String {
        val values = table ?: synchronized(this) {
            table ?: parse(readClasspath()).also { table = it }
        }
        return values[key] ?: error("Missing copy key: $key")
    }

    fun format(key: String, vararg pairs: Pair<String, Any?>): String {
        var text = get(key)
        for ((name, value) in pairs) {
            text = text.replace("{$name}", value?.toString().orEmpty())
        }
        return text
    }

    private fun readClasspath(): String {
        val stream = Copy::class.java.classLoader.getResourceAsStream("app-copy.json")
            ?: error("app-copy.json is missing from the classpath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    internal fun parse(json: String): Map<String, String> {
        val source = json.trim()
        require(source.startsWith("{") && source.endsWith("}")) { "copy catalog must be a JSON object" }
        val out = LinkedHashMap<String, String>()
        var index = 1

        fun skip() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        fun parseString(): String {
            require(index < source.length && source[index] == '"') { "expected a string" }
            index++
            val buffer = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when (char) {
                    '"' -> return buffer.toString()
                    '\\' -> {
                        require(index < source.length) { "truncated escape" }
                        when (val escaped = source[index++]) {
                            '"' -> buffer.append('"')
                            '\\' -> buffer.append('\\')
                            '/' -> buffer.append('/')
                            'b' -> buffer.append('\b')
                            'f' -> buffer.append('\u000C')
                            'n' -> buffer.append('\n')
                            'r' -> buffer.append('\r')
                            't' -> buffer.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "truncated unicode escape" }
                                buffer.append(source.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("bad escape \\$escaped")
                        }
                    }
                    else -> buffer.append(char)
                }
            }
            error("unterminated string")
        }

        skip()
        if (source[index] == '}') return out
        while (true) {
            skip()
            val key = parseString()
            skip()
            require(source[index] == ':') { "expected colon" }
            index++
            skip()
            out[key] = parseString()
            skip()
            when (source[index]) {
                ',' -> index++
                '}' -> return out
                else -> error("expected comma or object end")
            }
        }
    }
}
