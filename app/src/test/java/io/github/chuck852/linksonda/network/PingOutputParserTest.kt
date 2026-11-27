package io.github.chuck852.linksonda.network

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import org.junit.Assert.assertEquals
import org.junit.Test

class PingOutputParserTest {
    @Test
    fun parsesIpv4AndIpv6ReplyTimes() {
        val output = """
            PING 127.0.0.1 (127.0.0.1) 56(84) bytes of data.
            64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.045 ms
            64 bytes from ::1: icmp_seq=1 ttl=64 time=1.200 ms
            --- 127.0.0.1 ping statistics ---
            2 packets transmitted, 2 received, 0% packet loss, time 0ms
            rtt min/avg/max/mdev = 0.045/0.622/1.200/0.577 ms
        """.trimIndent()

        assertEquals(
            listOf(45_000.nanoseconds, 1_200_000.nanoseconds),
            PingOutputParser.replyTimes(output),
        )
    }

    @Test
    fun ignoresSummaryAndMalformedLines() {
        val output = """
            PING example.test (192.0.2.1) 56(84) bytes of data.
            --- example.test ping statistics ---
            1 packets transmitted, 0 received, 100% packet loss, time 1001ms
            rtt min/avg/max/mdev = 0.000/0.000/0.000/0.000 ms
            time=not-a-number ms
        """.trimIndent()

        assertEquals(emptyList<kotlin.time.Duration>(), PingOutputParser.replyTimes(output))
    }

    @Test
    fun parsesMillisecondsWithSeveralDecimals() {
        assertEquals(listOf(12.345.milliseconds), PingOutputParser.replyTimes("time=12.345 ms"))
    }
}
