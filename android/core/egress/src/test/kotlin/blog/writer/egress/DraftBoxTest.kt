package blog.writer.egress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DraftBoxTest {
    @Test
    fun uploadsImagesAndCoverThenAddsOrUpdates() {
        val files = mutableMapOf(
            "posts/demo/photo.png" to byteArrayOf(1, 2, 3),
            "posts/demo/cover.jpg" to byteArrayOf(4, 5),
        )
        val egress = RecordingEgress()
        val ids = MemoryDraftIds()
        val box = DraftBox(
            render = DraftHtmlSource {
                DraftHtml(
                    html = """<section><img src="{{wx-img:1}}"><img src="{{wx-img:10}}"></section>""",
                    images = listOf(
                        DraftImage("{{wx-img:1}}", "./photo.png"),
                        DraftImage("{{wx-img:10}}", "extra/ten.png"),
                    ),
                )
            },
            egress = egress,
            readFile = { path ->
                if (path == "posts/demo/extra/ten.png") byteArrayOf(9) else files[path]
            },
            draftIds = ids,
            siteOrigin = "https://blog.example",
        )
        val input = DraftInput(
            path = "posts/demo/index.md",
            slug = "demo",
            title = "标题",
            body = "ignored",
            author = "Lightwing",
            digest = "摘要",
            sourceUrl = "auto",
            coverPath = "./cover.jpg",
            coverCrop = CoverFractions(0.0, 0.1, 1.0, 0.52),
            openComment = true,
            fansOnlyComment = false,
        )
        val added = box.send(input)
        assertEquals("draft-1", added.mediaId)
        assertTrue(!added.updated)
        assertEquals(listOf("uploadImage", "uploadImage", "uploadCover", "addDraft"), egress.calls)
        val article = egress.articles.single()
        assertEquals("http://mmbiz.qpic.cn/1", article.content.substringAfter("src=\"").substringBefore('"'))
        assertTrue(article.content.contains("http://mmbiz.qpic.cn/10"))
        assertTrue(!article.content.contains("{{wx-img:"))
        assertTrue(!article.content.contains("api.weixin.qq.com"))
        assertEquals("thumb-1", article.thumbMediaId)
        assertEquals("https://blog.example/posts/demo/", article.contentSourceUrl)
        assertEquals(1, article.needOpenComment)
        assertEquals(0, article.onlyFansCanComment)
        assertEquals("2.35_1", article.coverCrops.single().ratio)
        assertEquals("0.1", article.coverCrops.single().y1)
        assertEquals("draft-1", ids.get("posts/demo/index.md"))

        egress.calls.clear()
        val updated = box.send(input.copy(title = "新标题"))
        assertTrue(updated.updated)
        assertEquals("draft-1", updated.mediaId)
        assertEquals(listOf("uploadImage", "uploadImage", "uploadCover", "updateDraft"), egress.calls)
        assertEquals("新标题", egress.updatedTitle)
    }

    @Test
    fun missingCoverDoesNotAddADraft() {
        val egress = RecordingEgress()
        val box = DraftBox(
            render = DraftHtmlSource { DraftHtml("<section><p>只有字</p></section>", emptyList()) },
            egress = egress,
            readFile = { null },
            draftIds = MemoryDraftIds(),
            siteOrigin = "https://blog.example",
        )
        try {
            box.send(DraftInput(path = "posts/demo/index.md", slug = "demo", title = "标题", body = "x"))
            fail("cover was optional")
        } catch (error: EgressException) {
            assertTrue(error.message!!.contains("封面"))
        }
        assertTrue(egress.calls.isEmpty())
    }

    @Test
    fun rejectsWeChatApiHostAndParentPaths() {
        try {
            requireImageUrl("https://api.weixin.qq.com/cgi-bin/media/uploadimg")
            fail("api host was accepted")
        } catch (error: EgressException) {
            assertTrue(error.message!!.contains("微信接口"))
        }
        try {
            resolvePostFile("posts/demo/index.md", "../secret.png")
            fail("parent path was accepted")
        } catch (error: EgressException) {
            assertTrue(error.message!!.contains(".."))
        }
        val html = substituteImages(
            """<section><img src="{{wx-img:10}}"><img src="{{wx-img:1}}"></section>""",
            listOf(
                "{{wx-img:1}}" to "http://mmbiz.qpic.cn/one",
                "{{wx-img:10}}" to "http://mmbiz.qpic.cn/ten",
            ),
        )
        assertTrue(html.contains("http://mmbiz.qpic.cn/one"))
        assertTrue(html.contains("http://mmbiz.qpic.cn/ten"))
        assertTrue(!html.contains("{{wx-img:"))
        assertEquals("", contentSourceUrl(DraftInput(path = "posts/a/index.md", slug = "a", title = "t", body = "b", publishBlog = false), "https://blog.example"))
    }
}

private class RecordingEgress : WechatEgress {
    val calls = mutableListOf<String>()
    val articles = mutableListOf<EgressArticle>()
    var updatedTitle = ""

    override fun uploadImage(content: ByteArray, filename: String): String {
        calls += "uploadImage"
        return if (filename == "ten.png") "http://mmbiz.qpic.cn/10" else "http://mmbiz.qpic.cn/1"
    }

    override fun uploadCover(content: ByteArray, filename: String): String {
        calls += "uploadCover"
        assertEquals("cover.jpg", filename)
        return "thumb-1"
    }

    override fun addDraft(article: EgressArticle): String {
        calls += "addDraft"
        articles += article
        return "draft-1"
    }

    override fun updateDraft(mediaId: String, article: EgressArticle) {
        calls += "updateDraft"
        assertEquals("draft-1", mediaId)
        updatedTitle = article.title
    }
}

private class MemoryDraftIds : DraftIds {
    private val ids = mutableMapOf<String, String>()

    override fun get(path: String): String? = ids[path]

    override fun put(path: String, mediaId: String) {
        ids[path] = mediaId
    }
}
