package blog.writer.sync
import blog.writer.copy.Copy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WechatIndex(
    val wechatMode: String,
    val pages: Map<String, String>,
)

@Serializable
data class WechatMetaFile(
    val title: String = "",
    val author: String = "",
    val digest: String = "",
    val sourceUrl: String? = null,
    val coverUrl: String? = null,
    val openComment: Boolean? = null,
    val characters: Int? = null,
    val contentHash: String? = null,
)

sealed interface CopyDecision {
    data object Ready : CopyDecision
    data class Waiting(val reason: String) : CopyDecision
}

object WechatCopy {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseIndex(source: String): WechatIndex {
        val root = json.parseToJsonElement(source).jsonObject
        val mode = root.string("wechatMode") ?: "html"
        val posts = root["posts"] as? JsonObject ?: JsonObject(emptyMap())
        val pages = linkedMapOf<String, String>()
        for ((slug, value) in posts) {
            val url = when (value) {
                is JsonPrimitive -> value.contentOrNull
                is JsonObject -> value.string("pageUrl") ?: value.string("url")
                else -> null
            }
            if (!url.isNullOrBlank()) pages[slug] = url
        }
        return WechatIndex(wechatMode = mode, pages = pages)
    }

    fun parseMeta(source: String): WechatMetaFile = json.decodeFromString(source)

    fun contentUrl(pageUrl: String): String = pageUrl.trimEnd('/') + "/content.html"

    fun metaUrl(pageUrl: String): String = pageUrl.trimEnd('/') + "/meta.json"

    fun decide(localHash: String?, pageHash: String?): CopyDecision = when {
        localHash.isNullOrBlank() -> CopyDecision.Waiting(Copy["copy_hash_missing"])
        pageHash.isNullOrBlank() -> CopyDecision.Waiting(Copy["copy_page_hash_missing"])
        localHash != pageHash -> CopyDecision.Waiting(Copy["copy_page_stale"])
        else -> CopyDecision.Ready
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
}
