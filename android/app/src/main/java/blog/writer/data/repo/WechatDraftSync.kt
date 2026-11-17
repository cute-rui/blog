package blog.writer.data.repo

import blog.writer.content.PostDocument
import blog.writer.data.db.WechatDraftEntity
import blog.writer.data.db.WriterDao
import blog.writer.data.files.LocalContentStore
import blog.writer.egress.ClientConfig
import blog.writer.egress.CoverFractions
import blog.writer.egress.DraftBox
import blog.writer.egress.DraftHtml
import blog.writer.egress.DraftHtmlSource
import blog.writer.egress.DraftIds
import blog.writer.egress.DraftImage
import blog.writer.egress.DraftInput
import blog.writer.egress.DraftResult
import blog.writer.egress.EgressException
import blog.writer.egress.SrvDirectory
import blog.writer.egress.dial
import blog.writer.render.StubWechatMarkdownRenderer
import blog.writer.render.WechatMarkdownRenderer
import blog.writer.render.WechatRenderOptions
import blog.writer.render.WechatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts one article in the personal-account draft box through the egress.
 * Copy-page actions stay separate. This does not call freepublish or mass send.
 */
@Singleton
class WechatDraftSync @Inject constructor(
    private val posts: PostRepository,
    private val files: LocalContentStore,
    private val egressSettings: EgressSettingsRepository,
    private val github: GithubSettingsRepository,
    private val dao: WriterDao,
    private val renderer: WechatMarkdownRenderer,
    private val directory: SrvDirectory,
) {
    fun hasDraft(path: String): Boolean = dao.wechatDraft(path) != null

    suspend fun send(path: String): DraftResult = withContext(Dispatchers.IO) {
        val domain = egressSettings.domain() ?: throw EgressException("先在设置里填写 SRV 域名")
        val token = egressSettings.token() ?: throw EgressException("先在设置里填写出口令牌")
        val document = posts.read(path)
        val site = github.current().siteOrigin
        val client = dial(
            ClientConfig(
                token = token,
                domain = domain,
                insecure = false,
                lookup = directory,
            ),
        )
        DraftBox(
            render = DraftHtmlSource { markdown -> renderDraft(markdown) },
            egress = client,
            readFile = { files.readBytes(it) },
            draftIds = object : DraftIds {
                override fun get(path: String): String? = dao.wechatDraft(path)?.mediaId

                override fun put(path: String, mediaId: String) {
                    dao.upsertWechatDraft(WechatDraftEntity(path, mediaId))
                }
            },
            siteOrigin = site,
        ).send(document.toDraftInput())
    }

    private fun renderDraft(markdown: String): DraftHtml {
        val result = renderer.render(
            markdown = markdown,
            theme = WechatTheme(accent = "", ink = "", paper = "", muted = ""),
            options = WechatRenderOptions(imageUrlPrefix = ""),
        )
        if (result.htmlFragment == StubWechatMarkdownRenderer.MARKER) {
            throw EgressException("测试替身不能用来生成草稿")
        }
        return DraftHtml(
            html = result.htmlFragment,
            images = result.images.map { DraftImage(it.placeholder, it.path) },
        )
    }
}

private fun PostDocument.toDraftInput(): DraftInput {
    val matter = frontMatter
    return DraftInput(
        path = path,
        slug = slug,
        title = matter.wechatTitle(),
        body = body,
        author = matter.wechat.author.orEmpty(),
        digest = matter.wechatDigest(),
        publishBlog = matter.publish.blog,
        sourceUrl = matter.wechat.sourceUrl,
        coverPath = matter.wechat.cover?.takeIf { it.isNotBlank() } ?: matter.heroImage,
        coverCrop = matter.wechat.coverCrop?.let { CoverFractions(it.x1, it.y1, it.x2, it.y2) },
        openComment = matter.wechat.openComment,
        fansOnlyComment = matter.wechat.fansOnlyComment,
    )
}
