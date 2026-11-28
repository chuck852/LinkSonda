package io.github.chuck852.linksonda.network

import java.nio.file.Files
import java.nio.file.Path
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
        try {
            val started = System.nanoTime()
            val result = SystemPingProcessRunner().execute(
                listOf("/bin/sh", "-c", "echo \$\$ > $pidFile; exec sleep 5"),
                500.milliseconds,
            )

            assertTrue(result is PingExecution.TimedOut)
            assertTrue((System.nanoTime() - started).nanoseconds < 3_000.milliseconds)
            assertFalse(waitForDead(pidFile))
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun cancellationTerminatesTheChildProcess() {
        val pidFile = Files.createTempFile("linksonda-ping", ".pid")
        try {
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(500.milliseconds) {
                        SystemPingProcessRunner().execute(
                            listOf("/bin/sh", "-c", "echo \$\$ > $pidFile; exec sleep 5"),
                            5_000.milliseconds,
                        )
                    }
                }
            }
            assertFalse(waitForDead(pidFile))
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun timeoutPreservesAvailablePartialOutput() = runBlocking {
        val result = SystemPingProcessRunner().execute(
            listOf("/bin/sh", "-c", "printf 'partial output\\n'; exec sleep 5"),
            200.milliseconds,
        )

        assertTrue(result is PingExecution.TimedOut)
        assertTrue((result as PingExecution.TimedOut).output.contains("partial output"))
    }

    private fun waitForDead(pidFile: Path): Boolean {
        val pid = awaitPid(pidFile) ?: return true
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false).not()) return false
            Thread.sleep(20)
        }
        return true
    }

    private fun awaitPid(pidFile: Path): Long? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                Files.readString(pidFile).trim().toLongOrNull()?.let { return it }
            }
            Thread.sleep(20)
        }
        return null
    }
}
