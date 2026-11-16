package blog.writer.render
import blog.writer.copy.Copy

import uniffi.md2wx.RenderOptions
import uniffi.md2wx.blogTheme
import uniffi.md2wx.render

/**
 * Draft HTML and the public-account preview both come from md2wx.
 * [blogTheme] and [render] are the UniFFI exports. This is not a second renderer.
 */
class Md2wxWechatMarkdownRenderer : WechatMarkdownRenderer {
    @Suppress("UNUSED_PARAMETER")
    override fun render(
        markdown: String,
        theme: WechatTheme,
        options: WechatRenderOptions,
    ): WechatRenderResult {
        try {
            val rendered = render(
                markdown,
                RenderOptions(theme = blogTheme(), imageBaseUrl = options.imageUrlPrefix),
            )
            return WechatRenderResult(
                htmlFragment = rendered.html,
                images = rendered.images.map { WechatImage(it.placeholder, it.path) },
                length = WechatHtmlLength(
                    characters = rendered.charCount.toLong().toInt(),
                    bytes = rendered.byteLen.toLong().toInt(),
                ),
            )
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException(Copy["missing_lib"], error)
        }
    }
}
