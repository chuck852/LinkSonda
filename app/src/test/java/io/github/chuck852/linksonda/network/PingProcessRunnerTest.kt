package io.github.chuck852.linksonda.network

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PingProcessRunnerTest {
    @Test
    fun timeoutTerminatesTheChildProcess() = runBlocking {
        val started = System.nanoTime()
        val result = SystemPingProcessRunner().execute(listOf("/bin/sleep", "5"), 100.milliseconds)

        assertTrue(result is PingExecution.TimedOut)
        assertTrue((System.nanoTime() - started).nanoseconds < 2_000.milliseconds)
    }

    @Test
    fun cancellationTerminatesTheChildProcess() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(100.milliseconds) {
                    SystemPingProcessRunner().execute(listOf("/bin/sleep", "5"), 5_000.milliseconds)
                }
            }
        }
    }
}
