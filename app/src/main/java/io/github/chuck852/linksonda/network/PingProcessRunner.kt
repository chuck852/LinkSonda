package io.github.chuck852.linksonda.network

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

            try {
                val output = async {
                    process.inputStream.bufferedReader().use { it.readText() }
                }
                val exitCode = withTimeoutOrNull(timeout) {
                    runInterruptible { process.waitFor() }
                }
                if (exitCode == null) {
                    process.destroy()
                    if (!runInterruptible { process.waitFor(200, TimeUnit.MILLISECONDS) }) {
                        process.destroyForcibly()
                    }
                    output.cancel()
                    PingExecution.TimedOut("")
                } else {
                    PingExecution.Completed(output.await(), exitCode)
                }
            } catch (cancelled: CancellationException) {
                process.destroyForcibly()
                throw cancelled
            } catch (error: Exception) {
                process.destroyForcibly()
                PingExecution.LaunchFailed(error)
            }
        }
}
