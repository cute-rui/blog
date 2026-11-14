package blog.writer.content

object ContentSchema {
    fun text(): String =
        checkNotNull(ContentSchema::class.java.classLoader.getResource("content-schema.json")) {
            "缺少 content-schema.json"
        }.readText()

    fun requiredKeys(): Set<String> = setOf("title", "description", "pubDate")
}
