package blog.writer.egress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SrvTest {
    @Test
    fun queryNameAndRelativeTarget() {
        assertEquals("_wechat._tcp.example.com", queryName("", "example.com"))
        assertEquals("_wechat._tcp.example.com", queryName("_wechat._tcp", "example.com."))
        assertEquals("example.com", normalizeSrvDomain(" example.com. "))
        assertEquals("example.com", normalizeSrvDomain("_wechat._tcp.example.com"))
        assertEquals("egress.example.com", resolveTarget("egress", "example.com"))
        assertEquals("egress.dc1.example.com", resolveTarget("egress.dc1", "example.com"))
        assertEquals("egress.example.com", resolveTarget("egress.example.com.", "other.net"))
        assertEquals("vps.other.net", resolveTarget("vps.other.net.", "example.com"))
        assertEquals("127.0.0.1", resolveTarget("127.0.0.1", "example.com"))
        assertEquals("127.0.0.1", resolveTarget("127.0.0.1.", "example.com"))
        assertEquals("::1", resolveTarget("::1", "example.com"))
        assertFails { resolveTarget(".", "example.com") }
        assertFails { resolveTarget("egress", "") }
        assertFails { normalizeSrvDomain("https://example.com") }
        assertFails { queryName("_wechat", "example.com") }
    }

    @Test
    fun priorityWeightAndRelativeOrder() {
        var calls = 0
        val ordered = orderRecords(
            listOf(
                SrvRecord(5, 100, 9, "late.example.com."),
                SrvRecord(0, 1, 8443, "a.example.com."),
                SrvRecord(0, 3, 8444, "b.example.com."),
            ),
            "example.com",
        ) { n ->
            calls++
            assertEquals(4, n)
            1
        }
        assertEquals(1, calls)
        assertEquals(
            listOf("b.example.com:8444", "a.example.com:8443", "late.example.com:9"),
            ordered.map { it.address() },
        )

        val zeroAfter = orderRecords(
            listOf(
                SrvRecord(0, 0, 1, "z.example.com."),
                SrvRecord(0, 5, 2, "p.example.com."),
            ),
            "example.com",
        ) { n ->
            assertEquals(5, n)
            0
        }
        assertEquals(listOf("p.example.com:2", "z.example.com:1"), zeroAfter.map { it.address() })

        var shuffled = false
        val priority = orderRecords(
            listOf(
                SrvRecord(1, 100, 2, "second.example.com."),
                SrvRecord(0, 0, 1, "first.example.com."),
            ),
            "example.com",
        ) {
            shuffled = true
            0
        }
        assertTrue(!shuffled)
        assertEquals(listOf("first.example.com:1", "second.example.com:2"), priority.map { it.address() })

        val relative = orderRecords(
            listOf(SrvRecord(0, 1, 8443, "egress")),
            "example.com",
        ) { 0 }
        assertEquals(listOf("egress.example.com:8443"), relative.map { it.address() })
        assertFails { orderRecords(listOf(SrvRecord(0, 1, 9, ".")), "example.com") { 0 } }
    }

    @Test
    fun parsesCompressedSrvAndSkipsOtherTypes() {
        val qname = encodeName("_wechat._tcp.example.com")
        val exampleAt = 25
        assertEquals(7, qname[exampleAt - 12].toInt())
        val message = ArrayList<Byte>()
        message += bytes(0x00, 0x2a, 0x81, 0x80)
        message += bytes(0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00)
        message += qname
        message += bytes(0x00, 33, 0x00, 1)
        message += bytes(0xC0, 0x0C)
        message += bytes(0x00, 1, 0x00, 1)
        message += bytes(0, 0, 0, 30)
        message += bytes(0x00, 4, 127, 0, 0, 1)
        message += bytes(0xC0, 0x0C)
        message += bytes(0x00, 33, 0x00, 1)
        message += bytes(0, 0, 0, 30)
        val target = byteArrayOf(6, 'e'.code.toByte(), 'g'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 's'.code.toByte(), 0xC0.toByte(), exampleAt.toByte())
        val rdlen = 6 + target.size
        message += bytes(rdlen shr 8, rdlen)
        message += bytes(0, 1, 0, 5, 0x20, 0xFB)
        message += target
        val records = parseMessage(message.toByteArray())
        assertEquals(1, records.size)
        assertEquals(1, records[0].priority)
        assertEquals(5, records[0].weight)
        assertEquals(8443, records[0].port)
        assertEquals("egress.example.com.", records[0].target)
        val endpoints = endpointsFromMessage(message.toByteArray(), "example.com") { 0 }
        assertEquals("egress.example.com:8443", endpoints.single().address())
    }

    @Test
    fun rejectsMalformedDns() {
        assertFails { parseMessage(byteArrayOf(0x00, 0x01)) }
        val query = ByteArray(12)
        query[2] = 0x01
        assertFails { parseMessage(query) }
        val nx = ByteArray(12)
        nx[2] = 0x81.toByte()
        nx[3] = 0x03
        try {
            parseMessage(nx)
            fail("nxdomain was accepted")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("nxdomain"))
        }
    }

    @Test
    fun loopbackIsOnlyNumericOrLocalhost() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("127.0.0.1."))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(!isLoopbackHost("example.com"))
        assertTrue(!isLoopbackHost("1.2.3.4"))
        assertTrue(!isLoopbackHost("weixin.qq.com"))
    }

    private fun encodeName(name: String): ByteArray {
        val out = ArrayList<Byte>()
        for (label in name.split('.')) {
            out += label.length.toByte()
            label.forEach { out += it.code.toByte() }
        }
        out += 0
        return out.toByteArray()
    }

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private operator fun ArrayList<Byte>.plusAssign(value: ByteArray) {
        value.forEach { add(it) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("应该失败")
        } catch (_: IllegalArgumentException) {
        } catch (_: EgressException) {
        }
    }
}
