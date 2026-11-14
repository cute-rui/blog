package blog.writer.ai

object Prompts {
    val version: Int = load("prompts/version.txt").trim().toInt()
    val system: String = load("prompts/system.txt").trim()
    private val continueTemplate: String = load("prompts/continue-user.txt")
    private val fillTemplate: String = load("prompts/fill-user.txt")
    private val extractTemplate: String = load("prompts/fact-extract.txt")
    private val judgeTemplate: String = load("prompts/fact-judge.txt")

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

    fun fillUser(meta: String, outline: String, opening: String, ending: String, words: Int, extra: String?): String {
        val body = fillTemplate
            .replace("{{meta}}", meta)
            .replace("{{outline}}", outline)
            .replace("{{opening}}", opening)
            .replace("{{ending}}", ending)
            .replace("{{words}}", words.toString())
        return if (extra.isNullOrBlank()) body else body + "\n补充要求：${extra.trim()}"
    }

    fun extractClaims(title: String, text: String, jsonMode: Boolean): String {
        val body = extractTemplate.replace("{{title}}", title).replace("{{text}}", text)
        return if (jsonMode) body else body + "\n只输出 JSON，不要用代码块包裹。"
    }

    fun judgeClaim(claim: String, results: String, jsonMode: Boolean): String {
        val body = judgeTemplate.replace("{{claim}}", claim).replace("{{results}}", results)
        return if (jsonMode) body else body + "\n只输出 JSON，不要用代码块包裹。"
    }

    private fun load(path: String): String =
        checkNotNull(Prompts::class.java.classLoader.getResource(path)) { "缺少 $path" }.readText()
}
