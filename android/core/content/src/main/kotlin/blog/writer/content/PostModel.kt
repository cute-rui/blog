package blog.writer.content

data class PublishTargets(
    val blog: Boolean = true,
    val wechat: Boolean = false,
)

data class CoverCrop(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
)

data class WechatFields(
    val title: String? = null,
    val author: String? = null,
    val digest: String? = null,
    val cover: String? = null,
    val coverCrop: CoverCrop? = null,
    val openComment: Boolean? = null,
    val fansOnlyComment: Boolean? = null,
    val sourceUrl: String? = null,
)

data class PostFrontMatter(
    val title: String = "",
    val description: String = "",
    val pubDate: String = "",
    val updatedDate: String? = null,
    val tags: List<String> = emptyList(),
    val draft: Boolean = true,
    val heroImage: String? = null,
    val heroAlt: String? = null,
    val publish: PublishTargets = PublishTargets(),
    val wechat: WechatFields = WechatFields(),
    val extra: Map<String, YamlValue> = emptyMap(),
) {
    fun wechatTitle(): String = wechat.title?.takeIf { it.isNotBlank() } ?: title

    fun wechatDigest(): String = wechat.digest?.takeIf { it.isNotBlank() } ?: description
}

data class PostDocument(
    val path: String,
    val frontMatter: PostFrontMatter,
    val body: String,
) {
    val slug: String
        get() = slugOf(path)

    val markdownFile: Boolean
        get() = path.endsWith(".md") && !path.endsWith(".mdx")
}

fun slugOf(path: String): String {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return if (parts.size >= 2) parts[parts.size - 2] else path.substringAfterLast('/').substringBeforeLast('.')
}

fun postPath(slug: String, drafts: Boolean): String =
    if (drafts) "drafts/$slug/index.md" else "posts/$slug/index.md"

fun isDraftPath(path: String): Boolean = path.startsWith("drafts/")

fun charCount(value: String): Int = value.codePointCount(0, value.length)

fun countWritingCharacters(markdown: String): Int =
    markdown.codePoints().filter { !Character.isWhitespace(it) }.count().toInt()
