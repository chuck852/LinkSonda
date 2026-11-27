package io.github.chuck852.linksonda.updater

import io.github.chuck852.linksonda.settings.UpdateChannel
import java.net.URI

internal data class LoadedRelease(
    val release: GitHubRelease,
    val metadata: ReleaseMetadata,
)

internal sealed interface ReleaseSelection {
    data class Available(val update: OfferedUpdate) : ReleaseSelection
    data object UpToDate : ReleaseSelection
    data class Invalid(val reason: String) : ReleaseSelection
    data object NoMetadata : ReleaseSelection
}

internal object ReleaseSelector {
    fun choose(
        releases: List<LoadedRelease>,
        channel: UpdateChannel,
        installedVersionCode: Long,
        installedSignerSha256: String,
        installedSdk: Int,
        applicationId: String,
        allowTestSource: Boolean = false,
    ): ReleaseSelection {
        var sawOlder = false
        var firstInvalid: String? = null
        var best: OfferedUpdate? = null

        for (loaded in releases) {
            val release = loaded.release
            if (release.draft) continue
            if (channel == UpdateChannel.STABLE && release.prerelease) continue
            val metadata = loaded.metadata
            val invalid = validate(
                release = release,
                metadata = metadata,
                channel = channel,
                installedSignerSha256 = installedSignerSha256,
                installedSdk = installedSdk,
                applicationId = applicationId,
                allowTestSource = allowTestSource,
            )
            if (invalid != null) {
                firstInvalid = firstInvalid ?: invalid
                continue
            }
            if (metadata.versionCode <= installedVersionCode) {
                sawOlder = true
                continue
            }
            val apkAsset = release.assets.firstOrNull { it.name == metadata.apk.name } ?: continue
            val candidate = OfferedUpdate(
                metadata = metadata,
                downloadUrl = apkAsset.browserDownloadUrl,
                notes = release.body.orEmpty().take(MAX_RELEASE_NOTES_LENGTH),
                publishedAt = release.publishedAt,
            )
            if (best == null || candidate.versionCode > best.versionCode) {
                best = candidate
            }
        }

        return when {
            best != null -> ReleaseSelection.Available(best)
            sawOlder -> ReleaseSelection.UpToDate
            firstInvalid != null -> ReleaseSelection.Invalid(firstInvalid)
            else -> ReleaseSelection.NoMetadata
        }
    }

    private fun validate(
        release: GitHubRelease,
        metadata: ReleaseMetadata,
        channel: UpdateChannel,
        installedSignerSha256: String,
        installedSdk: Int,
        applicationId: String,
        allowTestSource: Boolean,
    ): String? {
        if (metadata.schemaVersion != 1) return "unsupported metadata schema"
        if (metadata.testOnly) return "test-only release"
        if (metadata.applicationId != applicationId) return "applicationId mismatch"
        if (metadata.versionCode <= 0) return "invalid versionCode"
        if (metadata.versionName.isBlank()) return "missing versionName"
        if (metadata.tag != "v${metadata.versionName}") return "tag/version mismatch"
        if (metadata.tag != release.tagName) return "release/tag mismatch"
        val offeredChannel = when (metadata.channel) {
            "stable" -> UpdateChannel.STABLE
            "preview" -> UpdateChannel.PREVIEW
            else -> return "wrong channel"
        }
        if (!channel.accepts(offeredChannel)) return "wrong channel"
        if (release.prerelease != (metadata.channel == "preview")) return "prerelease/channel mismatch"
        if (metadata.minSdk <= 0 || metadata.minSdk > installedSdk) return "unsupported minSdk"
        if (metadata.apk.name.isBlank() || metadata.apk.size <= 0) return "invalid APK metadata"
        if (!isSha256(metadata.apk.sha256)) return "invalid APK checksum"
        if (!isSha256(metadata.signerCertificateSha256)) return "invalid signer checksum"
        if (!normalizeSha256(metadata.signerCertificateSha256).equals(installedSignerSha256, ignoreCase = true)) {
            return "signer mismatch"
        }
        val apkAsset = release.assets.firstOrNull { it.name == metadata.apk.name } ?: return "APK asset missing"
        if (apkAsset.size > 0 && apkAsset.size != metadata.apk.size) return "APK size mismatch"
        if (!UpdaterUrls.isAllowedDownload(apkAsset.browserDownloadUrl, allowTestSource)) return "untrusted download URL"
        return null
    }

    internal fun isSha256(value: String): Boolean = normalizeSha256(value).matches(Regex("[0-9a-f]{64}"))

    internal fun normalizeSha256(value: String): String =
        value.replace(":", "").replace("-", "").lowercase()
}

internal object UpdaterUrls {
    private val downloadHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    fun isAllowedDownload(value: String, allowTestSource: Boolean = false): Boolean = try {
        val uri = URI(value)
        (uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.host?.lowercase() in downloadHosts) ||
            (allowTestSource && isTestUrl(uri))
    } catch (_: Exception) {
        false
    }

    fun isAllowedApi(value: String, allowTestSource: Boolean = false): Boolean = try {
        val uri = URI(value)
        (uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.host.equals("api.github.com", ignoreCase = true)) ||
            (allowTestSource && isTestUrl(uri))
    } catch (_: Exception) {
        false
    }

    private fun isTestUrl(uri: URI): Boolean =
        uri.scheme.equals("http", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.host?.lowercase() in setOf("10.0.2.2", "127.0.0.1", "localhost")
}
