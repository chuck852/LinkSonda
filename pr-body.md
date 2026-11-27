## What changed

Adds the public #57 updater while keeping the formal dependency on #7 open. No production key, official signed release or tag was created.

- GitHub Releases API only; no backend, account, token or telemetry.
- WorkManager periodic checks (about 24 hours, connected network, unique work), manual check in Settings, Stable/Preview channels, first-run opt-in.
- ETag/304, rate-limit and `Retry-After`/`X-RateLimit-Reset`, timeouts, allowlisted HTTPS GitHub hosts and bounded responses.
- Checks choose by monotonic `versionCode`, exclude drafts, downgrade, `testOnly`, channel mismatches and already-current versions.
- User-started download with progress/cancel; verifies `release.json`, applicationId, versionCode, minSdk, size, SHA-256, APK package/version and signer certificate against the installed app.
- PackageInstaller session with the system confirmation and the Android unknown-sources permission flow.
- Update notifications deduplicated by the last notified versionCode.
- Room, DataStore, profiles and history are not cleared; in-place update keeps app data.
- RU/EN strings and the existing Material 3 settings/first-run design.
- Adds `INTERNET` for GitHub Releases and `REQUEST_INSTALL_PACKAGES` for the user-confirmed install only. No diagnostic endpoint is activated; Developer 1's `feat/dns-probes` tree had no overlapping files.

## Verification

- `testDebugUnitTest lintDebug assembleDebug assembleRelease`: **BUILD SUCCESSFUL**, 217 JVM tests, 0 failures/errors/skips; lint has only existing version/dependency warnings.
- Unit coverage: release selection by channel/versionCode, signer mismatch, testOnly, unsafe metadata, ETag/304 preservation, rate-limit retention, URL host allowlist.
- E2E on a separate API 36 emulator with a temporary test key outside the repository:
  - versionCode 2 → 3 release APKs signed by the same key installed with `adb install -r`; Settings and History retention (90 days) survived and the app showed `0.2.1 (3)`;
  - downgrade versionCode 2 over 3 rejected with `INSTALL_FAILED_VERSION_DOWNGRADE`;
  - an APK signed by a different test key rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- The updater's automatic check ran on the emulator and reached GitHub; without a published `release.json` it correctly reported no update metadata.
- Developer 1's emulator (`emulator-5562`) was not touched.

## Limitations / follow-up

- Official acceptance remains blocked by #7: `release.json` assets and the production signer must exist before an official update can be offered. This PR must not publish a signed official release.
- The full PackageInstaller confirmation UI was not automated end-to-end because there is no official `release.json` release to offer; the platform session/permission code and the same-signer upgrade path are in place.
- Room schema remains v1 from #13; this PR does not change it.

Refs #57.
