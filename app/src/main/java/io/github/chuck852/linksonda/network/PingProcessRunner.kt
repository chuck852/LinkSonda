package io.github.chuck852.linksonda.network

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface PingExecution {
    data class Completed(val output: String, val exitCode: Int) : PingExecution
    data class TimedOut(val output: String) : PingExecution
    data class LaunchFailed(val error: Throwable) : PingExecution
}

internal fun interface PingProcessRunner {
    suspend fun execute(command: List<String>, timeout: Duration): PingExecution
}

internal class SystemPingProcessRunner : PingProcessRunner {
    override suspend fun execute(command: List<String>, timeout: Duration): PingExecution =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (error: Exception) {
                return@withContext PingExecution.LaunchFailed(error)
            }

            val output = ByteArrayOutputStream()
            val outputLock = Any()
            val reader = async {
                val input = process.inputStream
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(outputLock) {
                        val remaining = MAX_OUTPUT_BYTES - output.size()
                        if (remaining > 0) output.write(buffer, 0, minOf(read, remaining))
                    }
                }
            }
            try {
                val exitCode = withTimeoutOrNull(timeout) {
                    runInterruptible { process.waitFor() }
                }
                if (exitCode == null) {
                    terminate(process)
                    reader.cancel()
                    PingExecution.TimedOut(snapshot(output, outputLock))
                } else {
                    reader.await()
                    PingExecution.Completed(snapshot(output, outputLock), exitCode)
                }
            } catch (cancelled: CancellationException) {
                terminate(process)
                reader.cancel()
                throw cancelled
            } catch (error: Exception) {
                terminate(process)
                reader.cancel()
                PingExecution.LaunchFailed(error)
            }
        }

    private fun terminate(process: Process) {
        process.destroy()
        if (runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            return
        }
        process.destroyForcibly()
        runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
    }

    private fun snapshot(output: ByteArrayOutputStream, lock: Any): String =
        synchronized(lock) { output.toString(Charsets.UTF_8.name()) }

    private companion object {
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
