package io.github.chuck852.linksonda.updater

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
) : UpdaterHttpClient {
    override suspend fun get(url: String, etag: String?): UpdaterHttpResult = try {
        withTimeout(GET_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                val disconnectOnCancellation = coroutineContext[kotlinx.coroutines.Job]
                    ?.invokeOnCompletion { connection?.disconnect() }
                try {
                    connection = request(url, etag, requireApiHost = true)
                    when {
                        connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED -> UpdaterHttpResult.NotModified
                        connection.responseCode in 200..299 -> UpdaterHttpResult.Response(
                            status = connection.responseCode,
                            body = connection.readTextLimited(),
                            etag = connection.etag,
                        )
                        connection.responseCode == 403 || connection.responseCode == 429 -> UpdaterHttpResult.Failure(
                            UpdaterError.RATE_LIMITED,
                            connection.retryAt(),
                        )
                        connection.responseCode == 404 -> UpdaterHttpResult.Failure(UpdaterError.NO_RELEASE)
                        else -> UpdaterHttpResult.Failure(UpdaterError.NETWORK)
                    }
                } catch (timeout: SocketTimeoutException) {
                    UpdaterHttpResult.Failure(UpdaterError.NETWORK)
                } catch (error: IOException) {
                    UpdaterHttpResult.Failure(UpdaterError.NETWORK)
                } catch (error: IllegalArgumentException) {
                    UpdaterHttpResult.Failure(UpdaterError.INVALID_RELEASE)
                } finally {
                    disconnectOnCancellation?.dispose()
                    connection?.disconnect()
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        UpdaterHttpResult.Failure(UpdaterError.NETWORK)
    }

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): UpdaterDownloadResult = try {
        withTimeout(DOWNLOAD_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                val disconnectOnCancellation = coroutineContext[kotlinx.coroutines.Job]
                    ?.invokeOnCompletion { connection?.disconnect() }
                try {
            if (!UpdaterUrls.isAllowedDownload(url, allowTestSource)) {
                return@withContext UpdaterDownloadResult.Failure(UpdaterError.INVALID_RELEASE)
            }
            val opened = request(url, etag = null, requireApiHost = false)
            connection = opened
            if (opened.responseCode !in 200..299) {
                return@withContext UpdaterDownloadResult.Failure(
                    if (opened.responseCode == 403 || opened.responseCode == 429) UpdaterError.RATE_LIMITED else UpdaterError.DOWNLOAD_FAILED,
                    opened.retryAt(),
                )
            }
            val declared = opened.contentLengthLong.takeIf { it > 0 }
            if (declared != null && declared > MAX_APK_BYTES) {
                return@withContext UpdaterDownloadResult.Failure(UpdaterError.VERIFY_FAILED)
            }
            destination.parentFile?.mkdirs()
            var downloaded = 0L
            opened.inputStream.use { raw ->
                BufferedInputStream(raw, BUFFER_SIZE).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            if (downloaded > MAX_APK_BYTES) {
                                return@withContext UpdaterDownloadResult.Failure(UpdaterError.VERIFY_FAILED)
                            }
                            output.write(buffer, 0, read)
                            onProgress(downloaded, declared)
                        }
                    }
                }
            }
            UpdaterDownloadResult.Success(downloaded)
        } catch (timeout: SocketTimeoutException) {
            UpdaterDownloadResult.Failure(UpdaterError.NETWORK)
        } catch (error: IOException) {
            UpdaterDownloadResult.Failure(UpdaterError.DOWNLOAD_FAILED)
        } catch (error: IllegalArgumentException) {
            UpdaterDownloadResult.Failure(UpdaterError.INVALID_RELEASE)
                } finally {
                    disconnectOnCancellation?.dispose()
                    connection?.disconnect()
                    if (destination.exists() && destination.length() == 0L) destination.delete()
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        UpdaterDownloadResult.Failure(UpdaterError.NETWORK)
    }

    private fun request(url: String, etag: String?, requireApiHost: Boolean): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val allowed = if (requireApiHost) {
                UpdaterUrls.isAllowedApi(current, allowTestSource) || UpdaterUrls.isAllowedDownload(current, allowTestSource)
            } else {
                UpdaterUrls.isAllowedDownload(current, allowTestSource)
            }
            require(allowed) { "Untrusted updater URL" }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                connection.connect()
                val location = connection.getHeaderField("Location")
                if (connection.responseCode in REDIRECT_CODES && !location.isNullOrBlank()) {
                    connection.disconnect()
                    current = URI(current).resolve(location).toString()
                } else {
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Too many updater redirects")
    }

    private fun HttpURLConnection.readTextLimited(): String {
        val declared = contentLengthLong
        if (declared > MAX_API_RESPONSE_BYTES) throw IOException("Updater response too large")
        inputStream.use { raw ->
            BufferedInputStream(raw, BUFFER_SIZE).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_API_RESPONSE_BYTES) throw IOException("Updater response too large")
                    output.write(buffer, 0, read)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun HttpURLConnection.retryAt(): Instant? {
        getHeaderField("Retry-After")?.trim()?.let { value ->
            value.toLongOrNull()?.let { seconds ->
                return Instant.now().plusSeconds(seconds.coerceAtLeast(0))
            }
            runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull()?.let { return it }
        }
        getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { epochSeconds ->
            return Instant.ofEpochSecond(epochSeconds)
        }
        return null
    }

    private val HttpURLConnection.etag: String?
        get() = getHeaderField("ETag")

    private companion object {
        const val USER_AGENT = "LinkSonda-Updater"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val GET_TIMEOUT_MS = 20_000L
        const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
        const val BUFFER_SIZE = 16 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
