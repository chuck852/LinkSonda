package io.github.chuck852.linksonda.updater

import io.github.chuck852.linksonda.settings.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSelectorTest {
    private val signer = "a".repeat(64)
    private val sha = "b".repeat(64)
    private val packageName = "io.github.chuck852.linksonda"

    @Test
    fun stableChannelChoosesHighestNonPrerelease() {
        val older = loaded(versionCode = 10, versionName = "0.3.0", tag = "v0.3.0")
        val newer = loaded(versionCode = 12, versionName = "0.3.2", tag = "v0.3.2")
        val preview = loaded(
            versionCode = 13,
            versionName = "0.4.0-beta.1",
            tag = "v0.4.0-beta.1",
            prerelease = true,
            channel = "preview",
        )

        val selection = ReleaseSelector.choose(
            releases = listOf(older, newer, preview),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 2,
            installedSignerSha256 = signer,
            installedSdk = 36,
            applicationId = packageName,
        )

        assertEquals(12L, (selection as ReleaseSelection.Available).update.versionCode)
    }

    @Test
    fun previewChannelMayChoosePrerelease() {
        val preview = loaded(
            versionCode = 13,
            versionName = "0.4.0-beta.1",
            tag = "v0.4.0-beta.1",
            prerelease = true,
            channel = "preview",
        )

        val selection = ReleaseSelector.choose(
            releases = listOf(preview),
            channel = UpdateChannel.PREVIEW,
            installedVersionCode = 2,
            installedSignerSha256 = signer,
            installedSdk = 36,
            applicationId = packageName,
        )

        assertEquals(13L, (selection as ReleaseSelection.Available).update.versionCode)
    }

    @Test
    fun sameOrOlderVersionReportsUpToDate() {
        val selection = ReleaseSelector.choose(
            releases = listOf(loaded(versionCode = 2, versionName = "0.2.0", tag = "v0.2.0")),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 2,
            installedSignerSha256 = signer,
            installedSdk = 36,
            applicationId = packageName,
        )

        assertEquals(ReleaseSelection.UpToDate, selection)
    }

    @Test
    fun testOnlyAndUnsafeMetadataAreRejected() {
        val testOnly = loaded(versionCode = 20, versionName = "0.5.0", tag = "v0.5.0", testOnly = true)
        val wrongSigner = loaded(
            versionCode = 21,
            versionName = "0.5.1",
            tag = "v0.5.1",
            signer = "c".repeat(64),
        )
        val badChecksum = loaded(
            versionCode = 22,
            versionName = "0.5.2",
            tag = "v0.5.2",
            sha = "not-a-checksum",
        )
        val wrongPackage = loaded(
            versionCode = 23,
            versionName = "0.5.3",
            tag = "v0.5.3",
            applicationId = "example.other",
        )
        val unsupported = loaded(
            versionCode = 24,
            versionName = "0.5.4",
            tag = "v0.5.4",
            minSdk = 99,
        )

        val selection = ReleaseSelector.choose(
            releases = listOf(testOnly, wrongSigner, badChecksum, wrongPackage, unsupported),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 2,
            installedSignerSha256 = signer,
            installedSdk = 36,
            applicationId = packageName,
        )

        assertTrue(selection is ReleaseSelection.Invalid)
    }

    @Test
    fun noMetadataAssetIsReportedSeparately() {
        val selection = ReleaseSelector.choose(
            releases = emptyList(),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 2,
            installedSignerSha256 = signer,
            installedSdk = 36,
            applicationId = packageName,
        )

        assertEquals(ReleaseSelection.NoMetadata, selection)
        val release = GitHubRelease(
            tagName = "v0.5.0",
            assets = listOf(GitHubAsset("LinkSonda-0.5.0.apk", "https://github.com/a.apk", 100)),
        )
        assertNull(release.assets.firstOrNull { it.name == RELEASE_METADATA_ASSET })
    }

    @Test
    fun onlyTrustedGithubDownloadHostsAreAccepted() {
        assertTrue(UpdaterUrls.isAllowedDownload("https://github.com/chuck852/LinkSonda/releases/download/v1/a.apk"))
        assertTrue(UpdaterUrls.isAllowedDownload("https://objects.githubusercontent.com/a.apk"))
        assertTrue(!UpdaterUrls.isAllowedDownload("http://github.com/a.apk"))
        assertTrue(!UpdaterUrls.isAllowedDownload("https://evil.example/a.apk"))
        assertTrue(!UpdaterUrls.isAllowedDownload("https://github.com@evil.example/a.apk"))
        assertTrue(UpdaterUrls.isAllowedApi(GITHUB_RELEASES_URL))
        assertTrue(!UpdaterUrls.isAllowedApi("https://evil.example/releases"))
    }

    private fun loaded(
        versionCode: Long,
        versionName: String,
        tag: String,
        prerelease: Boolean = false,
        channel: String = "stable",
        testOnly: Boolean = false,
        signer: String = this.signer,
        sha: String = this.sha,
        applicationId: String = packageName,
        minSdk: Int = 26,
    ): LoadedRelease {
        val apkName = "LinkSonda-$versionName.apk"
        return LoadedRelease(
            release = GitHubRelease(
                tagName = tag,
                body = "Notes for $versionName",
                prerelease = prerelease,
                assets = listOf(
                    GitHubAsset(RELEASE_METADATA_ASSET, "https://github.com/release.json", 200),
                    GitHubAsset(apkName, "https://github.com/$apkName", 1000),
                ),
            ),
            metadata = ReleaseMetadata(
                schemaVersion = 1,
                tag = tag,
                commit = "c".repeat(40),
                applicationId = applicationId,
                versionName = versionName,
                versionCode = versionCode,
                channel = channel,
                minSdk = minSdk,
                apk = ReleaseApk(apkName, 1000, sha),
                signerCertificateSha256 = signer,
                testOnly = testOnly,
            ),
        )
    }
}
