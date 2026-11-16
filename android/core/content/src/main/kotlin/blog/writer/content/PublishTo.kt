package blog.writer.content
import blog.writer.copy.Copy

data class PublishSelection(
    val blog: Boolean,
    val wechat: Boolean,
) {
    fun token(): String = when {
        blog && wechat -> "blog,wechat"
        blog -> "blog"
        wechat -> "wechat"
        else -> "none"
    }

    companion object {
        fun parseToken(token: String): PublishSelection? {
            val parts = token.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return when {
                parts.isEmpty() -> null
                parts == setOf("none") -> PublishSelection(blog = false, wechat = false)
                parts.all { it == "blog" || it == "wechat" } -> PublishSelection(
                    blog = "blog" in parts,
                    wechat = "wechat" in parts,
                )
                else -> null
            }
        }
    }
}

fun effectiveTargets(defaults: PublishTargets, trailer: PublishSelection?): PublishTargets {
    if (trailer == null) return defaults
    return PublishTargets(
        blog = defaults.blog && trailer.blog,
        wechat = defaults.wechat && trailer.wechat,
    )
}

fun commitMessage(summary: String, selection: PublishSelection): String {
    val head = summary.trim().ifEmpty { Copy["commit_default"] }
    return "$head\n\nPublish-To: ${selection.token()}\n"
}

fun parsePublishTrailer(message: String): PublishSelection? {
    val line = message.replace("\r\n", "\n").lineSequence()
        .map { it.trim() }
        .lastOrNull { it.startsWith("Publish-To:") }
        ?: return null
    return PublishSelection.parseToken(line.removePrefix("Publish-To:").trim())
}

fun narrowSelection(defaults: PublishTargets, requested: PublishSelection): PublishSelection {
    return PublishSelection(
        blog = defaults.blog && requested.blog,
        wechat = defaults.wechat && requested.wechat,
    )
}
