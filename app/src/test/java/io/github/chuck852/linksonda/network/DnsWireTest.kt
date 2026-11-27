package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.DnsQuestion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireTest {
    private val question = DnsQuestion("www.example.test", 1)

    @Test
    fun queryEncodesHeaderAndQuestion() {
        val query = DnsWire.query(id = 0x1234, question = question)
        val expectedName = byteArrayOf(
            3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            4, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            0,
        )

        assertEquals(0x12, query[0].toInt())
        assertEquals(0x34, query[1].toInt())
        assertEquals(0x01, query[2].toInt())
        assertEquals(0x00, query[3].toInt())
        assertEquals(0, query[4].toInt())
        assertEquals(1, query[5].toInt())
        assertEquals(0, query[6].toInt())
        assertEquals(0, query[7].toInt())
        assertArrayEquals(expectedName, query.copyOfRange(12, 12 + expectedName.size))
        assertEquals(0, query[12 + expectedName.size].toInt())
        assertEquals(1, query[12 + expectedName.size + 1].toInt())
        assertEquals(0, query[12 + expectedName.size + 2].toInt())
        assertEquals(1, query[12 + expectedName.size + 3].toInt())
    }

    @Test
    fun parseReportsRcodeAnswerCountAndTruncation() {
        val query = DnsWire.query(id = 7, question = question)
        val message = DnsWire.parseResponse(
            bytes = response(query, answerCount = 1, answerBytes = aAnswer()),
            expectedId = 7,
            expected = question,
        )

        assertEquals(0, message.responseCode)
        assertEquals(1, message.answerCount)
        assertEquals(1, message.matchingAnswerCount)
        assertEquals(false, message.truncated)
    }

    @Test
    fun parseCountsOnlyAnswersMatchingTheQuestionType() {
        val query = DnsWire.query(id = 16, question = question)
        val message = DnsWire.parseResponse(
            bytes = response(query, answerCount = 1, answerBytes = cnameRecord()),
            expectedId = 16,
            expected = question,
        )

        assertEquals(1, message.answerCount)
        assertEquals(0, message.matchingAnswerCount)
    }

    @Test
    fun parseReportsTruncationWhenAnAnswerSectionIsIncomplete() {
        val query = DnsWire.query(id = 17, question = question)
        val partialRecord = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
        val message = DnsWire.parseResponse(
            bytes = response(
                query,
                truncated = true,
                answerCount = 1,
                answerBytes = partialRecord,
            ),
            expectedId = 17,
            expected = question,
        )

        assertEquals(true, message.truncated)
        assertEquals(0, message.answerCount)
        assertEquals(0, message.matchingAnswerCount)
    }

    @Test
    fun parseReportsNxdomainAndServfail() {
        val query = DnsWire.query(id = 8, question = question)
        val nxdomain = DnsWire.parseResponse(response(query, rcode = 3), 8, question)
        val servfail = DnsWire.parseResponse(response(query, rcode = 2), 8, question)

        assertEquals(3, nxdomain.responseCode)
        assertEquals(2, servfail.responseCode)
    }

    @Test
    fun parseReportsTruncation() {
        val query = DnsWire.query(id = 9, question = question)
        val message = DnsWire.parseResponse(response(query, truncated = true), 9, question)

        assertEquals(true, message.truncated)
    }

    @Test
    fun parseRejectsWrongTransactionId() {
        val query = DnsWire.query(id = 10, question = question)
        val bytes = response(query).also { it[1] = (it[1].toInt() xor 1).toByte() }

        assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(bytes, expectedId = 10, expected = question)
        }
    }

    @Test
    fun parseRejectsWrongQuestionType() {
        val query = DnsWire.query(id = 11, question = question)
        val bytes = response(query)
        bytes[questionEnd(query) - 3] = 28

        assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(bytes, expectedId = 11, expected = question)
        }
    }

    @Test
    fun parseRejectsWrongQuestionName() {
        val query = DnsWire.query(id = 12, question = question)
        val bytes = response(query)
        bytes[13] = 'x'.code.toByte()

        assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(bytes, expectedId = 12, expected = question)
        }
    }

    @Test
    fun parseRejectsMalformedARecordData() {
        val query = DnsWire.query(id = 18, question = question)
        val bytes = response(
            query,
            answerCount = 1,
            answerBytes = rawRecord(byteArrayOf(0xC0.toByte(), 0x0C), type = 1, data = byteArrayOf(127, 0, 1)),
        )

        assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(bytes, expectedId = 18, expected = question)
        }
    }

    @Test
    fun parseRejectsMalformedAaaaRecordData() {
        val query = DnsWire.query(id = 19, question = question)
        val bytes = response(
            query,
            answerCount = 1,
            answerBytes = rawRecord(byteArrayOf(0xC0.toByte(), 0x0C), type = 28, data = ByteArray(15)),
        )

        assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(bytes, expectedId = 19, expected = question)
        }
    }

    @Test
    fun parseDoesNotCountAnAnswerWithTheWrongClass() {
        val query = DnsWire.query(id = 20, question = question)
        val message = DnsWire.parseResponse(
            response(query, answerCount = 1, answerBytes = aRecord(recordClass = 3)),
            20,
            question,
        )

        assertEquals(1, message.answerCount)
        assertEquals(0, message.matchingAnswerCount)
    }

    @Test
    fun parseDoesNotCountAnAnswerForAnUnrelatedOwner() {
        val query = DnsWire.query(id = 21, question = question)
        val message = DnsWire.parseResponse(
            response(query, answerCount = 1, answerBytes = aRecord(ownerName = "other.test")),
            21,
            question,
        )

        assertEquals(1, message.answerCount)
        assertEquals(0, message.matchingAnswerCount)
    }

    @Test
    fun parseCountsAnAnswerReachedThroughACname() {
        val query = DnsWire.query(id = 22, question = question)
        val answers = cnameRecord(target = "alias.test") +
            aRecord(address = "192.0.2.10", ownerName = "alias.test")
        val message = DnsWire.parseResponse(
            response(query, answerCount = 2, answerBytes = answers),
            22,
            question,
        )

        assertEquals(2, message.answerCount)
        assertEquals(1, message.matchingAnswerCount)
    }

    @Test
    fun parseReportsExtendedEdnsRcodeInsteadOfNoerror() {
        val query = DnsWire.query(id = 23, question = question)
        val message = DnsWire.parseResponse(
            response(
                query,
                additionalCount = 1,
                additionalBytes = optRecord(extendedRcode = 1),
            ),
            23,
            question,
        )

        assertEquals(16, message.responseCode)
    }

    @Test
    fun parseReportsOnlyCompleteAnswersFromATruncatedResponse() {
        val query = DnsWire.query(id = 24, question = question)
        val partialSecond = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
        val message = DnsWire.parseResponse(
            response(
                query,
                truncated = true,
                answerCount = 2,
                answerBytes = aRecord() + partialSecond,
            ),
            24,
            question,
        )

        assertEquals(1, message.answerCount)
        assertEquals(0, message.matchingAnswerCount)
    }

    @Test
    fun parseRejectsTruncatedHeader() {
        val query = DnsWire.query(id = 13, question = question)

        assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(query.copyOf(10), expectedId = 13, expected = question)
        }
    }

    @Test
    fun parseRejectsMissingAnswerData() {
        val query = DnsWire.query(id = 14, question = question)

        assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(response(query, answerCount = 1), expectedId = 14, expected = question)
        }
    }

    @Test
    fun parseRejectsCompressionLoop() {
        val query = DnsWire.query(id = 15, question = question)
        val offset = questionEnd(query)
        val answer = byteArrayOf(0xC0.toByte(), offset.toByte()) +
            byteArrayOf(0, 1, 0, 1, 0, 0, 0, 60, 0, 1, 0)

        val error = assertThrows(MalformedDnsResponseException::class.java) {
            DnsWire.parseResponse(response(query, answerCount = 1, answerBytes = answer), 15, question)
        }

        assertTrue(error.message!!.contains("name", ignoreCase = true))
    }

    private fun response(
        query: ByteArray,
        rcode: Int = 0,
        truncated: Boolean = false,
        answerCount: Int = 0,
        answerBytes: ByteArray = ByteArray(0),
        additionalCount: Int = 0,
        additionalBytes: ByteArray = ByteArray(0),
    ): ByteArray {
        val end = questionEnd(query)
        val result = query.copyOf(end)
        result[2] = (0x81 or (if (truncated) 0x02 else 0)).toByte()
        result[3] = (0x80 or (rcode and 0x0F)).toByte()
        result[6] = (answerCount ushr 8).toByte()
        result[7] = answerCount.toByte()
        result[10] = (additionalCount ushr 8).toByte()
        result[11] = additionalCount.toByte()
        return result + answerBytes + additionalBytes
    }

    private fun questionEnd(query: ByteArray): Int {
        var offset = 12
        while (query[offset].toInt() != 0) {
            offset += 1 + query[offset].toInt()
        }
        return offset + 5
    }

    private fun aAnswer(): ByteArray =
        byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 1)
}
