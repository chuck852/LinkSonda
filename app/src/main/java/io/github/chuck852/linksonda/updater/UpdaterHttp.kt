package io.github.chuck852.linksonda.updater

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal sealed interface UpdaterHttpResult {
    data class Response(
        val status: Int,
        val body: String?,
        val etag: String?,
        val retryAt: Instant? = null,
    ) : UpdaterHttpResult

    data object NotModified : UpdaterHttpResult

    data class Failure(
        val error: UpdaterError,
        val retryAt: Instant? = null,
    ) : UpdaterHttpResult
}

internal sealed interface UpdaterDownloadResult {
    data class Success(val bytes: Long) : UpdaterDownloadResult
    data class Failure(val error: UpdaterError, val retryAt: Instant? = null) : UpdaterDownloadResult
}

internal interface UpdaterHttpClient {
    suspend fun get(url: String, etag: String? = null): UpdaterHttpResult
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): UpdaterDownloadResult
}

internal class HttpUpdaterHttpClient(
    private val allowTestSource: Boolean = false,
    private val getTimeoutMs: Long = GET_TIMEOUT_MS,
    private val downloadTimeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : UpdaterHttpClient {
    override suspend fun get(url: String, etag: String?): UpdaterHttpResult = withContext(Dispatchers.IO) {
        var call: Call? = null
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { call?.cancel() }
        try {
            withTimeout(getTimeoutMs) {
                executeFollowingRedirects(url, etag, requireApiHost = true, getTimeoutMs) { activeCall ->
                    call = activeCall
                    coroutineContext.ensureActive()
                }.use { response ->
                    when {
                        response.code == 304 -> UpdaterHttpResult.NotModified
                        response.code in 200..299 -> UpdaterHttpResult.Response(
                            status = response.code,
                            body = readTextLimited(response),
                            etag = response.header("ETag"),
                        )
                        response.code == 403 || response.code == 429 -> UpdaterHttpResult.Failure(
                            UpdaterError.RATE_LIMITED,
                            response.retryAt(),
                        )
                        response.code == 404 -> UpdaterHttpResult.Failure(UpdaterError.NO_RELEASE)
                        else -> UpdaterHttpResult.Failure(UpdaterError.NETWORK)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            UpdaterHttpResult.Failure(UpdaterError.NETWORK)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            if (!coroutineContext[Job]?.isActive.orTrue()) throw CancellationException("Updater request cancelled", error)
            UpdaterHttpResult.Failure(UpdaterError.NETWORK)
        } catch (_: IllegalArgumentException) {
            UpdaterHttpResult.Failure(UpdaterError.INVALID_RELEASE)
        } finally {
            cancellation?.dispose()
            call?.cancel()
        }
    }

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): UpdaterDownloadResult = withContext(Dispatchers.IO) {
        if (!UpdaterUrls.isAllowedDownload(url, allowTestSource)) {
            return@withContext UpdaterDownloadResult.Failure(UpdaterError.INVALID_RELEASE)
        }
        var call: Call? = null
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { call?.cancel() }
        try {
            withTimeout(downloadTimeoutMs) {
                executeFollowingRedirects(url, etag = null, requireApiHost = false, downloadTimeoutMs) { activeCall ->
                    call = activeCall
                    coroutineContext.ensureActive()
                }.use { response ->
                    if (response.code !in 200..299) {
                        return@withTimeout UpdaterDownloadResult.Failure(
                            if (response.code == 403 || response.code == 429) {
                                UpdaterError.RATE_LIMITED
                            } else {
                                UpdaterError.DOWNLOAD_FAILED
                            },
                            response.retryAt(),
                        )
                    }
                    val body = response.body
                    val declared = body.contentLength().takeIf { it > 0 }
                    if (declared != null && declared > MAX_APK_BYTES) {
                        return@withTimeout UpdaterDownloadResult.Failure(UpdaterError.VERIFY_FAILED)
                    }
                    destination.parentFile?.mkdirs()
                    var downloaded = 0L
                    body.byteStream().use { raw ->
                        BufferedInputStream(raw, BUFFER_SIZE).use { input ->
                            FileOutputStream(destination).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    downloaded += read
                                    if (downloaded > MAX_APK_BYTES) {
                                        return@withTimeout UpdaterDownloadResult.Failure(UpdaterError.VERIFY_FAILED)
                                    }
                                    output.write(buffer, 0, read)
                                    onProgress(downloaded, declared)
                                }
                            }
                        }
                    }
                    UpdaterDownloadResult.Success(downloaded)
                }
            }
        } catch (_: TimeoutCancellationException) {
            destination.delete()
            UpdaterDownloadResult.Failure(UpdaterError.NETWORK)
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (error: IOException) {
            destination.delete()
            if (!coroutineContext[Job]?.isActive.orTrue()) throw CancellationException("Updater download cancelled", error)
            UpdaterDownloadResult.Failure(UpdaterError.DOWNLOAD_FAILED)
        } catch (_: IllegalArgumentException) {
            destination.delete()
            UpdaterDownloadResult.Failure(UpdaterError.INVALID_RELEASE)
        } finally {
            cancellation?.dispose()
            call?.cancel()
            if (destination.exists() && destination.length() == 0L) destination.delete()
        }
    }

    private fun executeFollowingRedirects(
        initialUrl: String,
        etag: String?,
        requireApiHost: Boolean,
        timeoutMs: Long,
        onCall: (Call) -> Unit,
    ): Response {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            val allowed = if (requireApiHost) {
                UpdaterUrls.isAllowedApi(current, allowTestSource) ||
                    UpdaterUrls.isAllowedDownload(current, allowTestSource)
            } else {
                UpdaterUrls.isAllowedDownload(current, allowTestSource)
            }
            require(allowed) { "Untrusted updater URL" }
            val request = Request.Builder()
                .url(current)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            onCall(call)
            val response = call.execute()
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw IOException("Updater redirect without Location")
                current = URI(current).resolve(location).toString()
            } else {
                return response
            }
        }
        throw IOException("Too many updater redirects")
    }

    private suspend fun readTextLimited(response: Response): String {
        val body = response.body
        val declared = body.contentLength()
        if (declared > MAX_API_RESPONSE_BYTES) throw IOException("Updater response too large")
        return body.byteStream().use { raw ->
            BufferedInputStream(raw, BUFFER_SIZE).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_API_RESPONSE_BYTES) throw IOException("Updater response too large")
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun Response.retryAt(): Instant? {
        header("Retry-After")?.trim()?.let { value ->
            value.toLongOrNull()?.let { seconds ->
                return Instant.now().plusSeconds(seconds.coerceAtLeast(0))
            }
            runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull()?.let { return it }
        }
        header("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { epochSeconds ->
            return Instant.ofEpochSecond(epochSeconds)
        }
        return null
    }

    private fun Boolean?.orTrue(): Boolean = this ?: true

    private companion object {
        const val USER_AGENT = "LinkSonda-Updater"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 20_000L
        const val GET_TIMEOUT_MS = 20_000L
        const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
        const val BUFFER_SIZE = 16 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
