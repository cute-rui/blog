package blog.writer.ai
import blog.writer.copy.Copy

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
    401 -> Copy["http_key_invalid"]
    403 -> Copy["http_forbidden"]
    404 -> Copy["http_not_found_model"]
    429 -> Copy["http_rate_limited"]
    else -> Copy["http_failed"]
}

fun shouldRetryLlm(error: Throwable, attempt: Int): Boolean = when {
    attempt >= 1 -> false
    error is HttpCallException -> false
    error is FirstTokenTimeoutException -> false
    error is java.io.IOException -> true
    else -> false
}

class HttpCallException(val status: Int, val raw: String) : RuntimeException(explainHttpStatus(status))

class FirstTokenTimeoutException : java.io.IOException(Copy["first_token_timeout"])

fun validateBaseUrl(url: String, allowHttp: Boolean): String? {
    val trimmed = url.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
        ?: return Copy["base_url_https"]
    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    if (host.isNullOrBlank()) return Copy["base_url_https"]
    return when (scheme) {
        "https" -> null
        "http" -> if (allowHttp) null else Copy["base_url_https"]
        else -> Copy["base_url_https"]
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
