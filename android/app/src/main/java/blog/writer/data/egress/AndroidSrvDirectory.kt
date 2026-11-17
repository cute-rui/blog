package blog.writer.data.egress

import android.net.DnsResolver
import blog.writer.copy.Copy
import blog.writer.egress.DEFAULT_SERVICE
import blog.writer.egress.EgressException
import blog.writer.egress.Endpoint
import blog.writer.egress.SrvDirectory
import blog.writer.egress.endpointsFromMessage
import blog.writer.egress.normalizeSrvDomain
import blog.writer.egress.queryName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Looks up `_wechat._tcp.<domain>` with the system resolver and orders the
 * answers with the same priority and weight rules as the Go client.
 */
class AndroidSrvDirectory(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wechat-srv").apply { isDaemon = true }
    },
) : SrvDirectory {
    override fun lookup(domain: String): List<Endpoint> {
        val host = normalizeSrvDomain(domain)
        val name = queryName(DEFAULT_SERVICE, host)
        return endpointsFromMessage(query(name), host)
    }

    private fun query(name: String): ByteArray {
        val latch = CountDownLatch(1)
        val answer = AtomicReference<ByteArray>()
        val failure = AtomicReference<Exception>()
        DnsResolver.getInstance().rawQuery(
            null,
            name,
            DnsResolver.CLASS_IN,
            TYPE_SRV,
            DnsResolver.FLAG_EMPTY,
            executor,
            null,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(response: ByteArray, rcode: Int) {
                    if (rcode != 0) failure.set(EgressException(Copy.format("dns_rcode", "code" to rcode)))
                    else answer.set(response)
                    latch.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    failure.set(error)
                    latch.countDown()
                }
            },
        )
        if (!latch.await(5, TimeUnit.SECONDS)) throw EgressException(Copy.format("dns_timeout", "name" to name))
        failure.get()?.let { throw it }
        return answer.get() ?: throw EgressException(Copy.format("dns_no_response", "name" to name))
    }

    private companion object {
        const val TYPE_SRV = 33
    }
}
