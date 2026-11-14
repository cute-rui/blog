package blog.writer.render

/**
 * The shape `md2wx` will expose through UniFFI.
 *
 * Markdown, a theme, and options go in. An HTML fragment, an image list, and a length go out.
 * The function is pure: no files and no network. Uploading images and writing the copy page
 * stay outside this interface.
 */
fun interface WechatMarkdownRenderer {
    fun render(
        markdown: String,
        theme: WechatTheme,
        options: WechatRenderOptions,
    ): WechatRenderResult
}

data class WechatTheme(
    val accent: String,
    val ink: String,
    val paper: String,
    val muted: String,
    val fontFamily: String = "Noto Serif SC",
)

data class WechatRenderOptions(
    val imageUrlPrefix: String = "",
)

data class WechatImage(
    val placeholder: String,
    val path: String,
)

data class WechatHtmlLength(
    val characters: Int,
    val bytes: Int,
)

data class WechatRenderResult(
    val htmlFragment: String,
    val images: List<WechatImage>,
    val length: WechatHtmlLength,
)
