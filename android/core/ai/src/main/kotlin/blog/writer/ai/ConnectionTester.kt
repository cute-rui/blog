package blog.writer.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ConnectionReport(
    val ok: Boolean,
    val httpStatus: Int?,
    val firstTokenMs: Long?,
    val totalMs: Long?,
    val model: String?,
    val modelMismatch: Boolean,
    val streamVerified: Boolean,
    val jsonVerified: Boolean,
    val explanation: String?,
    val rawError: String?,
    val kind: NetKind?,
)

class ConnectionTester {
    suspend fun test(provider: LlmProvider, endpoint: LlmEndpoint): ConnectionReport {
        val request = LlmRequest(
            model = endpoint.model,
            system = "你是连接测试。只输出要求的 JSON。",
            user = "{\"ok\":true}",
            temperature = 0.0,
            maxOutputTokens = 16,
            stream = endpoint.stream,
            jsonObject = endpoint.jsonOutput,
        )
        return test(provider, request, endpoint.model, endpoint.stream, endpoint.jsonOutput)
    }

    suspend fun test(
        provider: LlmProvider,
        request: LlmRequest,
        expectedModel: String,
        expectStream: Boolean,
        expectJson: Boolean,
    ): ConnectionReport {
        val started = System.nanoTime()
        var first: Long? = null
        var model: String? = null
        val text = StringBuilder()
        var failed: LlmError? = null
        try {
            provider.stream(request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        if (first == null) first = System.nanoTime()
                        text.append(event.text)
                    }
                    is LlmEvent.Done -> {
                        if (first == null) first = System.nanoTime()
                        if (event.model != null) model = event.model
                    }
                    is LlmEvent.Citation -> Unit
                    is LlmEvent.Failed -> failed = event.error
                }
            }
        } catch (error: Throwable) {
            failed = error.toLlmError()
        }
        val total = (System.nanoTime() - started) / 1_000_000
        val firstMs = first?.let { (it - started) / 1_000_000 }
        val error = failed
        if (error != null) {
            return ConnectionReport(
                ok = false,
                httpStatus = (error as? LlmError.Http)?.status,
                firstTokenMs = firstMs,
                totalMs = total,
                model = model,
                modelMismatch = false,
                streamVerified = false,
                jsonVerified = false,
                explanation = error.message,
                rawError = (error as? LlmError.Http)?.raw,
                kind = (error as? LlmError.Network)?.kind,
            )
        }
        val jsonOk = !expectJson || text.contains("ok")
        return ConnectionReport(
            ok = jsonOk,
            httpStatus = 200,
            firstTokenMs = firstMs,
            totalMs = total,
            model = model,
            modelMismatch = model != null && expectedModel.isNotBlank() && model != expectedModel,
            streamVerified = expectStream && firstMs != null,
            jsonVerified = jsonOk,
            explanation = if (jsonOk) null else "返回的内容不是 JSON",
            rawError = if (jsonOk) null else text.toString(),
            kind = null,
        )
    }
}

class FakeLlmProvider(
    private val events: List<LlmEvent>,
    private val models: List<String> = emptyList(),
) : LlmProvider {
    var requests: List<LlmRequest> = emptyList()
        private set

    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        requests = requests + request
        return flow {
            events.forEach { emit(it) }
        }
    }

    override suspend fun listModels(): List<String> = models
}
