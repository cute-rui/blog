package blog.writer.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentTest {
    @Test
    fun frontMatterRoundTripKeepsWechatFieldsAndExtraKeys() {
        val source = """
            ---
            title: 从零搭一个双端发布的博客
            description: 内容仓库 push 之后发生了什么
            pubDate: 2026-11-14
            updatedDate: 2026-11-20
            tags: [工程, 写作]
            draft: false
            heroImage: ./cover.jpg
            heroAlt: 一张流程图
            publish:
              blog: true
              wechat: true
            wechat:
              title: 双端发布的博客怎么搭
              author: Lightwing
              digest: 摘要
              cover: ./cover-wechat.jpg
              coverCrop: { x1: 0, y1: 0.1, x2: 1, y2: 0.52 }
              openComment: true
              fansOnlyComment: false
              sourceUrl: auto
            series: 随笔
            ---

            正文第一段
        """.trimIndent()
        val document = FrontMatterCodec.decode("posts/double-publish/index.md", source)
        assertEquals("double-publish", document.slug)
        assertEquals("双端发布的博客怎么搭", document.frontMatter.wechatTitle())
        assertEquals(0.1, document.frontMatter.wechat.coverCrop?.y1)
        assertEquals("随笔", document.frontMatter.extra["series"]?.asString())
        assertEquals("正文第一段", document.body.trim())
        val again = FrontMatterCodec.decode(document.path, FrontMatterCodec.encode(document))
        assertEquals(document.frontMatter, again.frontMatter)
        assertEquals(document.body, again.body)
    }

    @Test
    fun missingFrontMatterBecomesADraftShell() {
        val document = FrontMatterCodec.decode("drafts/note/index.md", "只有正文")
        assertEquals("未命名", document.frontMatter.title)
        assertEquals("只有正文", document.body)
        assertTrue(document.frontMatter.draft)
    }

    @Test
    fun wechatRulesCatchTitleDigestAndMdx() {
        val longTitle = "标".repeat(33)
        val document = PostDocument(
            path = "posts/long/index.mdx",
            frontMatter = PostFrontMatter(
                title = longTitle,
                description = "摘".repeat(121),
                pubDate = "2026-11-14",
                publish = PublishTargets(blog = true, wechat = true),
            ),
            body = "正文",
        )
        val issues = ContentRules.inspect(
            document,
            PublishTargets(blog = false, wechat = true),
            rendered = null,
            rendererBound = false,
        )
        assertTrue(issues.any { it.code == "wechat-title" && it.severity == IssueSeverity.Fail })
        assertTrue(issues.any { it.code == "wechat-digest" })
        assertTrue(issues.any { it.code == "mdx" })
        assertTrue(issues.any { it.code == "md2wx" && it.severity == IssueSeverity.Warn })
        assertTrue(issues.any { it.code == "source-url" && it.severity == IssueSeverity.Note })
    }

    @Test
    fun renderedHtmlOverLimitFails() {
        val document = PostDocument(
            path = "posts/ok/index.md",
            frontMatter = PostFrontMatter(
                title = "短标题",
                description = "描述",
                pubDate = "2026-11-14",
                heroImage = "./cover.jpg",
                publish = PublishTargets(blog = true, wechat = true),
            ),
            body = "正文",
        )
        val issues = ContentRules.inspect(
            document,
            PublishTargets(blog = true, wechat = true),
            rendered = WechatHtmlLength(characters = 20_000, bytes = 10),
            rendererBound = true,
        )
        assertTrue(issues.any { it.code == "html-length" })
        assertFalse(issues.any { it.code == "md2wx" })
    }

    @Test
    fun trailerCanOnlyNarrow() {
        val defaults = PublishTargets(blog = true, wechat = false)
        val widened = effectiveTargets(defaults, PublishSelection.parseToken("blog,wechat"))
        assertFalse(widened.wechat)
        assertTrue(widened.blog)
        val narrowed = narrowSelection(PublishTargets(blog = true, wechat = true), PublishSelection(blog = false, wechat = true))
        assertFalse(narrowed.blog)
        assertTrue(narrowed.wechat)
        val message = commitMessage("改第三节", PublishSelection(blog = false, wechat = true))
        assertEquals(PublishSelection(blog = false, wechat = true), parsePublishTrailer(message))
        assertNull(parsePublishTrailer("没有 trailer"))
        assertEquals("none", PublishSelection(false, false).token())
    }

    @Test
    fun publishButtonCopyFollowsTheSelection() {
        assertEquals("发布到博客，并生成公众号版本", publishButtonLabel(blog = true, wechat = true))
        assertEquals("发布到博客", publishButtonLabel(blog = true, wechat = false))
        assertEquals("生成公众号版本", publishButtonLabel(blog = false, wechat = true))
        assertEquals("只保存，不发布", publishButtonLabel(blog = false, wechat = false))
    }

    @Test
    fun wordCountIgnoresWhitespace() {
        assertEquals(6, countWritingCharacters("你 好\nword"))
    }

    @Test
    fun wechatBodyOverridesRoundTripWithNewlinesAndQuotes() {
        val document = PostDocument(
            path = "posts/shift/index.md",
            frontMatter = PostFrontMatter(
                title = "博客标题",
                description = "博客摘要",
                pubDate = "2026-11-14",
                heroImage = "./cover.jpg",
                publish = PublishTargets(blog = true, wechat = true),
                wechat = WechatFields(
                    title = "短标题",
                    digest = "公众号摘要",
                    cover = "./cover-wechat.jpg",
                    body = listOf(BodyOverride("他说\"甲\"\n下一行", "公众号\n这一段")),
                ),
            ),
            body = "他说\"甲\"\n下一行\n\n其余共用。\n",
        )
        val again = FrontMatterCodec.decode(document.path, FrontMatterCodec.encode(document))
        assertEquals(document.frontMatter, again.frontMatter)
        assertEquals(document.body, again.body)
        assertEquals("短标题", again.frontMatter.wechatTitle())
        assertEquals("公众号摘要", again.frontMatter.wechatDigest())
    }

    @Test
    fun staleWechatBodyOverrideWarnsOnlyWhenWechatIsSelected() {
        val document = PostDocument(
            path = "posts/ok/index.md",
            frontMatter = PostFrontMatter(
                title = "短标题",
                description = "描述",
                pubDate = "2026-11-14",
                heroImage = "./cover.jpg",
                publish = PublishTargets(blog = true, wechat = true),
                wechat = WechatFields(body = listOf(BodyOverride("不在正文里", "改"))),
            ),
            body = "正文",
        )
        val selected = ContentRules.inspect(
            document,
            PublishTargets(blog = true, wechat = true),
            rendered = null,
            rendererBound = false,
        )
        assertTrue(selected.any { it.code == "wechat-body" && it.severity == IssueSeverity.Warn })
        val blogOnly = ContentRules.inspect(
            document,
            PublishTargets(blog = true, wechat = false),
            rendered = null,
            rendererBound = false,
        )
        assertFalse(blogOnly.any { it.code == "wechat-body" })
    }

    @Test
    fun schemaListsTheSharedRequiredFields() {
        val schema = ContentSchema.text()
        ContentSchema.requiredKeys().forEach { key ->
            assertTrue(schema.contains("\"$key\""))
        }
        assertTrue(schema.contains("\"wechat\""))
        assertTrue(schema.contains("\"publish\""))
    }
}
