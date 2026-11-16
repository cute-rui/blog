package blog.writer.ai
import blog.writer.copy.Copy

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
                close(t ?: IOException(Copy["connection_closed"]))
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
    is FirstTokenTimeoutException -> LlmError.Network(Copy["first_token_timeout"], NetKind.FirstToken)
    is UnknownHostException -> LlmError.Network(Copy["dns_failed"], NetKind.Dns)
    is SSLException -> LlmError.Network(Copy["tls_failed"], NetKind.Tls)
    is SocketTimeoutException -> LlmError.Network(Copy["connect_timeout"], NetKind.Connect)
    is IOException -> LlmError.Network(message ?: Copy["network_error"], NetKind.Other)
    else -> LlmError.Protocol(message ?: Copy["read_model_failed"])
}

internal fun Request.Builder.withExtra(headers: Map<String, String>): Request.Builder {
    headers.forEach { (name, value) -> header(name, value) }
    return this
}
