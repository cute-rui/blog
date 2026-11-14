package blog.writer.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class McpAuthKind { NONE, BEARER, HEADERS }

data class McpAuth(
    val kind: McpAuthKind,
    val bearer: String = "",
    val headers: Map<String, String> = emptyMap(),
)

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val looksLikeSearch: Boolean,
)

data class McpHandshake(
    val serverName: String,
    val serverVersion: String,
    val protocolVersion: String,
    val sessionId: String?,
    val tools: List<McpTool>,
    val raw: String,
)

fun interface ToolTextOrganizer {
    suspend fun organize(toolText: String): List<RawHit>
}

object McpParams {
    fun autoMap(schema: JsonObject): String {
        val props = schema["properties"] as? JsonObject ?: return ""
        return props.keys.mapNotNull { name ->
            val value = when (name.lowercase()) {
                "query", "q", "keywords" -> "{{query}}"
                "max_results", "maxresults", "count", "limit", "num", "num_results" -> "{{maxResults}}"
                else -> null
            }
            value?.let { "$name=$it" }
        }.joinToString("\n")
    }

    fun arguments(mapText: String, request: SearchRequest): JsonObject {
        val filled = buildJsonObject {
            mapText.lineSequence().forEach { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@forEach
                val key = line.substring(0, index).trim()
                val raw = line.substring(index + 1).trim()
                if (key.isEmpty() || raw.isEmpty()) return@forEach
                put(key, render(raw, request))
            }
        }
        if (filled.isEmpty()) return buildJsonObject { put("query", request.query) }
        return filled
    }

    private fun render(template: String, request: SearchRequest): JsonElement {
        if (template == "{{maxResults}}") return JsonPrimitive(request.maxResults)
        val text = template
            .replace("{{query}}", request.query)
            .replace("{{maxResults}}", request.maxResults.toString())
            .replace("{{language}}", request.language.orEmpty())
            .replace("{{region}}", request.region.orEmpty())
            .replace("{{freshness}}", request.freshness.name)
        return JsonPrimitive(text)
    }
}

object McpResults {
    fun readHits(toolResult: JsonElement, itemsPath: String, fields: Map<String, String>): List<RawHit>? {
        val structured = (toolResult as? JsonObject)?.get("structuredContent")
        if (structured != null) {
            val hits = decode(structured, itemsPath, fields)
            if (hits.isNotEmpty()) return hits
        }
        val text = textOf(toolResult)
        val json = parseJsonOrNull(text)
        if (json != null) {
            val hits = decode(json, itemsPath, fields)
            if (hits.isNotEmpty()) return hits
        }
        return null
    }

    fun textOf(toolResult: JsonElement): String {
        val content = (toolResult as? JsonObject)?.get("content") as? JsonArray ?: return ""
        return content.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj.optText("text")
        }.joinToString("\n")
    }

    fun keepUrlsPresentIn(raw: String, hits: List<RawHit>): List<RawHit> =
        hits.filter { hit ->
            val url = hit.url?.trim().orEmpty()
            url.isNotEmpty() && raw.contains(url)
        }

    fun parseOrganizer(text: String): List<RawHit> {
        val cleaned = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val element = parseJsonOrNull(cleaned) ?: return emptyList()
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["results"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { looseHit(it) }
    }

    fun autoHits(root: JsonElement): List<RawHit> {
        if (root is JsonArray) return root.mapNotNull { looseHit(it) }
        val paths = listOf("$.results", "$.items", "$.data", "$.organic", "$.references", "$.search_result", "$.pageItems")
        for (path in paths) {
            val hits = JsonPath.readArray(root, path).mapNotNull { looseHit(it) }
            if (hits.isNotEmpty()) return hits
        }
        return listOfNotNull(looseHit(root))
    }

    private fun decode(root: JsonElement, itemsPath: String, fields: Map<String, String>): List<RawHit> {
        if (fields.isEmpty()) return autoHits(root)
        return mappedHits(root, itemsPath.ifBlank { "$.results" }, fields)
    }

    private fun mappedHits(root: JsonElement, itemsPath: String, fields: Map<String, String>): List<RawHit> =
        JsonPath.readArray(root, itemsPath).map { item ->
            RawHit(
                title = JsonPath.readText(item, fields["title"]),
                url = JsonPath.readText(item, fields["url"]),
                snippet = JsonPath.readText(item, fields["snippet"]),
                content = JsonPath.readText(item, fields["content"]),
                publishedRaw = JsonPath.readText(item, fields["publishedAt"]),
                siteName = JsonPath.readText(item, fields["siteName"]),
                language = null,
                score = null,
            )
        }

    private fun looseHit(element: JsonElement): RawHit? {
        val obj = element as? JsonObject ?: return null
        val url = obj.optText("url") ?: obj.optText("link") ?: obj.optText("href") ?: return null
        return RawHit(
            title = obj.optText("title") ?: obj.optText("name"),
            url = url,
            snippet = obj.optText("snippet") ?: obj.optText("summary") ?: obj.optText("description"),
            content = obj.optText("content") ?: obj.optText("text") ?: obj.optText("mainText"),
            publishedRaw = obj.optText("publishedAt") ?: obj.optText("date") ?: obj.optText("publishedDate") ?: obj.optText("publish_date"),
            siteName = obj.optText("siteName") ?: obj.optText("website") ?: obj.optText("hostname"),
            language = null,
            score = null,
        )
    }

    private fun parseJsonOrNull(text: String): JsonElement? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
    }
}

