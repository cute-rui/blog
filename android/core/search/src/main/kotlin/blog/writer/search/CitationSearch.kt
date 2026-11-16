package blog.writer.search
import blog.writer.copy.Copy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class CitationFamily {
    ANTHROPIC,
    GEMINI,
    QWEN,
    ZHIPU,
    KIMI,
    ;

    val needsInternationalNetwork: Boolean
        get() = this == ANTHROPIC || this == GEMINI

    companion object {
        fun from(presetId: String, protocol: String): CitationFamily? = when (presetId) {
            "anthropic" -> ANTHROPIC
            "gemini" -> GEMINI
            "qwen" -> QWEN
            "glm" -> ZHIPU
            "kimi" -> KIMI
            else -> when (protocol) {
                "anthropic" -> ANTHROPIC
                "gemini" -> GEMINI
                else -> null
            }
        }
    }
}

data class CitationHit(
    val title: String?,
    val url: String?,
    val snippet: String?,
    val publishedRaw: String?,
    val redirect: Boolean,
)

fun interface RedirectResolver {
    suspend fun resolve(url: String): String?
}

class FollowingRedirects(private val http: OkHttpClient) : RedirectResolver {
    override suspend fun resolve(url: String): String? = runCatching {
        val client = http.newBuilder().followRedirects(true).followSslRedirects(true).build()
        client.newCall(Request.Builder().url(url).get().header("User-Agent", "blog-writer").build()).execute().use { response ->
            response.request.url.toString()
        }
    }.getOrNull()
}

object CitationParsers {
    fun parse(family: CitationFamily, root: JsonObject): List<CitationHit> = when (family) {
        CitationFamily.ANTHROPIC -> anthropic(root)
        CitationFamily.GEMINI -> gemini(root)
        CitationFamily.QWEN -> qwen(root)
        CitationFamily.ZHIPU -> zhipu(root)
        CitationFamily.KIMI -> kimi(root)
    }

