package blog.writer.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

enum class BuiltinSearchService(
    val label: String,
    val defaultBaseUrl: String,
    val domesticDirect: Boolean,
) {
    BOCHA("博查 Web Search", "https://api.bochaai.com", true),
    TAVILY("Tavily", "https://api.tavily.com", false),
    BRAVE("Brave Search API", "https://api.search.brave.com", false),
    SEARXNG("SearXNG", "", true),
}

class BuiltinSearchAdapter(
    private val http: OkHttpClient,
    private val service: BuiltinSearchService,
    private val apiKey: String,
    private val baseUrl: String,
    private val profileId: String,
    private val profileName: String,
) : SearchAdapter {
    override val id: String = service.name.lowercase()
    override val capabilities: SearchCapabilities = when (service) {
        BuiltinSearchService.BOCHA -> SearchCapabilities(freshness = true, fullContent = true, maxResultsCap = 10)
        BuiltinSearchService.TAVILY -> SearchCapabilities(includeDomains = true, excludeDomains = true, fullContent = true, maxResultsCap = 20)
        BuiltinSearchService.BRAVE -> SearchCapabilities(freshness = true, maxResultsCap = 20)
        BuiltinSearchService.SEARXNG -> SearchCapabilities(language = true, maxResultsCap = 20)
    }

    override suspend fun search(request: SearchRequest): SearchResponse {
        val limited = request.copy(maxResults = request.maxResults.coerceAtMost(capabilities.maxResultsCap))
        val started = System.nanoTime()
        val hits = when (service) {
            BuiltinSearchService.BOCHA -> bocha(limited)
            BuiltinSearchService.TAVILY -> tavily(limited)
            BuiltinSearchService.BRAVE -> brave(limited)
            BuiltinSearchService.SEARXNG -> searx(limited)
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        return SearchNormalizer.normalize(
            hits = hits,
            request = limited,
            capabilities = capabilities,
            origin = { rank -> SearchOrigin(profileId, profileName, id, rank) },
            now = java.time.Instant.now(),
            latencyMs = latency,
        )
    }

    private fun bocha(request: SearchRequest): List<RawHit> {
        val freshness = when (request.freshness) {
            Freshness.DAY -> "oneDay"
            Freshness.WEEK -> "oneWeek"
            Freshness.MONTH -> "oneMonth"
            Freshness.YEAR -> "oneYear"
            Freshness.ANY -> "noLimit"
        }
        val body = """{"query":${json(request.query)},"freshness":"$freshness","summary":true,"count":${request.maxResults}}"""
        val root = post("$baseUrl/v1/web-search", body, bearer = true)
        val pages = JsonPath.readArray(root, "$.data.webPages.value")
        return pages.map { page ->
            val obj = page.jsonObject
            RawHit(
                title = obj.text("name"),
                url = obj.text("url"),
                snippet = obj.text("snippet"),
                content = obj.text("summary"),
                publishedRaw = obj.text("datePublished"),
                siteName = obj.text("siteName"),
                language = null,
                score = null,
            )
        }
    }

    private fun tavily(request: SearchRequest): List<RawHit> {
        val include = request.includeDomains.joinToString(",") { "\"$it\"" }
        val exclude = request.excludeDomains.joinToString(",") { "\"$it\"" }
        val body = """{"api_key":${json(apiKey)},"query":${json(request.query)},"max_results":${request.maxResults},"search_depth":"basic","include_domains":[$include],"exclude_domains":[$exclude]}"""
        val root = post("$baseUrl/search", body, bearer = false)
        val pages = JsonPath.readArray(root, "$.results")
        return pages.map { page ->
            val obj = page.jsonObject
            RawHit(
                title = obj.text("title"),
                url = obj.text("url"),
                snippet = obj.text("content"),
                content = obj.text("content"),
                publishedRaw = obj.text("published_date"),
                siteName = null,
                language = null,
                score = obj.text("score")?.toDoubleOrNull(),
            )
        }
    }

    private fun brave(request: SearchRequest): List<RawHit> {
        val freshness = when (request.freshness) {
            Freshness.DAY -> "pd"
            Freshness.WEEK -> "pw"
            Freshness.MONTH -> "pm"
            Freshness.YEAR -> "py"
            Freshness.ANY -> null
        }
        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append("/res/v1/web/search?q=")
            append(java.net.URLEncoder.encode(request.query, Charsets.UTF_8))
            append("&count=").append(request.maxResults)
            if (freshness != null) append("&freshness=").append(freshness)
        }
        val root = get(url) { header("X-Subscription-Token", apiKey).header("Accept", "application/json") }
        val pages = JsonPath.readArray(root, "$.web.results")
        return pages.map { page ->
            val obj = page.jsonObject
            RawHit(
                title = obj.text("title"),
                url = obj.text("url"),
                snippet = obj.text("description"),
                content = null,
                publishedRaw = obj.text("page_age") ?: obj.text("age"),
                siteName = (obj["profile"] as? JsonObject)?.text("name"),
                language = null,
                score = null,
            )
        }
    }

    private fun searx(request: SearchRequest): List<RawHit> {
        if (baseUrl.isBlank()) throw SearchException("还没有填写 SearXNG 实例地址")
        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append("/search?format=json&q=")
            append(java.net.URLEncoder.encode(request.query, Charsets.UTF_8))
            if (!request.language.isNullOrBlank()) {
                append("&language=").append(java.net.URLEncoder.encode(request.language, Charsets.UTF_8))
            }
        }
        val root = get(url) { header("Accept", "application/json") }
        val pages = JsonPath.readArray(root, "$.results")
        return pages.map { page ->
            val obj = page.jsonObject
            RawHit(
                title = obj.text("title"),
                url = obj.text("url"),
                snippet = obj.text("content"),
                content = null,
                publishedRaw = obj.text("publishedDate"),
                siteName = obj.text("engine"),
                language = null,
                score = obj.text("score")?.toDoubleOrNull(),
            )
        }
    }

    private fun post(url: String, body: String, bearer: Boolean): JsonObject {
        val builder = Request.Builder().url(url).post(body.toRequestBody(JSON))
        if (bearer && apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return execute(builder.build())
    }

    private fun get(url: String, headers: Request.Builder.() -> Request.Builder): JsonObject {
        return execute(Request.Builder().url(url).headers().get().build())
    }

    private fun execute(request: Request): JsonObject {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SearchException(explain(response.code, text), response.code)
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun explain(status: Int, body: String): String = when (status) {
        401, 403 -> "key 错误"
        402 -> "额度用尽"
        404 -> "地址错"
        429 -> "被限流"
        else -> "HTTP $status ${body.take(180)}"
    }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class CustomHttpSpec(
    val method: String,
    val urlTemplate: String,
    val headers: Map<String, String>,
    val bodyTemplate: String,
    val errorPath: String?,
    val itemsPath: String,
    val fields: Map<String, String>,
    val capabilities: SearchCapabilities,
    val secrets: Map<String, String>,
)

class CustomHttpAdapter(
    private val http: OkHttpClient,
    private val spec: CustomHttpSpec,
    private val profileId: String,
    private val profileName: String,
) : SearchAdapter {
    override val id: String = "custom-http"
    override val capabilities: SearchCapabilities = spec.capabilities

    override suspend fun search(request: SearchRequest): SearchResponse {
        val context = TemplateContext(request, spec.secrets)
        val url = HttpTemplate.render(spec.urlTemplate, context, TemplateMode.URL)
        val headers = spec.headers.mapValues { (_, value) -> HttpTemplate.render(value, context, TemplateMode.JSON) }
        val body = if (spec.bodyTemplate.isBlank()) null else HttpTemplate.render(spec.bodyTemplate, context, TemplateMode.JSON)
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
        val call = if (spec.method.equals("GET", ignoreCase = true) || body == null) {
            builder.get().build()
        } else {
            builder.method(spec.method.uppercase(), body.toRequestBody(JSON)).build()
        }
        val started = System.nanoTime()
        val root = http.newCall(call).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SearchException(text.take(180), response.code)
            Json.parseToJsonElement(text)
        }
        spec.errorPath?.let { path ->
            val message = JsonPath.readText(root, path)
            if (!message.isNullOrBlank()) throw SearchException(message)
        }
        val hits = JsonPath.readArray(root, spec.itemsPath).map { item ->
            RawHit(
                title = JsonPath.readText(item, spec.fields["title"]),
                url = JsonPath.readText(item, spec.fields["url"]),
                snippet = JsonPath.readText(item, spec.fields["snippet"]),
                content = JsonPath.readText(item, spec.fields["content"]),
                publishedRaw = JsonPath.readText(item, spec.fields["publishedAt"]),
                siteName = JsonPath.readText(item, spec.fields["siteName"]),
                language = null,
                score = null,
            )
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        return SearchNormalizer.normalize(
            hits,
            request,
            capabilities,
            origin = { rank -> SearchOrigin(profileId, profileName, id, rank) },
            now = java.time.Instant.now(),
            latencyMs = latency,
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

fun hostOfUrl(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()
