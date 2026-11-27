package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.DnsQuestion
import java.io.ByteArrayOutputStream
import java.net.IDN

/**
 * Facts observed in a DNS response header. [matchingAnswerCount] counts answer records of the
 * requested type; TTLs and record data are intentionally not persisted.
 */
internal data class DnsMessage(
    val responseCode: Int,
    val answerCount: Int,
    val truncated: Boolean,
    val matchingAnswerCount: Int,
)

internal class MalformedDnsResponseException(message: String) : Exception(message)

internal object DnsWire {
    fun query(id: Int, question: DnsQuestion): ByteArray {
        require(id in 0..0xFFFF)
        val name = encodeName(question.name)
        val output = ByteArrayOutputStream(12 + name.size + 4)
        writeU16(output, id)
        writeU16(output, 0x0100) // RD
        writeU16(output, 1) // QDCOUNT
        writeU16(output, 0)
        writeU16(output, 0)
        writeU16(output, 0)
        output.write(name)
        writeU16(output, question.recordType)
        writeU16(output, 1) // IN
        return output.toByteArray()
    }

    fun parseResponse(
        bytes: ByteArray,
        expectedId: Int,
        expected: DnsQuestion,
    ): DnsMessage {
        if (bytes.size < 12) malformed("truncated header")
        val id = u16(bytes, 0)
        if (id != expectedId) malformed("transaction ID mismatch")
        val flags = u16(bytes, 2)
        if (flags and 0x8000 == 0) malformed("not a response")
        if ((flags ushr 11) and 0x0F != 0) malformed("unsupported opcode")
        val questionCount = u16(bytes, 4)
        if (questionCount != 1) malformed("expected exactly one question")
        val answerCount = u16(bytes, 6)
        val authorityCount = u16(bytes, 8)
        val additionalCount = u16(bytes, 10)

        var offset = 12
        val name = readName(bytes, offset)
        offset = name.nextOffset
        if (offset + 4 > bytes.size) malformed("truncated question")
        val type = u16(bytes, offset)
        val recordClass = u16(bytes, offset + 2)
        offset += 4
        if (!name.value.equals(asciiName(expected.name), ignoreCase = true) ||
            type != expected.recordType ||
            recordClass != 1
        ) {
            malformed("question does not match")
        }

        val responseCode = flags and 0x0F
        val truncated = flags and 0x0200 != 0
        if (truncated) {
            // The truncated message may end inside a section. The TCP retry performs full parsing.
            return DnsMessage(responseCode, answerCount, truncated, matchingAnswerCount = 0)
        }

        var matchingAnswerCount = 0
        repeat(answerCount) {
            val record = skipRecord(bytes, offset)
            offset = record.nextOffset
            if (record.type == expected.recordType) matchingAnswerCount++
        }
        repeat(authorityCount) { offset = skipRecord(bytes, offset).nextOffset }
        repeat(additionalCount) { offset = skipRecord(bytes, offset).nextOffset }
        if (offset != bytes.size) malformed("trailing bytes")

        return DnsMessage(responseCode, answerCount, truncated, matchingAnswerCount)
    }

    private fun skipRecord(bytes: ByteArray, start: Int): ParsedRecord {
        var offset = readName(bytes, start).nextOffset
        if (offset + 10 > bytes.size) malformed("truncated record")
        val type = u16(bytes, offset)
        val dataLength = u16(bytes, offset + 8)
        offset += 10
        if (offset + dataLength > bytes.size) malformed("truncated record data")
        return ParsedRecord(offset + dataLength, type)
    }

    private fun readName(bytes: ByteArray, start: Int): ParsedName {
        val output = StringBuilder()
        val visited = mutableSetOf<Int>()
        var position = start
        var nextOffset = -1
        var wireLength = 1
        while (true) {
            if (position >= bytes.size) malformed("truncated name")
            val length = bytes[position].toInt() and 0xFF
            if (length == 0) {
                if (nextOffset < 0) nextOffset = position + 1
                return ParsedName(output.toString(), nextOffset)
            }
            if (length and 0xC0 == 0xC0) {
                if (position + 1 >= bytes.size) malformed("truncated name pointer")
                val pointer = ((length and 0x3F) shl 8) or (bytes[position + 1].toInt() and 0xFF)
                if (nextOffset < 0) nextOffset = position + 2
                if (!visited.add(pointer)) malformed("name compression loop")
                position = pointer
                continue
            }
            if (length and 0xC0 != 0) malformed("invalid name label")
            if (length > 63 || position + 1 + length > bytes.size) malformed("truncated name label")
            wireLength += 1 + length
            if (wireLength > 255) malformed("name is too long")
            if (output.isNotEmpty()) output.append('.')
            output.append(String(bytes, position + 1, length, Charsets.US_ASCII))
            position += 1 + length
        }
    }

    private fun encodeName(value: String): ByteArray {
        val name = asciiName(value)
        val output = ByteArrayOutputStream()
        if (name.isNotEmpty()) {
            name.split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                require(bytes.size in 1..63) { "DNS label must contain 1..63 ASCII characters" }
                output.write(bytes.size)
                output.write(bytes)
            }
        }
        output.write(0)
        require(output.size() <= 255) { "DNS name must not exceed 255 bytes" }
        return output.toByteArray()
    }

    private fun asciiName(value: String): String =
        IDN.toASCII(value.trim().removeSuffix("."), IDN.ALLOW_UNASSIGNED)

    private fun writeU16(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) malformed("truncated integer")
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun malformed(message: String): Nothing =
        throw MalformedDnsResponseException(message)

    private data class ParsedName(val value: String, val nextOffset: Int)
    private data class ParsedRecord(val nextOffset: Int, val type: Int)
}