class McpClient(private val http: OkHttpClient) {
    fun handshake(serverUrl: String, auth: McpAuth): McpHandshake {
        val init = rpc(serverUrl, auth, null, "2025-03-26", 1, "initialize", initializeParams(), expectBody = true)
        val result = init.result?.jsonObject ?: throw SearchException("initialize 没有返回结果")
        val protocol = result.optText("protocolVersion") ?: "2025-03-26"
        val info = result["serverInfo"] as? JsonObject
        notify(serverUrl, auth, init.sessionId, protocol)
        val listed = rpc(serverUrl, auth, init.sessionId, protocol, 2, "tools/list", buildJsonObject { }, expectBody = true)
        val tools = ((listed.result as? JsonObject)?.get("tools") as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.optText("name") ?: return@mapNotNull null
            val description = obj.optText("description").orEmpty()
            val schema = obj["inputSchema"] as? JsonObject ?: buildJsonObject { }
            McpTool(name, description, schema, looksLikeSearch(name, description))
        }.sortedByDescending { it.looksLikeSearch }
        return McpHandshake(
            serverName = info?.optText("name").orEmpty().ifBlank { "MCP" },
            serverVersion = info?.optText("version").orEmpty(),
            protocolVersion = protocol,
            sessionId = init.sessionId,
            tools = tools,
            raw = listed.raw,
        )
    }

    fun callTool(serverUrl: String, auth: McpAuth, tool: String, arguments: JsonObject): JsonElement {
        val init = rpc(serverUrl, auth, null, "2025-03-26", 1, "initialize", initializeParams(), expectBody = true)
        val protocol = init.result?.jsonObject?.optText("protocolVersion") ?: "2025-03-26"
        notify(serverUrl, auth, init.sessionId, protocol)
        val called = rpc(
            serverUrl,
            auth,
            init.sessionId,
            protocol,
            3,
            "tools/call",
            buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            },
            expectBody = true,
        )
        val result = called.result ?: throw SearchException("没有工具结果")
        val errorFlag = (result as? JsonObject)?.get("isError")?.jsonPrimitive?.contentOrNull
        if (errorFlag == "true") {
            val message = McpResults.textOf(result).ifBlank { "工具返回错误" }
            throw SearchException(message)
        }
        return result
    }

    private fun initializeParams(): JsonObject = buildJsonObject {
        put("protocolVersion", "2025-03-26")
        put("capabilities", buildJsonObject { })
        put(
            "clientInfo",
            buildJsonObject {
                put("name", "blog-writer")
                put("version", "0.3")
            },
        )
    }

    private fun notify(serverUrl: String, auth: McpAuth, sessionId: String?, protocol: String) {
        val payload = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val request = base(serverUrl, auth, sessionId, protocol).post(payload.toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SearchException(explain(response.code, body), response.code)
        }
    }

    private fun rpc(
        serverUrl: String,
        auth: McpAuth,
        sessionId: String?,
        protocol: String,
        id: Int,
        method: String,
        params: JsonObject,
        expectBody: Boolean,
    ): RpcOk {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        val request = base(serverUrl, auth, sessionId, protocol).post(payload.toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val nextSession = response.header("Mcp-Session-Id") ?: sessionId
            if (!response.isSuccessful) throw SearchException(explain(response.code, body), response.code)
            if (!expectBody || body.isBlank()) return RpcOk(null, nextSession, body)
            val parsed = parseRpc(response.header("Content-Type"), body, id)
            return RpcOk(parsed["result"], nextSession, body)
        }
    }

    private fun base(serverUrl: String, auth: McpAuth, sessionId: String?, protocol: String): Request.Builder {
        val builder = Request.Builder()
            .url(serverUrl)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocol)
        if (!sessionId.isNullOrBlank()) builder.header("Mcp-Session-Id", sessionId)
        when (auth.kind) {
            McpAuthKind.NONE -> Unit
            McpAuthKind.BEARER -> if (auth.bearer.isNotBlank()) builder.header("Authorization", "Bearer ${auth.bearer}")
            McpAuthKind.HEADERS -> auth.headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
            }
        }
        return builder
    }

    private data class RpcOk(val result: JsonElement?, val sessionId: String?, val raw: String)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun parseRpc(contentType: String?, body: String, id: Int): JsonObject {
    val trimmed = body.trim()
    val jsonText = if (contentType?.contains("text/event-stream") == true || trimmed.startsWith("event:") || trimmed.startsWith("data:")) {
        extractSse(trimmed, id)
    } else {
        trimmed
    }
    if (jsonText.isBlank()) throw SearchException("空响应")
    val obj = Json.parseToJsonElement(jsonText).jsonObject
    val error = obj["error"] as? JsonObject
    if (error != null) throw SearchException(error.optText("message") ?: "MCP 错误")
    return obj
}

