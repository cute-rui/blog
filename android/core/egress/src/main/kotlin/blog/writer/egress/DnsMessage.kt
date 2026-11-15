package blog.writer.egress

/**
 * Reads SRV answers from a DNS response, including compression pointers.
 * A target of "." means the service is not available. Other record types are skipped.
 */
fun parseMessage(message: ByteArray): List<SrvRecord> {
    if (message.size < 12) throw IllegalArgumentException("dns message is too short")
    val flags = u16(message, 2)
    if (flags and 0x8000 == 0) throw IllegalArgumentException("dns message is not a response")
    val opcode = (flags shr 11) and 0xF
    if (opcode != 0) throw IllegalArgumentException("dns opcode $opcode")
    val rcode = flags and 0xF
    if (rcode != 0) throw IllegalArgumentException(rcodeError(rcode))
    val questions = u16(message, 4)
    val answers = u16(message, 6)
    if (answers > 64) throw IllegalArgumentException("dns response has $answers answers")
    var offset = 12
    repeat(questions) {
        val next = readName(message, offset).end
        if (next + 4 > message.size) throw IllegalArgumentException("dns question is truncated")
        offset = next + 4
    }
    val records = mutableListOf<SrvRecord>()
    repeat(answers) {
        val owner = readName(message, offset)
        offset = owner.end
        if (offset + 10 > message.size) throw IllegalArgumentException("dns answer is truncated")
        val type = u16(message, offset)
        val clazz = u16(message, offset + 2)
        val rdlen = u16(message, offset + 8)
        val rdata = offset + 10
        if (rdata + rdlen > message.size) throw IllegalArgumentException("dns rdata is truncated")
        if (type == 33 && clazz == 1) records += parseSrv(message, rdata, rdlen)
        offset = rdata + rdlen
    }
    return records
}

private fun parseSrv(message: ByteArray, start: Int, rdlen: Int): SrvRecord {
    if (rdlen < 7) throw IllegalArgumentException("srv rdata is too short")
    val name = readName(message, start + 6)
    if (name.end != start + rdlen) throw IllegalArgumentException("srv target length does not match rdlength")
    return SrvRecord(
        priority = u16(message, start),
        weight = u16(message, start + 2),
        port = u16(message, start + 4),
        target = name.text,
    )
}

private class DnsName(val text: String, val end: Int)

private fun readName(message: ByteArray, start: Int): DnsName {
    if (start < 0 || start >= message.size) throw IllegalArgumentException("dns name is out of range")
    val text = StringBuilder()
    val seen = ArrayList<Int>(8)
    var jumped = false
    var end = start
    var offset = start
    repeat(128) {
        if (offset >= message.size) throw IllegalArgumentException("dns name is truncated")
        if (offset in seen) throw IllegalArgumentException("dns name pointer loop")
        seen += offset
        val length = message[offset].toInt() and 0xff
        when (length and 0xC0) {
            0x00 -> {
                if (length == 0) {
                    if (!jumped) end = offset + 1
                    if (text.isEmpty()) return DnsName(".", end)
                    return DnsName(text.toString(), end)
                }
                if (offset + 1 + length > message.size) throw IllegalArgumentException("dns label is truncated")
                val label = message.copyOfRange(offset + 1, offset + 1 + length).toString(Charsets.US_ASCII)
                checkDnsLabel(label)
                text.append(label).append('.')
                offset += 1 + length
                if (!jumped) end = offset
            }
            0xC0 -> {
                if (offset + 1 >= message.size) throw IllegalArgumentException("dns pointer is truncated")
                val pointer = ((length and 0x3F) shl 8) or (message[offset + 1].toInt() and 0xff)
                if (pointer >= message.size) throw IllegalArgumentException("dns pointer is out of range")
                if (!jumped) {
                    end = offset + 2
                    jumped = true
                }
                offset = pointer
            }
            else -> throw IllegalArgumentException("dns name has a reserved label type")
        }
    }
    throw IllegalArgumentException("dns name is too long")
}

private fun checkDnsLabel(label: String) {
    if (label.isEmpty() || label.length > 63) throw IllegalArgumentException("invalid label \"$label\"")
    if (label.first() == '-' || label.last() == '-') throw IllegalArgumentException("invalid label \"$label\"")
    if (label.any { char ->
            char != '-' && char != '_' && char !in 'a'..'z' && char !in 'A'..'Z' && char !in '0'..'9'
        }
    ) {
        throw IllegalArgumentException("invalid label \"$label\"")
    }
}

private fun rcodeError(code: Int): String = when (code) {
    1 -> "dns rcode formerr"
    2 -> "dns rcode servfail"
    3 -> "dns rcode nxdomain"
    5 -> "dns rcode refused"
    else -> "dns rcode $code"
}

private fun u16(message: ByteArray, offset: Int): Int =
    ((message[offset].toInt() and 0xff) shl 8) or (message[offset + 1].toInt() and 0xff)
