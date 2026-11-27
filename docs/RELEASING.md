# Publishing a release

[GitHub Releases](https://github.com/chuck852/LinkSonda/releases) is the distribution channel. Android CI builds and tests every change. A maintainer selects one successful `main` build, signs it locally and publishes it after separate owner acceptance. Signing keys never enter GitHub Actions or release artifacts.

## Channels and versions

- **Stable** releases are normal GitHub Releases. Their `versionName` has no hyphen.
- **Preview** releases are GitHub pre-releases. Their `versionName` contains a hyphen (`-alpha`, `-beta`, `-rc` or a technical preview suffix).
- `version.properties` is the single source of `versionCode` and `versionName`. `versionCode` is a positive integer and must be strictly greater than every published release, including pre-releases. Do not reuse a tag. Published `release.json` values are also validated as positive integers; a non-integer value is rejected.
- A release tag is `v<versionName>` and must be annotated on the accepted commit.

## 1. Prepare the candidate

Update `version.properties` and add `docs/releases/v<versionName>.md` with the user-facing notes. Open a pull request, wait for the required **Debug APK** check, and merge after review. The successful `main` run builds both the debug APK and an unsigned release APK `LinkSonda-release-unsigned.apk`. The unsigned artifact includes `SHA256SUMS` and is kept for 14 days.

Do not add a signing configuration to Gradle. The release APK stays unsigned until the owner signs it offline.

## 2. Tag the accepted commit

After the owner accepts the commit and the exact release notes, create and push an annotated tag on the commit that CI built:

```bash
tag=v<versionName>
git tag -a "$tag" "$commit" -m "LinkSonda $tag"
git push origin "refs/tags/$tag"
```

The signing script verifies the remote tag points to the same commit. A draft or published release with the same tag is rejected.

## 3. Sign and prepare the bundle

Use Bash, Git, an authenticated GitHub CLI, jq, GNU coreutils, tar, JDK 25 and Android SDK Build-Tools. Set `ANDROID_BUILD_TOOLS` only if Build-Tools 36.0.0 is not under `ANDROID_HOME`. Use an output directory outside the checkout that does not exist yet.

```bash
export LINKSONDA_KEYSTORE=/secure/path/linksonda-release.p12
export LINKSONDA_KEY_ALIAS=linksonda
export LINKSONDA_SIGNER_SHA256=<certificate SHA-256>
./scripts/prepare-release.sh "$run_id" "$commit" "$bundle"
```

The script checks the CI run (`main`, expected workflow, success, exact commit), downloads the unsigned APK, verifies its checksum, rejects an already-signed or debuggable APK, checks package, versionCode, versionName and minSdk, and signs with `apksigner`. It then checks the single signer certificate, the v2/v3 signature, the expected SHA-256 and that signing did not change the APK metadata.

It also enforces signing continuity: every previous published release with `release.json` must use the same signer certificate. The script downloads that release's APK and verifies its actual signature. Legacy debug previews without `release.json` are excluded. A mismatch stops signing because changing the key would break in-place updates.

The keystore must be outside the repository and must not be readable by group or others. The password is entered interactively and passed to `apksigner` through stdin; it is never an argument, environment variable or temporary file. Never run the script with `bash -x` or `set -x`; xtrace can print expanded arguments. The script disables xtrace at startup as an additional guard.

The output directory contains:

| File | Purpose |
| --- | --- |
| `LinkSonda-<versionName>.apk` | Signed release APK |
| `release.json` | Machine-readable release metadata for the updater |
| `SHA256SUMS` | Checksums for the APK, metadata and license archive (the published assets) |
| `LinkSonda-license-notices.tar.gz` | Apache-2.0 and third-party notices from the same commit |
| `release-notes.md` | Copy of the notes for the draft release body; not a release asset |

## 4. Review and publish

The script prints a ready `gh release create` command with `--draft` only in CI-artifact mode. It adds `--prerelease` for preview versions. The command uploads exactly the APK, `release.json`, `SHA256SUMS` and the license archive. `release-notes.md` is used through `--notes-file` and is not an asset. Review `release.json`, the signer fingerprint and `sha256sum -c SHA256SUMS`, then create the draft. Download the draft assets again, verify the checksum and certificate, and publish through the GitHub Releases UI or:

```bash
gh release edit "$tag" --repo chuck852/LinkSonda --draft=false
```

After publishing, download the public APK and confirm installation and launch on a clean test device. Install over the previous release with the same key to confirm that settings survive.

## release.json contract

`release.json` is included in every official release. Its schema version is `1`.

```json
{
  "schemaVersion": 1,
  "tag": "v0.3.0",
  "commit": "<40-character commit SHA>",
  "applicationId": "io.github.chuck852.linksonda",
  "versionName": "0.3.0",
  "versionCode": 3,
  "channel": "stable",
  "minSdk": 26,
  "apk": {
    "name": "LinkSonda-0.3.0.apk",
    "size": 8825279,
    "sha256": "<APK SHA-256>"
  },
  "signerCertificateSha256": "<certificate SHA-256>",
  "generatedAt": "2026-11-27T20:31:19Z"
}
```

`channel` is `preview` when `versionName` contains a hyphen, otherwise `stable`. `versionCode`, `minSdk` and `apk.size` are positive integers. `testOnly` is absent from official releases; a test-only bundle has `"testOnly": true` and must be rejected. The updater must compare `versionCode`, choose the APK by `apk.name`, and verify `apk.size`, `apk.sha256`, `applicationId`, `versionCode` and `minSdk`. It must also compare `signerCertificateSha256` with the installed app's signer; downloaded metadata alone is not trusted. SHA-256 does not replace the signature check.

The updater must reject downgrades, debug or unsigned builds, and a different signer. A stable channel ignores pre-releases. A preview channel may offer the newest pre-release or stable build. GitHub being unavailable is not evidence that the network is down. No GitHub token is stored on the device and no telemetry is sent.

## Test-only bundles

`prepare-release.sh --unsigned-apk` exists only for local testing and recovery rehearsal. It refuses a commit reachable from `origin/main` and a tag that already exists on `origin`, adds `"testOnly": true` to `release.json`, and does not print a `gh release create` command. A `release.json` with `testOnly: true` is not valid for an official release and is rejected by later release checks. Never upload a test bundle to GitHub Releases.

## Legacy debug previews

The earlier `v0.1.0-dev.1` and `v0.2.0` previews used `scripts/prepare-debug-preview.sh` and a CI debug key. They have no `release.json`. They remain published for history, but new functional releases use the signed release procedure above. The signing script still accounts for their APKs when it enforces the versionCode maximum.

A debug-signed preview cannot be updated in place by a release-signed APK. Uninstall the preview first; local settings will be lost. This is a one-time transition, not the normal update path.