    private fun anthropic(root: JsonObject): List<CitationHit> {
        val content = root["content"] as? JsonArray ?: return emptyList()
        val hits = mutableListOf<CitationHit>()
        content.forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            when (block.optText("type")) {
                "web_search_tool_result" -> {
                    val pages = block["content"] as? JsonArray ?: return@forEach
                    pages.forEach { pageElement ->
                        val page = pageElement as? JsonObject ?: return@forEach
                        if (page.optText("type") != "web_search_result") return@forEach
                        hits += CitationHit(
                            title = page.optText("title"),
                            url = page.optText("url"),
                            snippet = null,
                            publishedRaw = page.optText("page_age"),
                            redirect = false,
                        )
                    }
                }
                "text" -> {
                    val citations = block["citations"] as? JsonArray ?: return@forEach
                    citations.forEach { citationElement ->
                        val citation = citationElement as? JsonObject ?: return@forEach
                        if (citation.optText("type") != "web_search_result_location") return@forEach
                        hits += CitationHit(
                            title = citation.optText("title"),
                            url = citation.optText("url"),
                            snippet = citation.optText("cited_text"),
                            publishedRaw = null,
                            redirect = false,
                        )
                    }
                }
            }
        }
        return hits
    }

    private fun gemini(root: JsonObject): List<CitationHit> {
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val metadata = candidate["groundingMetadata"] as? JsonObject ?: return emptyList()
        val chunks = metadata["groundingChunks"] as? JsonArray ?: return emptyList()
        val supports = metadata["groundingSupports"] as? JsonArray ?: buildJsonArray { }
        return chunks.mapIndexedNotNull { index, element ->
            val web = (element as? JsonObject)?.get("web") as? JsonObject ?: return@mapIndexedNotNull null
            val uri = web.optText("uri") ?: return@mapIndexedNotNull null
            val snippet = supports.firstNotNullOfOrNull { support ->
                val obj = support as? JsonObject ?: return@firstNotNullOfOrNull null
                val indices = obj["groundingChunkIndices"] as? JsonArray ?: return@firstNotNullOfOrNull null
                val matches = indices.any { (it as? JsonPrimitive)?.contentOrNull == index.toString() }
                if (!matches) null else (obj["segment"] as? JsonObject)?.optText("text")
            }
            CitationHit(
                title = web.optText("title"),
                url = uri,
                snippet = snippet,
                publishedRaw = null,
                redirect = uri.contains("grounding-api-redirect") || uri.contains("vertexaisearch.cloud.google.com"),
            )
        }
    }

    private fun qwen(root: JsonObject): List<CitationHit> {
        val info = root["search_info"] as? JsonObject
            ?: (root["output"] as? JsonObject)?.get("search_info") as? JsonObject
            ?: return emptyList()
        val results = info["search_results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            CitationHit(
                title = obj.optText("title"),
                url = obj.optText("url"),
                snippet = obj.optText("snippet"),
                publishedRaw = obj.optText("date"),
                redirect = false,
            )
        }
    }

    private fun zhipu(root: JsonObject): List<CitationHit> {
        val top = root["web_search"] as? JsonArray
        val nested = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("web_search") as? JsonArray
        val results = top ?: nested ?: return emptyList()
        return results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            CitationHit(
                title = obj.optText("title"),
                url = obj.optText("link") ?: obj.optText("url"),
                snippet = obj.optText("content"),
                publishedRaw = obj.optText("publish_date"),
                redirect = false,
            )
        }
    }

    // Kimi 没有稳定的引用字段。只认 references / search_results / message.citations。
    // 助手正文和 tool_calls 里的网址一律不用。
    private fun kimi(root: JsonObject): List<CitationHit> {
        val arrays = listOfNotNull(
            root["references"] as? JsonArray,
            root["search_results"] as? JsonArray,
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("citations") as? JsonArray,
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("references") as? JsonArray,
        )
        return arrays.flatten().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.optText("url") ?: obj.optText("link") ?: return@mapNotNull null
            CitationHit(
                title = obj.optText("title"),
                url = url,
                snippet = obj.optText("snippet") ?: obj.optText("content"),
                publishedRaw = obj.optText("date") ?: obj.optText("published_date"),
                redirect = false,
            )
        }
    }
}

