package blog.writer.ai

import kotlinx.coroutines.flow.Flow

enum class LlmProtocol {
    OpenAiCompatible,
    Anthropic,
    Gemini,
}

data class LlmEndpoint(
    val protocol: LlmProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    val stream: Boolean = true,
    val jsonOutput: Boolean = true,
    val connectTimeoutSec: Int = 10,
    val firstTokenTimeoutSec: Int = 30,
    val totalTimeoutSec: Int = 180,
)

data class LlmRequest(
    val model: String,
    val system: String,
    val user: String,
    val temperature: Double,
    val maxOutputTokens: Int,
    val stream: Boolean,
    val jsonObject: Boolean,
)

data class Usage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

sealed interface LlmError {
    val message: String

    data class Http(val status: Int, val explanation: String, val raw: String) : LlmError {
        override val message: String = explanation
    }

    data class Network(override val message: String, val kind: NetKind) : LlmError

    data class Protocol(override val message: String) : LlmError
}

enum class NetKind { Connect, FirstToken, Total, Dns, Tls, Other }

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class Citation(val title: String, val url: String) : LlmEvent
    data class Done(val usage: Usage?, val model: String? = null) : LlmEvent
    data class Failed(val error: LlmError) : LlmEvent
}

interface LlmProvider {
    fun stream(request: LlmRequest): Flow<LlmEvent>

    suspend fun listModels(): List<String>
}

fun explainHttpStatus(status: Int): String = when (status) {
    401 -> "key 错误或已失效"
    403 -> "无权限"
    404 -> "地址或模型名错"
    429 -> "被限流，稍后再试"
    else -> "请求失败"
}

fun shouldRetryLlm(error: Throwable, attempt: Int): Boolean = when {
    attempt >= 1 -> false
    error is HttpCallException -> false
    error is FirstTokenTimeoutException -> false
    error is java.io.IOException -> true
    else -> false
}

class HttpCallException(val status: Int, val raw: String) : RuntimeException(explainHttpStatus(status))

class FirstTokenTimeoutException : java.io.IOException("首字超时")

fun validateBaseUrl(url: String, allowHttp: Boolean): String? {
    val trimmed = url.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
        ?: return "必须以 https:// 开头；局域网网关可在高级选项里允许 http://"
    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    if (host.isNullOrBlank()) return "必须以 https:// 开头；局域网网关可在高级选项里允许 http://"
    return when (scheme) {
        "https" -> null
        "http" -> if (allowHttp) null else "必须以 https:// 开头；局域网网关可在高级选项里允许 http://"
        else -> "必须以 https:// 开头；局域网网关可在高级选项里允许 http://"
    }
}

fun endpointHost(url: String): String {
    val host = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url
    return host.removePrefix("www.")
}

fun joinUrl(base: String, path: String): String {
    val root = base.trim().trimEnd('/')
    val suffix = if (path.startsWith("/")) path else "/$path"
    return root + suffix
}
