package blog.writer.content

import blog.writer.copy.Copy

object ContentSchema {
    fun text(): String =
        checkNotNull(ContentSchema::class.java.classLoader.getResource("content-schema.json")) {
            Copy.format("schema_missing", "name" to "content-schema.json")
        }.readText()

    fun requiredKeys(): Set<String> = setOf("title", "description", "pubDate")
}
