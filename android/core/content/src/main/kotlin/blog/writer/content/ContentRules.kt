package blog.writer.content
import blog.writer.copy.Copy

enum class IssueSeverity { Fail, Warn, Note }

data class ContentIssue(
    val code: String,
    val message: String,
    val severity: IssueSeverity,
)

data class WechatHtmlLength(
    val characters: Int,
    val bytes: Int,
)

object ContentRules {
    const val WECHAT_TITLE_LIMIT = 32
    const val WECHAT_AUTHOR_LIMIT = 16
    const val WECHAT_DIGEST_LIMIT = 120
    const val WECHAT_HTML_CHAR_LIMIT = 20_000
    const val WECHAT_HTML_BYTE_LIMIT = 1_000_000

    fun inspect(
        document: PostDocument,
        selection: PublishTargets,
        rendered: WechatHtmlLength?,
        rendererBound: Boolean,
    ): List<ContentIssue> {
        val issues = mutableListOf<ContentIssue>()
        val matter = document.frontMatter
        if (matter.title.isBlank()) {
            issues += ContentIssue("title", Copy["issue_title_empty"], IssueSeverity.Fail)
        }
        if (matter.description.isBlank()) {
            issues += ContentIssue("description", Copy["issue_description_empty"], IssueSeverity.Fail)
        }
        if (matter.pubDate.isNotBlank() && !DATE.matches(matter.pubDate)) {
            issues += ContentIssue("pubDate", Copy["issue_pub_date"], IssueSeverity.Fail)
        }
        if (!selection.blog && selection.wechat) {
            issues += ContentIssue(
                "source-url",
                Copy["issue_source_url"],
                IssueSeverity.Note,
            )
        }
        if (selection.wechat) {
            val title = matter.wechatTitle()
            if (charCount(title) > WECHAT_TITLE_LIMIT && matter.wechat.title.isNullOrBlank()) {
                issues += ContentIssue(
                    "wechat-title",
                    Copy.format("issue_wechat_title_needs_override", "limit" to WECHAT_TITLE_LIMIT),
                    IssueSeverity.Fail,
                )
            } else if (charCount(title) > WECHAT_TITLE_LIMIT) {
                issues += ContentIssue(
                    "wechat-title",
                    Copy.format("issue_wechat_title_long", "limit" to WECHAT_TITLE_LIMIT),
                    IssueSeverity.Fail,
                )
            }
            val author = matter.wechat.author
            if (author != null && charCount(author) > WECHAT_AUTHOR_LIMIT) {
                issues += ContentIssue("wechat-author", Copy.format("issue_wechat_author", "limit" to WECHAT_AUTHOR_LIMIT), IssueSeverity.Fail)
            }
            if (charCount(matter.wechatDigest()) > WECHAT_DIGEST_LIMIT) {
                issues += ContentIssue("wechat-digest", Copy.format("issue_wechat_digest", "limit" to WECHAT_DIGEST_LIMIT), IssueSeverity.Fail)
            }
            if (!document.markdownFile) {
                issues += ContentIssue("mdx", Copy["issue_mdx"], IssueSeverity.Fail)
            }
            if (matter.heroImage.isNullOrBlank() && matter.wechat.cover.isNullOrBlank()) {
                issues += ContentIssue("cover", Copy["issue_cover"], IssueSeverity.Warn)
            }
            if (!rendererBound || rendered == null) {
                issues += ContentIssue(
                    "md2wx",
                    Copy["issue_md2wx_unbound"],
                    IssueSeverity.Warn,
                )
            } else if (rendered.characters >= WECHAT_HTML_CHAR_LIMIT || rendered.bytes >= WECHAT_HTML_BYTE_LIMIT) {
                issues += ContentIssue(
                    "html-length",
                    Copy.format("issue_html_length", "count" to rendered.characters),
                    IssueSeverity.Fail,
                )
            }
        }
        return issues
    }

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
}

fun publishButtonLabel(blog: Boolean, wechat: Boolean): String = when {
    blog && wechat -> Copy["publish_blog_and_wechat"]
    blog -> Copy["publish_blog"]
    wechat -> Copy["publish_wechat"]
    else -> Copy["publish_save_only"]
}
