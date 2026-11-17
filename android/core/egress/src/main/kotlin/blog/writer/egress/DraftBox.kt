package blog.writer.egress
import blog.writer.copy.Copy

import java.math.BigDecimal
import java.net.URI

data class DraftImage(
    val placeholder: String,
    val path: String,
)

data class DraftHtml(
    val html: String,
    val images: List<DraftImage>,
)

data class CoverFractions(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
)

data class DraftInput(
    val path: String,
    val slug: String,
    val title: String,
    val body: String,
    val author: String = "",
    val digest: String = "",
    val publishBlog: Boolean = true,
    val sourceUrl: String? = null,
    val coverPath: String? = null,
    val coverCrop: CoverFractions? = null,
    val openComment: Boolean? = null,
    val fansOnlyComment: Boolean? = null,
)

data class DraftResult(
    val mediaId: String,
    val updated: Boolean,
)

fun interface DraftHtmlSource {
    fun render(markdown: String): DraftHtml
}

interface DraftIds {
    fun get(path: String): String?

    fun put(path: String, mediaId: String)
}

/**
 * Renders with the caller's Markdown renderer, uploads local images and the
 * cover through [WechatEgress], then adds or updates one draft. There is no
 * freepublish and no mass send.
 */
class DraftBox(
    private val render: DraftHtmlSource,
    private val egress: WechatEgress,
    private val readFile: (String) -> ByteArray?,
    private val draftIds: DraftIds,
    private val siteOrigin: String,
) {
    fun send(input: DraftInput): DraftResult {
        val rendered = render.render(input.body)
        val html = fillImages(input.path, rendered)
        val characters = html.codePointCount(0, html.length)
        val size = html.toByteArray(Charsets.UTF_8).size
        if (characters < 1 || characters >= 20_000) throw EgressException(Copy["body_char_limit"])
        if (size >= 1 shl 20) throw EgressException(Copy["body_byte_limit"])
        val coverRef = input.coverPath?.trim().orEmpty()
        if (coverRef.isEmpty()) throw EgressException(Copy["cover_required"])
        val coverFile = resolvePostFile(input.path, coverRef)
        val cover = readFile(coverFile) ?: throw EgressException(Copy.format("cover_missing", "path" to coverFile))
        val thumb = egress.uploadCover(cover, fileName(coverFile))
        val article = EgressArticle(
            title = input.title,
            author = input.author,
            digest = input.digest,
            content = html,
            contentSourceUrl = contentSourceUrl(input, siteOrigin),
            thumbMediaId = thumb,
            needOpenComment = flag(input.openComment),
            onlyFansCanComment = flag(input.fansOnlyComment),
            coverCrops = crops(input.coverCrop),
        )
        val existing = draftIds.get(input.path)?.trim().orEmpty()
        if (existing.isEmpty()) {
            val mediaId = egress.addDraft(article)
            if (mediaId.isBlank()) throw EgressException(Copy["media_id_missing"])
            draftIds.put(input.path, mediaId)
            return DraftResult(mediaId, updated = false)
        }
        egress.updateDraft(existing, article)
        return DraftResult(existing, updated = true)
    }

    private fun fillImages(postPath: String, rendered: DraftHtml): String {
        val replacements = rendered.images.map { image ->
            if (!rendered.html.contains(image.placeholder)) throw EgressException(Copy.format("image_placeholder_missing", "placeholder" to image.placeholder))
            val path = resolvePostFile(postPath, image.path)
            val bytes = readFile(path) ?: throw EgressException(Copy.format("image_missing", "path" to path))
            image.placeholder to egress.uploadImage(bytes, fileName(path))
        }
        return substituteImages(rendered.html, replacements)
    }
}

fun substituteImages(html: String, replacements: List<Pair<String, String>>): String {
    var out = html
    for ((placeholder, url) in replacements.sortedByDescending { it.first.length }) {
        if (!PLACEHOLDER.matches(placeholder)) throw EgressException(Copy["image_placeholder_bad"])
        if (!out.contains(placeholder)) throw EgressException(Copy.format("image_placeholder_missing", "placeholder" to placeholder))
        out = out.replace(placeholder, requireImageUrl(url))
    }
    if (out.contains("{{wx-img:")) throw EgressException(Copy["images_remain"])
    return out
}

fun requireImageUrl(url: String): String {
    val parsed = try {
        URI(url)
    } catch (error: Exception) {
        throw EgressException(Copy["image_url_bad"], error)
    }
    if (parsed.scheme != "http" && parsed.scheme != "https") throw EgressException(Copy["image_url_scheme"])
    val host = parsed.host ?: throw EgressException(Copy["image_url_host"])
    if (parsed.userInfo != null) throw EgressException(Copy["image_url_userinfo"])
    if (host.equals("api.weixin.qq.com", ignoreCase = true)) {
        throw EgressException(Copy["image_url_wechat_api"])
    }
    return url
}

fun resolvePostFile(postPath: String, ref: String): String {
    val raw = ref.trim()
    if (raw.isEmpty() || raw.contains('\\') || raw.startsWith("/") || raw.contains("://")) {
        throw EgressException(Copy["image_path_bad"])
    }
    if (raw.split('/').any { it == ".." }) throw EgressException(Copy["image_path_parent"])
    val dir = postPath.substringBeforeLast('/', "")
    if (!dir.startsWith("posts/") && !dir.startsWith("drafts/")) throw EgressException(Copy["post_path_bad"])
    val relative = raw.removePrefix("./")
    if (relative.isEmpty() || relative.startsWith("/")) throw EgressException(Copy["image_path_bad"])
    return "$dir/$relative"
}

fun contentSourceUrl(input: DraftInput, siteOrigin: String): String {
    val raw = input.sourceUrl?.trim().orEmpty()
    if (raw.isNotEmpty() && !raw.equals("auto", ignoreCase = true)) return raw
    if (!input.publishBlog) return ""
    val origin = siteOrigin.trim().trimEnd('/')
    if (!origin.startsWith("https://") && !origin.startsWith("http://")) return ""
    return "$origin/posts/${input.slug}/"
}

private fun crops(crop: CoverFractions?): List<EgressCrop> {
    if (crop == null) return emptyList()
    return listOf(
        EgressCrop(
            ratio = "2.35_1",
            x1 = unit(crop.x1),
            y1 = unit(crop.y1),
            x2 = unit(crop.x2),
            y2 = unit(crop.y2),
        ),
    )
}

private fun unit(value: Double): String {
    if (value.isNaN() || value < 0.0 || value > 1.0) throw EgressException(Copy["cover_crop_range"])
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private fun flag(value: Boolean?): Int? = when (value) {
    null -> null
    true -> 1
    false -> 0
}

private fun fileName(path: String): String = path.substringAfterLast('/')

private val PLACEHOLDER = Regex("""\{\{wx-img:\d+\}\}""")
