package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.DnsQuestion
import java.io.ByteArrayOutputStream
import java.net.IDN

/**
 * Facts observed in a DNS response. [answerCount] is the number of complete answer records
 * actually parsed; [matchingAnswerCount] additionally requires class IN and an owner reachable
 * from the question through a CNAME chain. TTLs and record data are intentionally not persisted.
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
            recordClass != CLASS_IN
        ) {
            malformed("question does not match")
        }

        val baseRcode = flags and 0x0F
        val truncated = flags and 0x0200 != 0
        if (truncated) {
            // A TC message can end inside a section. Count only complete answer records; the TCP
            // retry performs full validation and supplies the final facts.
            var parsedAnswers = 0
            var answerOffset = offset
            repeat(answerCount) {
                answerOffset = try {
                    parseRecord(bytes, answerOffset).nextOffset
                } catch (error: MalformedDnsResponseException) {
                    return DnsMessage(baseRcode, parsedAnswers, true, matchingAnswerCount = 0)
                }
                parsedAnswers++
            }
            return DnsMessage(baseRcode, parsedAnswers, true, matchingAnswerCount = 0)
        }

        val answers = ArrayList<ParsedRecord>(answerCount)
        repeat(answerCount) {
            val record = parseRecord(bytes, offset)
            answers.add(record)
            offset = record.nextOffset
        }
        repeat(authorityCount) { offset = parseRecord(bytes, offset).nextOffset }

        var extendedRcode = 0
        var optCount = 0
        repeat(additionalCount) {
            val record = parseRecord(bytes, offset)
            if (record.type == TYPE_OPT) {
                optCount++
                if (optCount > 1) malformed("multiple OPT records")
                extendedRcode = ((record.ttl ushr 24) and 0xFF).toInt()
            }
            offset = record.nextOffset
        }
        if (offset != bytes.size) malformed("trailing bytes")

        val allowedOwners = allowedOwners(bytes, answers, asciiName(expected.name).lowercase())
        val matchingAnswerCount = answers.count { record ->
            record.type == expected.recordType &&
                record.recordClass == CLASS_IN &&
                record.name.lowercase() in allowedOwners
        }

        return DnsMessage(
            responseCode = (extendedRcode shl 4) or baseRcode,
            answerCount = answers.size,
            truncated = false,
            matchingAnswerCount = matchingAnswerCount,
        )
    }

    private fun allowedOwners(
        bytes: ByteArray,
        answers: List<ParsedRecord>,
        questionName: String,
    ): Set<String> {
        val cnameTargets = mutableMapOf<String, String>()
        for (record in answers) {
            if (record.type != TYPE_CNAME || record.recordClass != CLASS_IN) continue
            val target = readName(bytes, record.dataOffset)
            if (target.nextOffset != record.dataOffset + record.dataLength) {
                malformed("invalid CNAME data length")
            }
            val owner = record.name.lowercase()
            val targetName = target.value.lowercase()
            val previous = cnameTargets.put(owner, targetName)
            if (previous != null && previous != targetName) malformed("multiple CNAME targets")
        }

        val allowed = mutableSetOf(questionName)
        var current = questionName
        while (true) {
            val target = cnameTargets[current] ?: break
            if (!allowed.add(target)) malformed("CNAME loop")
            current = target
        }
        return allowed
    }

    private fun parseRecord(bytes: ByteArray, start: Int): ParsedRecord {
        val name = readName(bytes, start)
        var offset = name.nextOffset
        if (offset + 10 > bytes.size) malformed("truncated record")
        val type = u16(bytes, offset)
        val recordClass = u16(bytes, offset + 2)
        val ttl = u32(bytes, offset + 4)
        val dataLength = u16(bytes, offset + 8)
        offset += 10
        if (offset + dataLength > bytes.size) malformed("truncated record data")
        if ((type == TYPE_A && dataLength != 4) ||
            (type == TYPE_AAAA && dataLength != 16)
        ) {
            malformed("invalid address record data length")
        }
        return ParsedRecord(offset + dataLength, name.value, type, recordClass, ttl, offset, dataLength)
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

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) malformed("truncated integer")
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun malformed(message: String): Nothing =
        throw MalformedDnsResponseException(message)

    private data class ParsedName(val value: String, val nextOffset: Int)
    private data class ParsedRecord(
        val nextOffset: Int,
        val name: String,
        val type: Int,
        val recordClass: Int,
        val ttl: Long,
        val dataOffset: Int,
        val dataLength: Int,
    )

    private const val CLASS_IN = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val TYPE_OPT = 41
}