private fun extractSse(body: String, id: Int): String {
    val datas = body.split("\n\n").mapNotNull { event ->
        event.lineSequence()
            .filter { it.startsWith("data:") }
            .joinToString("") { it.removePrefix("data:").trim() }
            .ifBlank { null }
    }
    val matched = datas.firstOrNull { data ->
        runCatching {
            val value = Json.parseToJsonElement(data).jsonObject["id"]
            value?.jsonPrimitive?.contentOrNull == id.toString()
        }.getOrDefault(false)
    }
    return matched ?: datas.lastOrNull().orEmpty()
}

private fun looksLikeSearch(name: String, description: String): Boolean {
    val blob = "$name $description".lowercase()
    return blob.contains("search") || blob.contains("搜索") || blob.contains("检索")
}

private fun explain(status: Int, body: String): String = when (status) {
    401, 403 -> "key 错误"
    402 -> "额度用尽"
    404 -> "地址错"
    429 -> "被限流"
    else -> "HTTP $status ${body.take(180)}"
}

class McpSearchAdapter(
    private val client: McpClient,
    private val serverUrl: String,
    private val auth: McpAuth,
    private val toolName: String,
    private val paramMap: String,
    private val itemsPath: String,
    private val fields: Map<String, String>,
    private val useModelCleanup: Boolean,
    private val organizer: ToolTextOrganizer?,
    private val profileId: String,
    private val profileName: String,
) : SearchAdapter {
    override val id: String = "mcp"
    override val capabilities: SearchCapabilities = SearchCapabilities(maxResultsCap = 10)

    override suspend fun search(request: SearchRequest): SearchResponse {
        if (toolName.isBlank()) throw SearchException("还没有选择工具")
        val arguments = McpParams.arguments(paramMap, request)
        val started = System.nanoTime()
        val toolResult = client.callTool(serverUrl, auth, toolName, arguments)
        val raw = toolResult.toString()
        val structured = McpResults.readHits(toolResult, itemsPath, fields)
        val text = McpResults.textOf(toolResult)
        val dropped = mutableListOf<String>()
        val hits = when {
            structured != null -> structured
            useModelCleanup && organizer != null -> {
                val organized = try {
                    organizer.organize(text.ifBlank { raw })
                } catch (error: SearchException) {
                    throw error
                } catch (error: Exception) {
                    throw SearchException(error.message ?: "模型整理失败")
                }
                val kept = McpResults.keepUrlsPresentIn(text.ifBlank { raw }, organized)
                if (kept.size < organized.size) dropped += "丢弃了未出现在工具文本里的网址"
                kept
            }
            else -> throw SearchException("无法解析工具结果")
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        val normalized = SearchNormalizer.normalize(
            hits = hits,
            request = request,
            capabilities = capabilities,
            origin = { rank -> SearchOrigin(profileId, profileName, id, rank) },
            now = java.time.Instant.now(),
            latencyMs = latency,
        )
        return normalized.copy(warnings = normalized.warnings + dropped, rawBody = raw.take(4_000))
    }
}
