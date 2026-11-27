package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.IpFamily
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine

/** Runs the blocking platform resolver on a bounded daemon pool. */
internal class BoundedHostResolver(
    private val hostResolver: HostResolver,
    private val executor: ExecutorService = DNS_EXECUTOR,
) {
    suspend fun resolve(host: String, family: IpFamily?): List<InetAddress> =
        suspendCancellableCoroutine { continuation ->
            val future = try {
                executor.submit {
                    try {
                        continuation.resumeWith(Result.success(hostResolver.resolve(host, family)))
                    } catch (error: Throwable) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                throw DnsCapacityExceededException()
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
}

internal class DnsCapacityExceededException : Exception("DNS worker capacity exceeded")

private const val DNS_WORKERS = 2
private const val DNS_QUEUE_CAPACITY = 8

internal val DNS_EXECUTOR = ThreadPoolExecutor(
    DNS_WORKERS,
    DNS_WORKERS,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue<Runnable>(DNS_QUEUE_CAPACITY),
) { runnable ->
    Thread(runnable, "linksonda-dns").apply { isDaemon = true }
}
