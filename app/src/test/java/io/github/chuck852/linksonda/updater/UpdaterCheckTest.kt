package io.github.chuck852.linksonda.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.chuck852.linksonda.settings.PersistentSettingsStore
import io.github.chuck852.linksonda.settings.SettingsStorage
import io.github.chuck852.linksonda.settings.SettingsCodec
import io.github.chuck852.linksonda.settings.UpdateChannel
import io.github.chuck852.linksonda.settings.UpdateCheckStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdaterCheckTest {
    private val signer = "a".repeat(64)
    private val sha = "b".repeat(64)
    private val now = Instant.parse("2026-11-28T12:00:00Z")

    @Test
    fun checkPersistsAvailableUpdateAndSendsEtagOnNextRequest() = runBlocking {
        val storage = MemoryStorage()
        val settings = PersistentSettingsStore(storage)
        val http = FakeHttp(
            releases = listOf(
                release(versionCode = 10, versionName = "0.4.0", tag = "v0.4.0"),
            ),
            metadata = metadata(versionCode = 10, versionName = "0.4.0", tag = "v0.4.0"),
        )
        val updater = updater(settings, http)

        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, updater.checkNowAwait())
        assertEquals(10L, updater.state.value.available?.versionCode)
        assertEquals(10L, settings.current().lastOfferedVersionCode)
        assertEquals("\"etag-1\"", settings.current().updateEtag)
        assertNotNull(settings.current().lastOfferedUpdateJson)

        val notModified = updater(settings, FakeHttp(notModified = true))
        assertEquals(UpdateCheckStatus.NOT_MODIFIED, notModified.checkNowAwait())
        assertEquals(10L, notModified.state.value.available?.versionCode)
    }

    @Test
    fun rateLimitRetainsExistingUpdateAndStoresRetryTime() = runBlocking {
        val retryAt = Instant.parse("2026-11-28T12:30:00Z")
        val storage = MemoryStorage()
        val settings = PersistentSettingsStore(storage)
        val first = FakeHttp(
            releases = listOf(release(versionCode = 10, versionName = "0.4.0", tag = "v0.4.0")),
            metadata = metadata(versionCode = 10, versionName = "0.4.0", tag = "v0.4.0"),
        )
        updater(settings, first).checkNowAwait()

        val limited = updater(
            settings,
            FakeHttp(failure = UpdaterHttpResult.Failure(UpdaterError.RATE_LIMITED, retryAt)),
        )
        assertEquals(UpdateCheckStatus.RATE_LIMITED, limited.checkNowAwait())
        assertEquals(10L, limited.state.value.available?.versionCode)
        assertEquals(retryAt, settings.current().lastUpdateRetryAt)
    }

    @Test
    fun metadataFetchFailureDoesNotPoisonTheReleaseEtag() = runBlocking {
        val storage = MemoryStorage()
        val settings = PersistentSettingsStore(storage)
        settings.update { it.copy(updateEtag = "\"old-etag\"") }
        val http = MetadataFailureHttp(release(versionCode = 12, versionName = "0.4.2", tag = "v0.4.2"))
        val updater = updater(settings, http)

        assertEquals(UpdateCheckStatus.INVALID_RELEASE, updater.checkNowAwait())
        assertNull(settings.current().updateEtag)
    }

    @Test
    fun invalidSignerIsRejectedAndNotOffered() = runBlocking {
        val storage = MemoryStorage()
        val settings = PersistentSettingsStore(storage)
        val http = FakeHttp(
            releases = listOf(release(versionCode = 11, versionName = "0.4.1", tag = "v0.4.1")),
            metadata = metadata(
                versionCode = 11,
                versionName = "0.4.1",
                tag = "v0.4.1",
                signer = "c".repeat(64),
            ),
        )
        val updater = updater(settings, http)

        assertEquals(UpdateCheckStatus.INVALID_RELEASE, updater.checkNowAwait())
        assertNull(updater.state.value.available)
        assertNull(settings.current().lastOfferedVersionCode)
    }

    private fun updater(settings: PersistentSettingsStore, http: UpdaterHttpClient): Updater {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Updater(
            context = context,
            settingsStore = settings,
            http = http,
            installed = InstalledApp(2, "0.2.0", signer, 36, false),
            clock = { now },
            debugBuild = false,
        )
    }

    private fun release(versionCode: Long, versionName: String, tag: String): GitHubRelease {
        val apk = "LinkSonda-$versionName.apk"
        return GitHubRelease(
            tagName = tag,
            body = "Notes",
            assets = listOf(
                GitHubAsset(RELEASE_METADATA_ASSET, "https://github.com/release.json", 200),
                GitHubAsset(apk, "https://github.com/$apk", 1000),
            ),
        )
    }

    private fun metadata(
        versionCode: Long,
        versionName: String,
        tag: String,
        signer: String = this.signer,
    ): ReleaseMetadata = ReleaseMetadata(
        schemaVersion = 1,
        tag = tag,
        commit = "c".repeat(40),
        applicationId = "io.github.chuck852.linksonda",
        versionName = versionName,
        versionCode = versionCode,
        channel = "stable",
        minSdk = 26,
        apk = ReleaseApk("LinkSonda-$versionName.apk", 1000, sha),
        signerCertificateSha256 = signer,
    )

    private class FakeHttp(
        private val releases: List<GitHubRelease> = emptyList(),
        private val metadata: ReleaseMetadata? = null,
        private val notModified: Boolean = false,
        private val failure: UpdaterHttpResult? = null,
    ) : UpdaterHttpClient {
        override suspend fun get(url: String, etag: String?): UpdaterHttpResult {
            if (failure != null) return failure
            if (notModified) return UpdaterHttpResult.NotModified
            val body = if (url == GITHUB_RELEASES_URL) {
                updaterJson.encodeToString(ListSerializer(GitHubRelease.serializer()), releases)
            } else {
                updaterJson.encodeToString(ReleaseMetadata.serializer(), requireNotNull(metadata))
            }
            return UpdaterHttpResult.Response(200, body, "\"etag-1\"")
        }

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: (Long, Long?) -> Unit,
        ): UpdaterDownloadResult {
            destination.writeBytes(ByteArray(10))
            onProgress(10, 10)
            return UpdaterDownloadResult.Success(10)
        }
    }

    private class MetadataFailureHttp(
        private val release: GitHubRelease,
    ) : UpdaterHttpClient {
        override suspend fun get(url: String, etag: String?): UpdaterHttpResult =
            if (url == GITHUB_RELEASES_URL) {
                UpdaterHttpResult.Response(
                    200,
                    updaterJson.encodeToString(ListSerializer(GitHubRelease.serializer()), listOf(release)),
                    "\"etag-1\"",
                )
            } else {
                UpdaterHttpResult.Failure(UpdaterError.NETWORK)
            }

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: (Long, Long?) -> Unit,
        ): UpdaterDownloadResult = UpdaterDownloadResult.Failure(UpdaterError.DOWNLOAD_FAILED)
    }

    private class MemoryStorage : SettingsStorage {
        private var values: Map<String, String?> = emptyMap()
        override fun read(): Map<String, String?> = values
        override suspend fun write(values: Map<String, String>): Boolean {
            this.values = values
            return true
        }
    }
}
