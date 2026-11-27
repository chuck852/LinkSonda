package io.github.chuck852.linksonda.network

internal fun dnsResponse(
    query: ByteArray,
    rcode: Int = 0,
    truncated: Boolean = false,
    answerCount: Int = 0,
    answerBytes: ByteArray = ByteArray(0),
): ByteArray {
    val end = dnsQuestionEnd(query)
    val result = query.copyOf(end)
    result[2] = (0x81 or (if (truncated) 0x02 else 0)).toByte()
    result[3] = (0x80 or (rcode and 0x0F)).toByte()
    result[6] = (answerCount ushr 8).toByte()
    result[7] = answerCount.toByte()
    return result + answerBytes
}

internal fun dnsQuestionEnd(query: ByteArray): Int {
    var offset = 12
    while (query[offset].toInt() != 0) {
        offset += 1 + query[offset].toInt()
    }
    return offset + 5
}

internal fun aRecord(address: String = "127.0.0.1"): ByteArray =
    byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) +
        address.split('.').map { it.toInt().toByte() }.toByteArray()

internal fun aaaaRecord(address: String = "::1"): ByteArray {
    val bytes = java.net.InetAddress.getByName(address).address
    return byteArrayOf(0xC0.toByte(), 0x0C, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16) + bytes
}

internal fun cnameRecord(target: String = "alias.test"): ByteArray {
    val name = mutableListOf<Byte>()
    target.split('.').forEach { label ->
        name.add(label.length.toByte())
        label.toByteArray(Charsets.US_ASCII).forEach(name::add)
    }
    name.add(0.toByte())
    return byteArrayOf(0xC0.toByte(), 0x0C, 0, 5, 0, 1, 0, 0, 0, 60, 0, name.size.toByte()) +
        name.toByteArray()
}
