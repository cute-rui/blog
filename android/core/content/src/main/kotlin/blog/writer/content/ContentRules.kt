package blog.writer.content

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
            issues += ContentIssue("title", "标题还空着", IssueSeverity.Fail)
        }
        if (matter.description.isBlank()) {
            issues += ContentIssue("description", "描述还空着", IssueSeverity.Fail)
        }
        if (matter.pubDate.isNotBlank() && !DATE.matches(matter.pubDate)) {
            issues += ContentIssue("pubDate", "发布日期要用 YYYY-MM-DD", IssueSeverity.Fail)
        }
        if (!selection.blog && selection.wechat) {
            issues += ContentIssue(
                "source-url",
                "不发博客，「阅读原文」链接将为空",
                IssueSeverity.Note,
            )
        }
        if (selection.wechat) {
            val title = matter.wechatTitle()
            if (charCount(title) > WECHAT_TITLE_LIMIT && matter.wechat.title.isNullOrBlank()) {
                issues += ContentIssue(
                    "wechat-title",
                    "公众号标题超过 $WECHAT_TITLE_LIMIT 字，需要另填公众号标题",
                    IssueSeverity.Fail,
                )
            } else if (charCount(title) > WECHAT_TITLE_LIMIT) {
                issues += ContentIssue(
                    "wechat-title",
                    "公众号标题超过 $WECHAT_TITLE_LIMIT 字",
                    IssueSeverity.Fail,
                )
            }
            val author = matter.wechat.author
            if (author != null && charCount(author) > WECHAT_AUTHOR_LIMIT) {
                issues += ContentIssue("wechat-author", "公众号作者超过 $WECHAT_AUTHOR_LIMIT 字", IssueSeverity.Fail)
            }
            if (charCount(matter.wechatDigest()) > WECHAT_DIGEST_LIMIT) {
                issues += ContentIssue("wechat-digest", "摘要超过 $WECHAT_DIGEST_LIMIT 字", IssueSeverity.Fail)
            }
            if (!document.markdownFile) {
                issues += ContentIssue("mdx", "发公众号的文章必须是 Markdown，不能是 MDX", IssueSeverity.Fail)
            }
            if (matter.heroImage.isNullOrBlank() && matter.wechat.cover.isNullOrBlank()) {
                issues += ContentIssue("cover", "还没有封面图", IssueSeverity.Warn)
            }
            if (!rendererBound || rendered == null) {
                issues += ContentIssue(
                    "md2wx",
                    "还不能用渲染结果检查 2 万字上限（md2wx 未绑定）",
                    IssueSeverity.Warn,
                )
            } else if (rendered.characters >= WECHAT_HTML_CHAR_LIMIT || rendered.bytes >= WECHAT_HTML_BYTE_LIMIT) {
                issues += ContentIssue(
                    "html-length",
                    "公众号 HTML 超过上限（${rendered.characters} 字符）",
                    IssueSeverity.Fail,
                )
            }
        }
        return issues
    }

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
}

fun publishButtonLabel(blog: Boolean, wechat: Boolean): String = when {
    blog && wechat -> "发布到博客，并生成公众号版本"
    blog -> "发布到博客"
    wechat -> "生成公众号版本"
    else -> "只保存，不发布"
}
