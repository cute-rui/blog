package blog.writer.render

/**
 * Test double standing in for the UniFFI binding of the `md2wx` crate.
 *
 * It does not convert Markdown into WeChat HTML. A later change replaces this binding
 * with the real renderer; callers already depend only on [WechatMarkdownRenderer].
 */
class StubWechatMarkdownRenderer : WechatMarkdownRenderer {
    override fun render(
        markdown: String,
        theme: WechatTheme,
        options: WechatRenderOptions,
    ): WechatRenderResult {
        val fragment = MARKER
        val bytes = fragment.toByteArray(Charsets.UTF_8)
        return WechatRenderResult(
            htmlFragment = fragment,
            images = emptyList(),
            length = WechatHtmlLength(characters = fragment.length, bytes = bytes.size),
        )
    }

    companion object {
        const val MARKER = "<!-- md2wx-stub: uniffi binding not linked -->"
    }
}
