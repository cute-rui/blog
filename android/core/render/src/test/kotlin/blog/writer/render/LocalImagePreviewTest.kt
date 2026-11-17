package blog.writer.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImagePreviewTest {
    @Test
    fun blogPreviewKeepsHeadingsAndShowsTheLocalImage() {
        val html = blogPreviewHtml("## 标题\n\n![图](./dot.png)") {
            "data:image/png;base64,YQ=="
        }
        assertTrue(html.contains("<h2>标题</h2>"))
        assertTrue(html.contains("""src="data:image/png;base64,YQ==""""))
        assertFalse(html.contains("![图]"))
    }

    @Test
    fun wechatPlaceholderIsFilledWithoutAnotherRenderer() {
        val html = embedImageSources(
            """<img src="{{wx-img:0}}" alt="" />""",
            listOf("{{wx-img:0}}" to imageDataUri("dot.png", byteArrayOf(1))),
        )
        assertTrue(html.contains("data:image/png;base64,"))
        assertFalse(html.contains("{{wx-img:"))
    }
}