class ModelBuiltinSearchAdapter(
    private val http: OkHttpClient,
    private val family: CitationFamily,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxCitations: Int,
    private val profileId: String,
    private val profileName: String,
    private val redirects: RedirectResolver = RedirectResolver { null },
) : SearchAdapter {
    override val id: String = "model-builtin"
    override val capabilities: SearchCapabilities = SearchCapabilities(maxResultsCap = maxCitations.coerceAtLeast(1))

    override suspend fun search(request: SearchRequest): SearchResponse {
        val started = System.nanoTime()
        val (root, raw) = post(request.query)
        val parsed = CitationParsers.parse(family, root)
        var redirectTried = 0
        var redirectOk = 0
        val resolved = parsed.map { hit ->
            if (!hit.redirect || hit.url.isNullOrBlank()) hit
            else {
                redirectTried += 1
                val finalUrl = redirects.resolve(hit.url)
                if (finalUrl.isNullOrBlank()) hit
                else {
                    redirectOk += 1
                    hit.copy(url = finalUrl, redirect = false)
                }
            }
        }
        val hits = resolved.map { hit ->
            RawHit(hit.title, hit.url, hit.snippet, hit.snippet, hit.publishedRaw, null, null, null)
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        val normalized = SearchNormalizer.normalize(
            hits = hits,
            request = request.copy(maxResults = request.maxResults.coerceAtMost(capabilities.maxResultsCap)),
            capabilities = capabilities,
            origin = { rank -> SearchOrigin(profileId, profileName, id, rank) },
            now = java.time.Instant.now(),
            latencyMs = latency,
        )
        val limited = normalized.results.take(maxCitations.coerceAtLeast(1)).mapIndexed { index, result ->
            val short = result.snippet.length < 24 && (result.content?.length ?: 0) < 24
            result.copy(rank = index + 1, incompleteExcerpt = short)
        }
        val warnings = normalized.warnings.toMutableList()
        if (limited.any { it.incompleteExcerpt }) warnings += Copy["excerpt_incomplete"]
        val redirectNote = when {
            redirectTried == 0 -> null
            redirectOk == redirectTried -> Copy["redirect_ok"]
            else -> Copy["redirect_failed"]
        }
        if (redirectNote != null) warnings += redirectNote
        val detail = buildString {
            append(Copy.format("citation_count", "count" to limited.size))
            if (redirectTried > 0) {
                append(Copy.format("redirect_part", "mark" to if (redirectOk == redirectTried) "✓" else "✕"))
            }
        }
        return normalized.copy(results = limited, warnings = warnings, detail = detail, rawBody = raw.take(4_000))
    }

    private fun post(query: String): Pair<JsonObject, String> {
        val call = when (family) {
            CitationFamily.ANTHROPIC -> anthropicRequest(query)
            CitationFamily.GEMINI -> geminiRequest(query)
            CitationFamily.QWEN -> chatRequest(query, enableSearch = true, tools = null)
            CitationFamily.ZHIPU -> chatRequest(query, enableSearch = false, tools = zhipuTools())
            CitationFamily.KIMI -> chatRequest(query, enableSearch = false, tools = kimiTools())
        }
        http.newCall(call).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SearchException(explain(response.code, text), response.code)
            return Json.parseToJsonElement(text).jsonObject to text
        }
    }

    private fun anthropicRequest(query: String): Request {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 1024)
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                            put("max_uses", maxCitations.coerceIn(1, 5))
                        },
                    )
                },
            )
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", query)
                    })
                },
            )
        }
        return Request.Builder()
            .url(anthropicMessagesUrl(baseUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    private fun geminiRequest(query: String): Request {
        val payload = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", query) }) })
                        },
                    )
                },
            )
            put("tools", buildJsonArray { add(buildJsonObject { put("google_search", buildJsonObject { }) }) })
        }
        return Request.Builder()
            .url(geminiGenerateUrl(baseUrl, model))
            .header("x-goog-api-key", apiKey)
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    private fun chatRequest(query: String, enableSearch: Boolean, tools: kotlinx.serialization.json.JsonArray?): Request {
        val payload = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", query)
                    })
                },
            )
            put("stream", false)
            if (enableSearch) put("enable_search", true)
            if (tools != null) put("tools", tools)
        }
        return Request.Builder()
            .url(chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    private fun zhipuTools() = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "web_search")
                put("web_search", buildJsonObject {
                    put("enable", true)
                    put("search_result", true)
                })
            },
        )
    }

    private fun kimiTools() = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "builtin_function")
                put("function", buildJsonObject { put("name", "\$web_search") })
            },
        )
    }

    private fun explain(status: Int, body: String): String = when (status) {
        401, 403 -> Copy["key_error"]
        402 -> Copy["quota_spent"]
        404 -> Copy["address_wrong"]
        429 -> Copy["rate_limited"]
        else -> "HTTP $status ${body.take(180)}"
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun anthropicMessagesUrl(base: String): String {
    val root = base.trimEnd('/')
    val withVersion = if (root.endsWith("/v1")) root else "$root/v1"
    return "$withVersion/messages"
}

internal fun geminiGenerateUrl(base: String, model: String): String {
    val root = base.trimEnd('/')
    val withVersion = if (root.endsWith("/v1beta")) root else "$root/v1beta"
    return "$withVersion/models/$model:generateContent"
}

internal fun chatCompletionsUrl(base: String): String {
    val root = base.trimEnd('/')
    return if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
}
