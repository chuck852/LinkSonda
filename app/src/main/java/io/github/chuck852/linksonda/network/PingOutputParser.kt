package io.github.chuck852.linksonda.network

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal object PingOutputParser {
    private val REPLY_TIME = Regex(
        """\bbytes from .*?\btime=([0-9]+(?:\.[0-9]+)?)\s*ms\b""",
        RegexOption.IGNORE_CASE,
    )

    fun replyTimes(output: String): List<Duration> =
        REPLY_TIME.findAll(output).map { match ->
            BigDecimal(match.groupValues[1])
                .movePointRight(6) // milliseconds to nanoseconds
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
                .nanoseconds
        }.toList()
}
