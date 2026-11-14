package blog.writer.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal fun OkHttpClient.forEndpoint(endpoint: LlmEndpoint): OkHttpClient {
    return newBuilder()
        .connectTimeout(endpoint.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(endpoint.totalTimeoutSec.toLong(), TimeUnit.SECONDS)
        .callTimeout(endpoint.totalTimeoutSec.toLong(), TimeUnit.SECONDS)
        .build()
}

internal fun OkHttpClient.sse(request: Request, firstTokenMs: Long): Flow<SseEvent> = callbackFlow {
    val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
    val factory = EventSources.createFactory(this@sse)
    lateinit var source: EventSource
    source = factory.newEventSource(
        request,
        object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (!opened.isCompleted) opened.complete(Unit)
                trySend(SseEvent(type.orEmpty(), data))
            }

            override fun onClosed(eventSource: EventSource) {
                if (!opened.isCompleted) opened.complete(Unit)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val status = response?.code
                if (status != null && status !in 200..299) {
                    val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    close(HttpCallException(status, raw))
                    return
                }
                close(t ?: IOException("连接中断"))
            }
        },
    )
    val watchdog = launch {
        val arrived = withTimeoutOrNull(firstTokenMs) { opened.await() }
        if (arrived == null) {
            source.cancel()
            close(FirstTokenTimeoutException())
        }
    }
    awaitClose {
        watchdog.cancel()
        source.cancel()
    }
}

internal fun Flow<LlmEvent>.retryingNetwork(): Flow<LlmEvent> = flow {
    var attempt = 0
    while (true) {
        var emitted = false
        try {
            collect { event ->
                emitted = true
                emit(event)
            }
            return@flow
        } catch (error: Throwable) {
            if (emitted || !shouldRetryLlm(error, attempt)) {
                emit(LlmEvent.Failed(error.toLlmError()))
                return@flow
            }
            attempt++
            delay(200)
        }
    }
}

internal fun Throwable.toLlmError(): LlmError = when (this) {
    is HttpCallException -> LlmError.Http(status, explainHttpStatus(status), raw.take(2_000))
    is FirstTokenTimeoutException -> LlmError.Network("首字超时", NetKind.FirstToken)
    is UnknownHostException -> LlmError.Network("域名无法解析", NetKind.Dns)
    is SSLException -> LlmError.Network("证书校验失败", NetKind.Tls)
    is SocketTimeoutException -> LlmError.Network("连接超时", NetKind.Connect)
    is IOException -> LlmError.Network(message ?: "网络错误", NetKind.Other)
    else -> LlmError.Protocol(message ?: "无法读取模型返回")
}

internal fun Request.Builder.withExtra(headers: Map<String, String>): Request.Builder {
    headers.forEach { (name, value) -> header(name, value) }
    return this
}
