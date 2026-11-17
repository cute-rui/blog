package blog.writer.render

import java.util.Base64

fun imageDataUri(path: String, bytes: ByteArray): String {
    val mime = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
    return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
}

fun embedImageSources(html: String, replacements: List<Pair<String, String>>): String {
    var out = html
    for ((placeholder, src) in replacements) {
        if (placeholder.isNotEmpty() && placeholder != src) out = out.replace(placeholder, src)
    }
    return out
}

private val markdownImage = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

fun blogPreviewHtml(markdown: String, srcFor: (String) -> String?): String {
    val images = mutableListOf<Pair<String, String>>()
    var index = 0
    val marked = markdownImage.replace(markdown) { match ->
        val src = srcFor(match.groupValues[2].trim()) ?: return@replace match.value
        val token = "\uE000img$index\uE001"
        val alt = match.groupValues[1].replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        images += token to """<img alt="$alt" src="$src" style="max-width:100%;height:auto;display:block;" />"""
        index++
        token
    }
    var html = plainBlogHtml(marked)
    for ((token, image) in images) html = html.replace(token, image)
    return html
}

private fun plainBlogHtml(markdown: String): String {
    val escaped = markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val blocks = escaped.split("\n\n").joinToString("") { block ->
        val line = block.trim()
        when {
            line.startsWith("# ") -> "<h1>${line.removePrefix("# ")}</h1>"
            line.startsWith("## ") -> "<h2>${line.removePrefix("## ")}</h2>"
            line.startsWith("### ") -> "<h3>${line.removePrefix("### ")}</h3>"
            else -> "<p>${line.replace("\n", "<br>")}</p>"
        }
    }
    return """
        <html><body style="background:#FAF7F0;color:#1E1C19;font-family:serif;padding:24px;line-height:1.75">
        $blocks
        </body></html>
        """.trimIndent()
}
