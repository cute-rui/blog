package blog.writer.ai

object Prompts {
    val version: Int = load("prompts/version.txt").trim().toInt()
    val system: String = load("prompts/system.txt").trim()
    private val continueTemplate: String = load("prompts/continue-user.txt")

    fun continueUser(
        meta: String,
        outline: String,
        before: String,
        after: String,
        extra: String?,
        jsonMode: Boolean,
    ): String {
        val extraBlock = if (extra.isNullOrBlank()) "" else "补充要求：${extra.trim()}\n"
        val body = continueTemplate
            .replace("{{meta}}", meta)
            .replace("{{outline}}", outline)
            .replace("{{before}}", before)
            .replace("{{after}}", after)
            .replace("{{extra}}", extraBlock)
        return if (jsonMode) body else body + "\n只输出 JSON，不要用代码块包裹。"
    }

    private fun load(path: String): String =
        checkNotNull(Prompts::class.java.classLoader.getResource(path)) { "缺少 $path" }.readText()
}
