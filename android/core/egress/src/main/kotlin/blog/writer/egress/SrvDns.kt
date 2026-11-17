package blog.writer.egress

import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom

/**
 * SRV lookup rules shared with wechat-egress/internal/srvdns.
 *
 * The phone asks for `_wechat._tcp.<domain>`. Lower priority is tried first.
 * Weight 0 is tried only after the positive weights in that priority. A target
 * without a trailing dot is joined to the lookup domain. An IP is never joined.
 */
const val DEFAULT_SERVICE = "_wechat._tcp"

data class SrvRecord(
    val priority: Int,
    val weight: Int,
    val port: Int,
    val target: String,
)

data class Endpoint(
    val host: String,
    val port: Int,
) {
    fun address(): String {
        val formatted = if (host.contains(':')) "[$host]" else host
        return "$formatted:$port"
    }
}

fun queryName(service: String, domain: String): String {
    var prefix = service.trim().removeSuffix(".")
    val host = domain.trim().removeSuffix(".")
    if (prefix.isEmpty()) prefix = DEFAULT_SERVICE
    validateService(prefix)
    validateDomain(host)
    return "$prefix.$host"
}

fun normalizeSrvDomain(raw: String): String {
    var domain = raw.trim()
    if (domain.contains("://") || domain.contains("/") || domain.any { it.isWhitespace() }) {
        throw EgressException("SRV 域名只填域，例如 example.com")
    }
    domain = domain.removeSuffix(".")
    val prefix = "$DEFAULT_SERVICE."
    if (domain.startsWith(prefix)) domain = domain.removePrefix(prefix).removeSuffix(".")
    return try {
        queryName(DEFAULT_SERVICE, domain).removePrefix(prefix)
    } catch (error: IllegalArgumentException) {
        throw EgressException("SRV 域名不对", error)
    }
}

fun resolveTarget(target: String, domain: String): String {
    val trimmed = target.trim()
    if (trimmed.isEmpty() || trimmed == ".") throw IllegalArgumentException("srv target is unavailable")
    val absolute = trimmed.endsWith(".")
    var host = trimmed.removeSuffix(".")
    if (host.isEmpty()) throw IllegalArgumentException("srv target is unavailable")
    numericIp(host)?.let { return it }
    if (!absolute) {
        val origin = domain.trim().removeSuffix(".")
        if (origin.isEmpty()) throw IllegalArgumentException("relative SRV target \"$trimmed\" needs a lookup domain")
        validateDomain(origin)
        host = "$host.$origin"
    }
    validateDomain(host)
    return host
}

fun orderRecords(records: List<SrvRecord>, domain: String, intn: (Int) -> Int): List<Endpoint> {
    val usable = records.filter { it.target != "." && it.port != 0 }
    if (usable.isEmpty()) throw IllegalArgumentException("no usable SRV targets")
    val sorted = usable.sortedWith(compareBy({ it.priority }, { it.weight }, { it.target }, { it.port })).toMutableList()
    var index = 0
    while (index < sorted.size) {
        var end = index + 1
        while (end < sorted.size && sorted[index].priority == sorted[end].priority) end++
        shuffleByWeight(sorted, index, end, intn)
        index = end
    }
    return sorted.map { record -> Endpoint(resolveTarget(record.target, domain), record.port) }
}

fun endpointsFromMessage(message: ByteArray, domain: String, intn: (Int) -> Int = ::secureIntn): List<Endpoint> {
    val records = parseMessage(message)
    if (records.isEmpty()) throw IllegalArgumentException("no SRV records")
    return orderRecords(records, domain, intn)
}

fun secureIntn(n: Int): Int {
    if (n <= 1) return 0
    return SecureRandom().nextInt(n)
}

fun isLoopbackHost(host: String): Boolean {
    val name = host.removeSuffix(".")
    if (name.equals("localhost", ignoreCase = true)) return true
    val ip = numericIp(name) ?: return false
    return try {
        InetAddress.getByName(ip).isLoopbackAddress
    } catch (_: Exception) {
        false
    }
}

private fun shuffleByWeight(addrs: MutableList<SrvRecord>, start: Int, end: Int, intn: (Int) -> Int) {
    var sum = 0
    for (index in start until end) sum += addrs[index].weight
    var left = start
    while (sum > 0 && end - left > 1) {
        var pick = intn(sum)
        if (pick < 0 || pick >= sum) pick = 0
        var running = 0
        var picked = left
        for (index in left until end) {
            running += addrs[index].weight
            if (running > pick) {
                picked = index
                break
            }
        }
        if (picked != left) {
            val swapped = addrs[left]
            addrs[left] = addrs[picked]
            addrs[picked] = swapped
        }
        val weight = addrs[left].weight
        if (weight <= 0) return
        sum -= weight
        left++
    }
}

private fun validateService(service: String) {
    val labels = service.split('.')
    if (labels.size != 2) throw IllegalArgumentException("SRV name \"$service\" must be two labels such as $DEFAULT_SERVICE")
    for (label in labels) {
        if (label.length < 2 || label[0] != '_') throw IllegalArgumentException("SRV label \"$label\" must start with _")
        try {
            checkLabel(label.substring(1))
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("SRV label \"$label\"", error)
        }
    }
}

private fun validateDomain(domain: String) {
    if (domain.isEmpty()) throw IllegalArgumentException("SRV host is required")
    if (domain.length > 253) throw IllegalArgumentException("SRV host \"$domain\" is too long")
    for (label in domain.split('.')) {
        try {
            checkLabel(label)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("SRV host \"$domain\"", error)
        }
    }
}

private fun checkLabel(label: String) {
    if (label.isEmpty() || label.length > 63) throw IllegalArgumentException("invalid label \"$label\"")
    if (label.first() == '-' || label.last() == '-') throw IllegalArgumentException("invalid label \"$label\"")
    if (label.any { !isNameChar(it) }) throw IllegalArgumentException("invalid label \"$label\"")
}

private fun isNameChar(char: Char): Boolean =
    char == '-' || char == '_' || char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

private fun numericIp(host: String): String? {
    if (host.contains('%')) return null
    val v4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    if (v4.matches(host)) {
        val addr = InetAddress.getByName(host)
        if (addr !is Inet4Address) return null
        return addr.hostAddress
    }
    if (':' in host && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) {
        val addr = InetAddress.getByName(host)
        if (addr.address.size != 16) return null
        return ipv6String(addr.address)
    }
    return null
}

private fun ipv6String(bytes: ByteArray): String {
    val parts = IntArray(8) { index ->
        ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
    }
    var bestStart = -1
    var bestLen = 0
    var run = -1
    for (index in parts.indices) {
        if (parts[index] == 0) {
            if (run < 0) run = index
            val len = index - run + 1
            if (len > bestLen) {
                bestStart = run
                bestLen = len
            }
        } else {
            run = -1
        }
    }
    if (bestLen < 2) bestStart = -1
    val out = StringBuilder()
    var index = 0
    while (index < parts.size) {
        if (index == bestStart) {
            out.append("::")
            index += bestLen
            continue
        }
        if (out.isNotEmpty() && !out.endsWith(':')) out.append(':')
        out.append(parts[index].toString(16))
        index++
    }
    if (out.isEmpty()) return "::"
    return out.toString()
}
