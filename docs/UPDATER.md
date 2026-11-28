# In-app updates

Public #57 adds a built-in updater for official LinkSonda releases. It reads GitHub Releases directly; there is no backend, account, GitHub token or telemetry. The updater is a separate network path from the diagnostic engine: a failed update check never changes a probe or monitoring status.

## Release channels

- **Stable** accepts non-prerelease GitHub releases only.
- **Preview** accepts pre-releases and stable releases.

The updater compares monotonically increasing `versionCode` values from `release.json`, never tag strings. Draft releases, already installed or older versions, `testOnly` bundles, debug APKs, unsigned APKs and APKs signed by a different certificate are not offered.

## Checks

`UpdateWorker` uses WorkManager with a unique periodic request (about every 24 hours) and a connected-network constraint. It never starts a foreground service. Automatic checks are off by default and can be enabled during first-run setup or in Settings.

A manual check uses the same code path. It sends `If-None-Match` when an ETag is stored and handles `304 Not Modified`. A `403`/`429` response records a rate-limit result and honours `Retry-After` or `X-RateLimit-Reset` when present. Timeouts, offline devices and GitHub errors are recorded separately from monitoring. The last check time, result, available version and release notes are shown in Settings.

By default the updater uses `https://api.github.com`. A local fixture source can be injected only at build time with the Gradle property `updaterTestUrl`; that build sets `UPDATER_TEST_SOURCE=true` and enables cleartext for that fixture only. A normal production build leaves the URL empty, keeps cleartext disabled and the test-host branch unreachable. A build with the test source enabled carries a false-by-default manifest marker; Android CI and `prepare-release.sh` reject any release APK where `UPDATER_TEST_SOURCE` is true or cleartext traffic is enabled. The resulting test APK is never published. GitHub API requests are limited to `api.github.com`; release metadata and APK downloads are limited to HTTPS GitHub release hosts. Redirects are followed only within an explicit allowlist, and response/APK sizes are bounded. The client uses OkHttp with per-call `callTimeout`; cancellation calls `Call.cancel()` and closes the response body, including a blocking slow-drip body read. It has no credentials.

## Verification before install

A check only reads release metadata. The APK is downloaded only after the user taps **Download update**, with progress and cancellation. Before any installer handoff, the app verifies:

- `release.json` schema and channel match the selected channel;
- tag, `applicationId`, positive `versionCode`, `minSdk` and APK name;
- `testOnly` is absent and the release is not a draft;
- the APK's exact size and SHA-256 match the metadata;
- the archive package, versionCode and minSdk are compatible;
- the APK is not debuggable;
- the APK certificate SHA-256 equals both `release.json` and the certificate of the installed app.

The installed app certificate is the trust anchor; downloaded metadata alone is not trusted. SHA-256 is an integrity check and does not replace the signature check. A failed check deletes the partial file and does not open the installer.

## Installation

After verification the user taps **Install update**. If Android has not allowed installs from LinkSonda, the app opens the system **Install unknown apps** screen; the user must return and confirm. Installation uses a `PackageInstaller` session and the system confirmation dialog. Cancelling the system prompt or the download leaves the installed app unchanged.

Android keeps app data during an in-place update when the application ID and signing certificate match. LinkSonda does not delete its DataStore or Room database before installation. A debug-preview or differently signed build cannot be updated in place; it requires a one-time uninstall and loses local data.

## Notifications

When a newer release is found, the app posts one local update notification if notifications are permitted. The last notified `versionCode` is stored, so repeated checks for the same release do not duplicate it. The notification opens the app; it does not download or install anything by itself.

## Permissions and privacy

The manifest declares:

- `INTERNET` for GitHub API and release assets only;
- `REQUEST_INSTALL_PACKAGES` for the user-confirmed PackageInstaller flow.

It does not declare a background location, account, analytics or diagnostic endpoint. The probe runners are still not connected to production UI and no diagnostic endpoint is activated. The app does not send a GitHub token or device identifier.

## Testing boundary

Unit tests use controlled GitHub/`release.json` fixtures and exercise ETag/304 handling, persisted retry enforcement, automatic-check consent, versionCode/channel selection, the metadata scan limit, one-signer/field rejection and the test-source boundary. The full local command passes 310 JVM tests with no failures, including slow-drip deadline and cancellation-during-body-read tests.

E2E used a release APK with the build-time local fixture URL, a temporary test key and a separate API 36 emulator. The app discovered versionCode 3 through its Settings UI, downloaded it with progress, verified the APK, requested the Android unknown-sources permission, showed the system PackageInstaller confirmation, cancelled it once, then retried and completed the update. It launched version 0.2.1 (3), with History retention still set to 90 days. A downgrade and an APK signed by a different test key were rejected. No official release, production key, test release or tag was created; the temporary key was removed after the run.
