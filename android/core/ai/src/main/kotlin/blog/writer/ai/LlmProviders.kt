package blog.writer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LlmClients(private val http: OkHttpClient) {
    fun create(endpoint: LlmEndpoint): LlmProvider = when (endpoint.protocol) {
        LlmProtocol.OpenAiCompatible -> OpenAiCompatibleProvider(http, endpoint)
        LlmProtocol.Anthropic -> AnthropicProvider(http, endpoint)
        LlmProtocol.Gemini -> GeminiProvider(http, endpoint)
    }
}

class OpenAiCompatibleProvider(
    http: OkHttpClient,
    private val endpoint: LlmEndpoint,
) : JsonLlmProvider(http, endpoint) {
    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        val payload = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            put("max_tokens", request.maxOutputTokens)
            put("stream", request.stream)
            if (request.jsonObject) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", request.user)
                    })
                },
            )
        }
        val call = baseRequest(joinUrl(endpoint.baseUrl, "/chat/completions"))
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(call, request.stream, ::mapOpenAi)
    }

    override suspend fun listModels(): List<String> {
        val call = baseRequest(joinUrl(endpoint.baseUrl, "/models"))
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .get()
            .build()
        return runCatching {
            val text = client().newCall(call).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string().orEmpty()
            }
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { item ->
                item.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrDefault(emptyList())
    }
}

class AnthropicProvider(
    http: OkHttpClient,
    private val endpoint: LlmEndpoint,
) : JsonLlmProvider(http, endpoint) {
    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        val payload = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxOutputTokens)
            put("temperature", request.temperature)
            put("stream", request.stream)
            put("system", request.system)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", request.user)
                    })
                },
            )
        }
        val call = baseRequest(joinUrl(endpoint.baseUrl, "/v1/messages"))
            .header("x-api-key", endpoint.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(call, request.stream, ::mapAnthropic)
    }

    override suspend fun listModels(): List<String> {
        val call = baseRequest(joinUrl(endpoint.baseUrl, "/v1/models"))
            .header("x-api-key", endpoint.apiKey)
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()
        return runCatching {
            val text = client().newCall(call).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string().orEmpty()
            }
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { item ->
                item.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrDefault(emptyList())
    }
}

class GeminiProvider(
    http: OkHttpClient,
    private val endpoint: LlmEndpoint,
) : JsonLlmProvider(http, endpoint) {
    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        val method = if (request.stream) "streamGenerateContent?alt=sse" else "generateContent"
        val url = joinUrl(endpoint.baseUrl, "/v1beta/models/${request.model}:$method")
        val payload = buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", request.system) }) })
                },
            )
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", request.user) }) })
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", request.temperature)
                    put("maxOutputTokens", request.maxOutputTokens)
                    if (request.jsonObject) put("responseMimeType", "application/json")
                },
            )
        }
        val call = baseRequest(url)
            .header("x-goog-api-key", endpoint.apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(call, request.stream, ::mapGemini)
    }

    override suspend fun listModels(): List<String> {
        val call = baseRequest(joinUrl(endpoint.baseUrl, "/v1beta/models"))
            .header("x-goog-api-key", endpoint.apiKey)
            .get()
            .build()
        return runCatching {
            val text = client().newCall(call).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string().orEmpty()
            }
            Json.parseToJsonElement(text).jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { item ->
                item.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')
            }
        }.getOrDefault(emptyList())
    }
}

private fun Flow<LlmEvent>.closing(box: ModelBox): Flow<LlmEvent> = flow {
    var terminal = false
    collect { event ->
        if (event is LlmEvent.Done || event is LlmEvent.Failed) terminal = true
        emit(event)
    }
    if (!terminal) emit(LlmEvent.Done(usage = null, model = box.model))
}

abstract class JsonLlmProvider(
    private val http: OkHttpClient,
    private val endpoint: LlmEndpoint,
) : LlmProvider {
    protected fun client(): OkHttpClient = http.forEndpoint(endpoint)

    protected fun baseRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream, application/json")
            .header("User-Agent", "blog-writer")
            .withExtra(endpoint.extraHeaders)
    }

    protected fun execute(
        request: Request,
        stream: Boolean,
        map: (SseEvent, ModelBox) -> LlmEvent?,
    ): Flow<LlmEvent> {
        val box = ModelBox()
        val raw = if (stream) {
            client().sse(request, endpoint.firstTokenTimeoutSec * 1000L).mapNotNull { event ->
                map(event, box)
            }
        } else {
            flow {
                val response = client().newCall(request).execute()
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) throw HttpCallException(it.code, text)
                    val mapped = map(SseEvent("message", text), box)
                    if (mapped != null) emit(mapped)
                    if (mapped !is LlmEvent.Done && mapped !is LlmEvent.Failed) {
                        emit(LlmEvent.Done(usage = null, model = box.model))
                    }
                }
            }
        }
        return raw.closing(box).retryingNetwork().flowOn(Dispatchers.IO)
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class ModelBox(var model: String? = null)

fun mapOpenAi(event: SseEvent, box: ModelBox): LlmEvent? {
    if (event.data == "[DONE]") return LlmEvent.Done(usage = null, model = box.model)
    val obj = runCatching { Json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: return null
    obj["error"]?.jsonObject?.let { error ->
        val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "模型返回错误"
        return LlmEvent.Failed(LlmError.Protocol(message))
    }
    obj["model"]?.jsonPrimitive?.contentOrNull?.let { box.model = it }
    val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
    val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
    if (!text.isNullOrEmpty()) return LlmEvent.TextDelta(text)
    if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull != null && text.isNullOrEmpty()) {
        return null
    }
    return null
}

fun mapAnthropic(event: SseEvent, box: ModelBox): LlmEvent? {
    val obj = runCatching { Json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: return null
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: event.type
    obj["model"]?.jsonPrimitive?.contentOrNull?.let { box.model = it }
    obj["message"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull?.let { box.model = it }
    return when (type) {
        "content_block_delta" -> {
            val text = obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            if (text.isNullOrEmpty()) null else LlmEvent.TextDelta(text)
        }
        "message_stop" -> LlmEvent.Done(usage = null, model = box.model)
        "error" -> LlmEvent.Failed(
            LlmError.Protocol(obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "模型返回错误"),
        )
        else -> {
            val text = obj["content"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrEmpty()) LlmEvent.TextDelta(text) else null
        }
    }
}

fun mapGemini(event: SseEvent, box: ModelBox): LlmEvent? {
    val obj = runCatching { Json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: return null
    obj["modelVersion"]?.jsonPrimitive?.contentOrNull?.let { box.model = it }
    obj["error"]?.jsonObject?.let { error ->
        return LlmEvent.Failed(LlmError.Protocol(error["message"]?.jsonPrimitive?.contentOrNull ?: "模型返回错误"))
    }
    val parts = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("content")?.jsonObject?.get("parts") as? JsonArray
    val text = parts?.joinToString("") { part ->
        part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.orEmpty()
    val finished = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("finishReason")?.jsonPrimitive?.contentOrNull
    return when {
        text.isNotEmpty() && finished != null -> LlmEvent.TextDelta(text)
        text.isNotEmpty() -> LlmEvent.TextDelta(text)
        finished != null -> LlmEvent.Done(usage = null, model = box.model)
        else -> null
    }
}
