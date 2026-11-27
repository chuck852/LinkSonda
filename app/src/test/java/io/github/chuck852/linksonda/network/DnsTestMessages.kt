package io.github.chuck852.linksonda.network

import java.io.ByteArrayOutputStream
import java.net.InetAddress

internal fun dnsResponse(
    query: ByteArray,
    rcode: Int = 0,
    truncated: Boolean = false,
    answerCount: Int = 0,
    answerBytes: ByteArray = ByteArray(0),
    authorityCount: Int = 0,
    authorityBytes: ByteArray = ByteArray(0),
    additionalCount: Int = 0,
    additionalBytes: ByteArray = ByteArray(0),
): ByteArray {
    val end = dnsQuestionEnd(query)
    val result = query.copyOf(end)
    result[2] = (0x81 or (if (truncated) 0x02 else 0)).toByte()
    result[3] = (0x80 or (rcode and 0x0F)).toByte()
    result[6] = (answerCount ushr 8).toByte()
    result[7] = answerCount.toByte()
    result[8] = (authorityCount ushr 8).toByte()
    result[9] = authorityCount.toByte()
    result[10] = (additionalCount ushr 8).toByte()
    result[11] = additionalCount.toByte()
    return result + answerBytes + authorityBytes + additionalBytes
}

internal fun dnsQuestionEnd(query: ByteArray): Int {
    var offset = 12
    while (query[offset].toInt() != 0) {
        offset += 1 + query[offset].toInt()
    }
    return offset + 5
}

internal fun dnsName(name: String): ByteArray {
    val output = ByteArrayOutputStream()
    name.trim().removeSuffix(".").split('.').forEach { label ->
        output.write(label.length)
        output.write(label.toByteArray(Charsets.US_ASCII))
    }
    output.write(0)
    return output.toByteArray()
}

internal fun rawRecord(
    owner: ByteArray,
    type: Int,
    recordClass: Int = 1,
    ttl: Int = 60,
    data: ByteArray = ByteArray(0),
): ByteArray {
    val output = ByteArrayOutputStream()
    output.write(owner)
    writeU16(output, type)
    writeU16(output, recordClass)
    output.write((ttl ushr 24) and 0xFF)
    output.write((ttl ushr 16) and 0xFF)
    output.write((ttl ushr 8) and 0xFF)
    output.write(ttl and 0xFF)
    writeU16(output, data.size)
    output.write(data)
    return output.toByteArray()
}

internal fun aRecord(
    address: String = "127.0.0.1",
    ownerName: String? = null,
    recordClass: Int = 1,
): ByteArray = rawRecord(
    owner = if (ownerName == null) byteArrayOf(0xC0.toByte(), 0x0C) else dnsName(ownerName),
    type = 1,
    recordClass = recordClass,
    data = address.split('.').map { it.toInt().toByte() }.toByteArray(),
)

internal fun aaaaRecord(
    address: String = "::1",
    ownerName: String? = null,
    recordClass: Int = 1,
): ByteArray = rawRecord(
    owner = if (ownerName == null) byteArrayOf(0xC0.toByte(), 0x0C) else dnsName(ownerName),
    type = 28,
    recordClass = recordClass,
    data = InetAddress.getByName(address).address,
)

internal fun cnameRecord(
    target: String = "alias.test",
    ownerName: String? = null,
    recordClass: Int = 1,
): ByteArray = rawRecord(
    owner = if (ownerName == null) byteArrayOf(0xC0.toByte(), 0x0C) else dnsName(ownerName),
    type = 5,
    recordClass = recordClass,
    data = dnsName(target),
)

internal fun optRecord(
    extendedRcode: Int = 0,
    version: Int = 0,
    payloadSize: Int = 1232,
): ByteArray = rawRecord(
    owner = byteArrayOf(0),
    type = 41,
    recordClass = payloadSize,
    ttl = (extendedRcode shl 24) or (version shl 16),
)

private fun writeU16(output: ByteArrayOutputStream, value: Int) {
    output.write((value ushr 8) and 0xFF)
    output.write(value and 0xFF)
}
