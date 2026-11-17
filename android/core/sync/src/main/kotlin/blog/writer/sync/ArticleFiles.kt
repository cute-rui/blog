package blog.writer.sync

fun articleDirectory(articlePath: String): String? {
    val directory = articlePath.substringBeforeLast('/', "")
    if (directory.contains("..")) return null
    if (!directory.startsWith("posts/") && !directory.startsWith("drafts/")) return null
    return directory
}

fun articleCommitPaths(articlePath: String, localPaths: Collection<String>): Set<String> {
    val directory = articleDirectory(articlePath) ?: return setOf(articlePath)
    return localPaths.filterTo(linkedSetOf()) { path ->
        path == articlePath || path.substringBeforeLast('/') == directory
    }
}

fun movedWithArticle(fromArticle: String, toArticle: String, files: List<String>): List<Pair<String, String>> {
    val fromDir = articleDirectory(fromArticle) ?: return emptyList()
    val toDir = articleDirectory(toArticle) ?: return emptyList()
    if (fromDir == toDir) return emptyList()
    return files.filter { it.substringBeforeLast('/') == fromDir }.map { file ->
        file to "$toDir/${file.substringAfterLast('/')}"
    }
}

fun resolveArticleImage(articlePath: String, reference: String): String? {
    val directory = articleDirectory(articlePath) ?: return null
    val raw = reference.trim()
    if (raw.isEmpty() || raw.contains('\\') || raw.startsWith("/") || raw.contains("://")) return null
    val relative = raw.removePrefix("./")
    if (relative.isEmpty() || relative.startsWith("/") || relative.split('/').any { it.isEmpty() || it == ".." }) return null
    return "$directory/$relative"
}

fun imageFileName(suggested: String?, bytes: ByteArray): String? {
    val extension = sniffImageExtension(bytes) ?: return null
    return sanitizeImageBase(suggested) + "." + extension
}

fun sniffImageExtension(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte()
    ) {
        return "png"
    }
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "jpg"
    }
    if (bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
        return "gif"
    }
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
    ) {
        return "webp"
    }
    val head = bytes.take(512).toByteArray().decodeToString().trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
    if (head.startsWith("<") && head.contains("<svg")) return "svg"
    return null
}

fun sanitizeImageBase(suggested: String?): String {
    val leaf = suggested?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    val stem = if ('.' in leaf) leaf.substringBeforeLast('.') else leaf
    val cleaned = buildString {
        for (ch in stem) {
            when {
                ch.isLetterOrDigit() || ch == '_' || ch == '-' -> append(ch)
                ch.isWhitespace() -> append('-')
            }
        }
    }.replace(Regex("-{2,}"), "-").trim('-')
    return cleaned.ifBlank { "image" }.take(80)
}

fun uniqueFileName(directory: String, fileName: String, exists: (String) -> Boolean): String {
    if (!exists("$directory/$fileName")) return fileName
    val stem = fileName.substringBeforeLast('.')
    val extension = fileName.substringAfterLast('.')
    var index = 2
    while (index < 100 && exists("$directory/$stem-$index.$extension")) index++
    if (index >= 100) throw IllegalArgumentException(fileName)
    return "$stem-$index.$extension"
}
