package blog.writer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StubRendererTest {
    @Test
    fun stubDoesNotRenderMarkdown() {
        val markdown = "# 标题\n\n一段话。\n\n![](./cover.png)\n"
        val result = StubWechatMarkdownRenderer().render(
            markdown = markdown,
            theme = WechatTheme(accent = "#2D5786", ink = "#1E1C19", paper = "#FAF7F0", muted = "#4C463E"),
            options = WechatRenderOptions(imageUrlPrefix = "https://example.com/wx/post/"),
        )
        assertEquals(StubWechatMarkdownRenderer.MARKER, result.htmlFragment)
        assertTrue(result.images.isEmpty())
        assertFalse(result.htmlFragment.contains("标题"))
        assertFalse(result.htmlFragment.contains("cover.png"))
        assertEquals(result.htmlFragment.length, result.length.characters)
        assertEquals(result.htmlFragment.toByteArray().size, result.length.bytes)
    }
}
