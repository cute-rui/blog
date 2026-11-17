package blog.writer.egress
import blog.writer.copy.Copy

/**
 * Calls the fixed-IP gRPC server. The phone never opens api.weixin.qq.com.
 * TLS is the default. Plaintext is refused unless every target is loopback.
 * Every RPC carries the bearer token; AppID and AppSecret are not sent.
 */
open class EgressException(message: String, cause: Throwable? = null) : Exception(message, cause)

class Unavailable(message: String, cause: Throwable? = null) : EgressException(message, cause)

data class EgressCrop(
    val ratio: String,
    val x1: String,
    val y1: String,
    val x2: String,
    val y2: String,
)

data class EgressArticle(
    val title: String,
    val content: String,
    val author: String = "",
    val digest: String = "",
    val contentSourceUrl: String = "",
    val thumbMediaId: String = "",
    val articleType: String = "news",
    val needOpenComment: Int? = null,
    val onlyFansCanComment: Int? = null,
    val coverCrops: List<EgressCrop> = emptyList(),
)

interface WechatEgress {
    fun uploadImage(content: ByteArray, filename: String): String

    fun uploadCover(content: ByteArray, filename: String): String

    fun addDraft(article: EgressArticle): String

    fun updateDraft(mediaId: String, article: EgressArticle)
}

interface EgressCall : WechatEgress, AutoCloseable

fun interface ChannelOpener {
    fun open(endpoint: Endpoint, insecure: Boolean, token: String): EgressCall
}

fun interface SrvDirectory {
    fun lookup(domain: String): List<Endpoint>
}

data class ClientConfig(
    val token: String,
    val address: String = "",
    val domain: String = "",
    val insecure: Boolean = false,
    val lookup: SrvDirectory? = null,
    val opener: ChannelOpener = GrpcChannelOpener,
)

class EgressClient internal constructor(
    private val token: String,
    private val endpoints: List<Endpoint>,
    private val insecure: Boolean,
    private val opener: ChannelOpener,
) : WechatEgress {
    override fun uploadImage(content: ByteArray, filename: String): String =
        call { it.uploadImage(content, filename) }

    override fun uploadCover(content: ByteArray, filename: String): String =
        call { it.uploadCover(content, filename) }

    override fun addDraft(article: EgressArticle): String = call { it.addDraft(article) }

    override fun updateDraft(mediaId: String, article: EgressArticle) {
        call { it.updateDraft(mediaId, article) }
    }

    private fun <T> call(block: (EgressCall) -> T): T {
        if (endpoints.isEmpty()) throw EgressException(Copy["no_srv_targets"])
        val failures = mutableListOf<String>()
        endpoints.forEachIndexed { index, endpoint ->
            val rpc = try {
                opener.open(endpoint, insecure, token)
            } catch (error: Unavailable) {
                failures += "${endpoint.address()}: ${error.message}"
                if (index == endpoints.lastIndex) throw joined(failures, error)
                return@forEachIndexed
            }
            try {
                return block(rpc)
            } catch (error: Unavailable) {
                failures += "${endpoint.address()}: ${error.message}"
                if (index == endpoints.lastIndex) throw joined(failures, error)
            } finally {
                rpc.close()
            }
        }
        throw EgressException(failures.joinToString("; ").ifBlank { Copy["no_srv_targets"] })
    }
}

fun dial(config: ClientConfig): EgressClient {
    val token = normalizeToken(config.token)
    val endpoints = resolveEndpoints(config)
    if (endpoints.isEmpty()) throw EgressException(Copy["no_srv_targets"])
    if (config.insecure) {
        for (endpoint in endpoints) {
            if (!isLoopbackHost(endpoint.host)) {
                throw EgressException(Copy.format("insecure_not_loopback", "host" to endpoint.host))
            }
        }
    }
    return EgressClient(token, endpoints, config.insecure, config.opener)
}

fun normalizeToken(raw: String): String {
    val token = raw.trim()
    if (token.isEmpty()) throw EgressException(Copy["egress_token_required"])
    if (token.length < 16) throw EgressException(Copy["egress_token_too_short"])
    if (token.any { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
        throw EgressException(Copy["egress_token_whitespace"])
    }
    return token
}

fun splitAddress(address: String): Endpoint {
    val host: String
    val portText: String
    if (address.startsWith("[")) {
        val end = address.indexOf(']')
        if (end < 2 || end + 2 > address.length || address[end + 1] != ':') {
            throw EgressException(Copy["address_host_port"])
        }
        host = address.substring(1, end)
        portText = address.substring(end + 2)
    } else {
        val colon = address.lastIndexOf(':')
        if (colon <= 0 || address.indexOf(':') != colon) throw EgressException(Copy["address_host_port"])
        host = address.substring(0, colon)
        portText = address.substring(colon + 1)
    }
    if (host.isEmpty()) throw EgressException(Copy["address_host_port"])
    val port = portText.toIntOrNull()
    if (port == null || port !in 1..65535) throw EgressException(Copy["invalid_port"])
    return Endpoint(host, port)
}

private fun resolveEndpoints(config: ClientConfig): List<Endpoint> {
    if (config.address.isNotBlank()) return listOf(splitAddress(config.address.trim()))
    val domain = config.domain.trim()
    if (domain.isEmpty()) throw EgressException(Copy["srv_host_required"])
    val lookup = config.lookup ?: throw EgressException(Copy["srv_lookup_required"])
    return lookup.lookup(normalizeSrvDomain(domain))
}

private fun joined(failures: List<String>, last: Unavailable): EgressException {
    if (failures.size <= 1) return last
    return EgressException(failures.joinToString("; "), last)
}
