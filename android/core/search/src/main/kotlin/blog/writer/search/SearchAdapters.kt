package blog.writer.search
import blog.writer.copy.Copy

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
    private val labelKey: String,
    val defaultBaseUrl: String,
    val domesticDirect: Boolean,
) {
    BOCHA("search_service_bocha", "https://api.bochaai.com", true),
    ZHIPU("search_service_zhipu", "https://open.bigmodel.cn/api/paas/v4", true),
    QIANFAN("search_service_qianfan", "https://qianfan.baidubce.com", true),
    IQS("search_service_iqs", "https://cloud-iqs.aliyuncs.com", true),
    TAVILY("search_service_tavily", "https://api.tavily.com", false),
    BRAVE("search_service_brave", "https://api.search.brave.com", false),
    EXA("search_service_exa", "https://api.exa.ai", false),
    SERPER("search_service_serper", "https://google.serper.dev", false),
    SEARXNG("search_service_searxng", "", true),
    ;

    val label: String
        get() = blog.writer.copy.Copy[labelKey]
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
        BuiltinSearchService.ZHIPU -> SearchCapabilities(freshness = true, fullContent = true, maxResultsCap = 10)
        BuiltinSearchService.QIANFAN -> SearchCapabilities(fullContent = true, maxResultsCap = 10)
        BuiltinSearchService.IQS -> SearchCapabilities(freshness = true, fullContent = true, maxResultsCap = 10)
        BuiltinSearchService.TAVILY -> SearchCapabilities(includeDomains = true, excludeDomains = true, fullContent = true, maxResultsCap = 20)
        BuiltinSearchService.BRAVE -> SearchCapabilities(freshness = true, maxResultsCap = 20)
        BuiltinSearchService.EXA -> SearchCapabilities(freshness = true, fullContent = true, maxResultsCap = 10)
        BuiltinSearchService.SERPER -> SearchCapabilities(freshness = true, maxResultsCap = 10)
        BuiltinSearchService.SEARXNG -> SearchCapabilities(language = true, maxResultsCap = 20)
    }

    override suspend fun search(request: SearchRequest): SearchResponse {
        val limited = request.copy(maxResults = request.maxResults.coerceAtMost(capabilities.maxResultsCap))
        val started = System.nanoTime()
        val hits = when (service) {
            BuiltinSearchService.BOCHA -> bocha(limited)
            BuiltinSearchService.ZHIPU -> zhipu(limited)
            BuiltinSearchService.QIANFAN -> qianfan(limited)
            BuiltinSearchService.IQS -> iqs(limited)
            BuiltinSearchService.TAVILY -> tavily(limited)
            BuiltinSearchService.BRAVE -> brave(limited)
            BuiltinSearchService.EXA -> exa(limited)
            BuiltinSearchService.SERPER -> serper(limited)
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
        if (baseUrl.isBlank()) throw SearchException(Copy["searx_missing"])
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

    private fun zhipu(request: SearchRequest): List<RawHit> {
        val recency = when (request.freshness) {
            Freshness.DAY -> "oneDay"
            Freshness.WEEK -> "oneWeek"
            Freshness.MONTH -> "oneMonth"
            Freshness.YEAR -> "oneYear"
            Freshness.ANY -> "noLimit"
        }
        val body = """{"search_query":${json(request.query)},"search_engine":"search_std","search_recency_filter":"$recency","count":${request.maxResults},"content_size":"medium"}"""
        val root = jsonPost("${baseUrl.trimEnd('/')}/web_search", body) { header("Authorization", "Bearer $apiKey") }
        return JsonPath.readArray(root, "$.search_result").map { page ->
            val obj = page.jsonObject
            RawHit(
                title = obj.text("title"),
                url = obj.text("link"),
                snippet = obj.text("content"),
                content = obj.text("content"),
                publishedRaw = obj.text("publish_date"),
                siteName = obj.text("media"),
                language = null,
                score = null,
            )
        }
    }

    // 千帆 AI 搜索只认 v2/ai_search/web_search 响应里的 references。
    // 别的产品代如果把引用放在别的字段，这里返回空列表，不从回答正文里抽网址。
    private fun qianfan(request: SearchRequest): List<RawHit> {
        val body = """{"messages":[{"role":"user","content":${json(request.query)}}],"search_source":"baidu_search_v2","resource_type_filter":[{"type":"web","top_k":${request.maxResults}}]}"""
        val root = jsonPost("${baseUrl.trimEnd('/')}/v2/ai_search/web_search", body) { header("Authorization", "Bearer $apiKey") }
        return JsonPath.readArray(root, "$.references").map { page ->
            val obj = page.jsonObject
            RawHit(
                title = obj.text("title"),
                url = obj.text("url"),
                snippet = obj.text("content"),
                content = obj.text("content"),
                publishedRaw = obj.text("date"),
                siteName = obj.text("website"),
                language = null,
                score = null,
            )
        }
    }

    // 阿里云 IQS 只做 Bearer API key 的 search/unified，解析 pageItems。
    // AccessKey 签名没有实现。
    private fun iqs(request: SearchRequest): List<RawHit> {
        val range = when (request.freshness) {
            Freshness.DAY -> "OneDay"
            Freshness.WEEK -> "OneWeek"
            Freshness.MONTH -> "OneMonth"
            Freshness.YEAR -> "OneYear"
            Freshness.ANY -> "NoLimit"
        }
        val body = """{"query":${json(request.query)},"engineType":"Generic","timeRange":"$range","contents":{"mainText":true,"summary":true}}"""
        val root = jsonPost("${baseUrl.trimEnd('/')}/search/unified", body) { header("Authorization", "Bearer $apiKey") }
        return JsonPath.readArray(root, "$.pageItems").map { page ->
            val obj = page.jsonObject
            val summary = obj.text("summary")
            val main = obj.text("mainText")
            RawHit(
                title = obj.text("title"),
                url = obj.text("link"),
                snippet = obj.text("snippet") ?: summary,
                content = main ?: summary,
                publishedRaw = obj.text("publishedTime"),
                siteName = obj.text("hostname"),
                language = null,
                score = obj.text("rerankScore")?.toDoubleOrNull(),
            )
        }
    }

    private fun exa(request: SearchRequest): List<RawHit> {
        val since = publishedSince(request.freshness)
        val start = if (since == null) "" else ""","startPublishedDate":"$since""""
        val body = """{"query":${json(request.query)},"numResults":${request.maxResults}$start,"contents":{"text":true}}"""
        val root = jsonPost("${baseUrl.trimEnd('/')}/search", body) { header("x-api-key", apiKey) }
        return JsonPath.readArray(root, "$.results").map { page ->
            val obj = page.jsonObject
            val text = obj.text("text")
            RawHit(
                title = obj.text("title"),
                url = obj.text("url"),
                snippet = text,
                content = text,
                publishedRaw = obj.text("publishedDate"),
                siteName = null,
                language = null,
                score = obj.text("score")?.toDoubleOrNull(),
            )
        }
    }

    private fun serper(request: SearchRequest): List<RawHit> {
        val tbs = when (request.freshness) {
            Freshness.DAY -> "qdr:d"
            Freshness.WEEK -> "qdr:w"
            Freshness.MONTH -> "qdr:m"
            Freshness.YEAR -> "qdr:y"
            Freshness.ANY -> null
        }
        val tbsField = if (tbs == null) "" else ""","tbs":"$tbs""""
        val body = """{"q":${json(request.query)},"num":${request.maxResults}$tbsField}"""
        val root = jsonPost("${baseUrl.trimEnd('/')}/search", body) { header("X-API-KEY", apiKey) }
        return JsonPath.readArray(root, "$.organic").map { page ->
            val obj = page.jsonObject
            RawHit(
                title = obj.text("title"),
                url = obj.text("link"),
                snippet = obj.text("snippet"),
                content = null,
                publishedRaw = obj.text("date"),
                siteName = null,
                language = null,
                score = null,
            )
        }
    }

    private fun publishedSince(freshness: Freshness): String? {
        val days = when (freshness) {
            Freshness.DAY -> 1L
            Freshness.WEEK -> 7L
            Freshness.MONTH -> 30L
            Freshness.YEAR -> 365L
            Freshness.ANY -> return null
        }
        return java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS).toString()
    }

    private fun jsonPost(url: String, body: String, headers: Request.Builder.() -> Unit): JsonObject {
        val builder = Request.Builder().url(url).post(body.toRequestBody(JSON))
        builder.headers()
        return execute(builder.build())
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
        401, 403 -> Copy["key_error"]
        402 -> Copy["quota_spent"]
        404 -> Copy["address_wrong"]
        429 -> Copy["rate_limited"]
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
        var rawBody = ""
        val root = http.newCall(call).execute().use { response ->
            val text = response.body?.string().orEmpty()
            rawBody = text
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
        ).copy(rawBody = rawBody.take(4_000))
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

fun hostOfUrl(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()
