package io.github.chuck852.linksonda.updater

import io.github.chuck852.linksonda.settings.UpdateChannel
import io.github.chuck852.linksonda.settings.UpdateCheckStatus
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/chuck852/LinkSonda/releases?per_page=30"
internal const val RELEASE_METADATA_ASSET = "release.json"
internal const val MAX_RELEASE_NOTES_LENGTH = 16_000
internal const val MAX_API_RESPONSE_BYTES = 2L * 1024 * 1024
internal const val MAX_APK_BYTES = 200L * 1024 * 1024

internal val updaterJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)

@Serializable
data class ReleaseMetadata(
    val schemaVersion: Int,
    val tag: String,
    val commit: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val channel: String,
    val minSdk: Int,
    val apk: ReleaseApk,
    val signerCertificateSha256: String,
    val generatedAt: String? = null,
    val testOnly: Boolean = false,
)

@Serializable
data class ReleaseApk(
    val name: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class OfferedUpdate(
    val metadata: ReleaseMetadata,
    val downloadUrl: String,
    val notes: String = "",
    val publishedAt: String? = null,
) {
    val versionCode: Long get() = metadata.versionCode
    val versionName: String get() = metadata.versionName
    val channel: UpdateChannel get() = UpdateChannel.valueOf(metadata.channel.uppercase())
}

enum class UpdaterError {
    NETWORK,
    RATE_LIMITED,
    INVALID_RELEASE,
    NO_RELEASE,
    DOWNLOAD_FAILED,
    VERIFY_FAILED,
    INSTALL_PERMISSION,
    INSTALL_FAILED,
    BUSY,
}

enum class InstallState { IDLE, WAITING_PERMISSION, INSTALLING, FAILED }

data class DownloadProgress(
    val downloaded: Long,
    val total: Long?,
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (downloaded.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

data class UpdaterUiState(
    val currentVersionName: String = "",
    val currentVersionCode: Long = 0,
    val checking: Boolean = false,
    val status: UpdateCheckStatus = UpdateCheckStatus.NEVER,
    val lastCheckAt: Instant? = null,
    val retryAt: Instant? = null,
    val available: OfferedUpdate? = null,
    val download: DownloadProgress? = null,
    val readyToInstall: Boolean = false,
    val installPermissionRequired: Boolean = false,
    val installState: InstallState = InstallState.IDLE,
    val error: UpdaterError? = null,
)
