package io.github.chuck852.linksonda.network

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PingProcessRunnerTest {
    @Test
    fun timeoutTerminatesTheChildProcess() = runBlocking {
        val pidFile = Files.createTempFile("linksonda-ping", ".pid")
        val started = System.nanoTime()
        val result = SystemPingProcessRunner().execute(
            listOf("/bin/sh", "-c", "echo \$\$ > $pidFile; exec sleep 5"),
            100.milliseconds,
        )

        assertTrue(result is PingExecution.TimedOut)
        assertTrue((System.nanoTime() - started).nanoseconds < 2_000.milliseconds)
        assertFalse(waitForDead(pidFile))
    }

    @Test
    fun cancellationTerminatesTheChildProcess() {
        val pidFile = Files.createTempFile("linksonda-ping", ".pid")
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(100.milliseconds) {
                    SystemPingProcessRunner().execute(
                        listOf("/bin/sh", "-c", "echo \$\$ > $pidFile; exec sleep 5"),
                        5_000.milliseconds,
                    )
                }
            }
        }
        assertFalse(waitForDead(pidFile))
    }

    private fun waitForDead(pidFile: java.nio.file.Path): Boolean {
        val pid = Files.readString(pidFile).trim().toLong()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false).not()) return false
            Thread.sleep(20)
        }
        return true
    }
}
