# Local storage — public #13

LinkSonda keeps settings in DataStore and monitoring data in Room. Everything stays in the app's private storage: there is no account, cloud sync or telemetry. The updater uses `INTERNET` only for GitHub Releases; diagnostic traffic is not connected. Android cloud backup and device transfer are disabled for the app; the policy and OEM limits are described below. The storage layer is app-scoped in `LinkSondaApplication`; screens are connected in later work, so the current UI still shows honest empty states.

## Settings

`AppSettings` is the UI-facing model. `SettingsStore` and `PersistentSettingsStore` keep the same contract and stable preference keys across adapters. `SharedPreferencesSettingsStorage` remains only as the migration source and as a small test adapter.

The production adapter is `DataStoreSettingsStorage`:

- file: `files/datastore/linksonda_settings.preferences_pb`;
- one-time `SharedPreferencesMigration` from the previous `linksonda_settings` preferences file;
- all values are strings encoded by `SettingsCodec`, so missing, unknown or corrupt values fall back to product defaults;
- `MainActivity.attachBaseContext` reads a small synchronous startup snapshot from the previous `linksonda_settings` SharedPreferences file, because it must choose the locale before the first frame;
- DataStore remains authoritative. Construction does not read it and does not block the main thread. Every successful DataStore write also refreshes the startup snapshot, and the old file is the source of the one-time migration;
- `SettingsStore.initialize()` asynchronously reads DataStore, replaces the in-memory state and refreshes the startup snapshot. Writes wait for this load, so a stale snapshot cannot overwrite DataStore. If the process dies between the DataStore commit and the snapshot refresh, the next initialization restores the authoritative values and repairs the snapshot;
- `PersistentSettingsStore` serializes updates. A cancellation before the write leaves the state unchanged; once the write starts it completes non-cancellably, so a committed DataStore value is also published to the `StateFlow`. A regression test covers cancellation after the write.

The model stores updater preferences and the last check/result, ETag, offered version and release notes, and the last notified versionCode. The updater is documented in [in-app updates](UPDATER.md).

## Room schema

Database: `linksonda.db`, schema version 1, exported to `app/schemas/io.github.chuck852.linksonda.storage.LinkSondaDatabase/1.json`.

| Table | Purpose |
| --- | --- |
| `probe_configs` | Immutable probe definition per `(probe_id, revision)`; request JSON and timeout. |
| `profiles` | Profile name, preset metadata, schedule, thresholds and notification settings. |
| `profile_probes` | Ordered profile-to-configuration links, scoped to a revision. |
| `probe_groups` | Ordered named groups inside a profile. |
| `probe_group_members` | Group membership with foreign keys back to the profile's probes. |
| `network_snapshots` | Transport, IP families and validation state at capture time. |
| `probe_results` | Completed or skipped result JSON plus indexed probe, revision, network and recorded time. |
| `incidents` | Incident JSON plus indexed profile, network, open and recovery times. |

Indices cover history by probe/revision/network/time, history by time, incidents by profile/network/open time, incidents by recovery time, and network snapshots by capture time. Room foreign keys keep profile relations consistent.

`ProbeRequest`, `ProbeResult` and `Incident` are stored as JSON with a stable `type` discriminator. `StorageJson` uses contextual serializers for `URI`, `Duration`, `Instant` and `IntRange`; no platform object is serialized directly. The stored observations contain only the fields in the domain model: HTTP status and marker match, DNS response code/count/truncation, TLS version, and ICMP counts/RTT. Response bodies, headers, raw DNS records, certificates, peer addresses and credentials are not stored.

`RoomProfileRepository` saves a profile and its probe configurations in one transaction. A probe revision is immutable: a stale revision is rejected, and the same revision with different request or timeout is rejected. Renaming a probe at the same revision updates the name only. Editing execution parameters creates a new revision through `ProbeConfig.update`, so old results remain attached to the definition that produced them.

`RoomResultRepository.saveResult(result, network)` and `saveIncident(incident, network)` write the network snapshot and the record in one Room transaction. A result whose context has a network must supply a matching snapshot; a skipped result with no network supplies none. Retention runs in its own transaction, so it cannot remove a snapshot between those two writes. Results are observed by `ProbeKey` and optional network; incidents by profile and network.

Persisted probe timeouts, schedule intervals and notification repeat intervals are constrained to whole milliseconds. Room stores these values in milliseconds, and the domain rejects sub-millisecond values so restoring the same revision cannot change execution. Result timing and observation durations remain exact ISO-8601 strings in JSON.

The persisted request is the user's configuration: hosts, URLs, query parameters and expectations. URL userinfo, fragments and common credential query names are rejected, and LinkSonda is not a secret store; opaque secrets must not be placed in a URL or probe name. Observations keep only the domain fields described above. Response bodies, headers, raw DNS records, certificates, peer addresses and credentials are not persisted.

## Retention and deletion

`HistoryMaintenance` uses wall-clock time and a configurable retention (30 days by default):

- `prune(retentionDays)` deletes results older than the cutoff, recovered incidents older than the cutoff, and network snapshots that are both old and no longer referenced by a result or incident;
- `clearHistory()` deletes all results and recovered incidents, and removes networks that are no longer referenced;
- open incidents and the network snapshot referenced by their evidence are never deleted by either operation.

The maintenance API is ready for a future scheduler or startup job; no background worker is added in #13. The existing History screen remains a placeholder until the monitoring engine writes results and the UI observes them.

## Backup and local-data policy

`AndroidManifest.xml` sets `android:allowBackup="false"`. `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` additionally exclude the app's root, file, database, shared-preference and external domains from cloud backup and device transfer. This covers supported Android backup mechanisms and keeps diagnostic history and settings out of Android cloud backup and standard device-to-device transfer.

OEM migration or phone-cloning tools are outside the app's control and may copy app-private data even when Android backup is disabled. LinkSonda has no account or cloud endpoint and does not upload this data itself; users who need strict isolation must disable vendor cloud or migration tools. Local data is protected by the Android app sandbox and device encryption, not by a separate LinkSonda encryption key.

## Migration policy

The settings migration from SharedPreferences to DataStore is covered by a Robolectric test. Room schema version 1 is the initial schema and is exported for future migration tests. Any later Room schema change must add a migration and a `MigrationTestHelper` test; destructive fallback is not used.

## Verification

- `RoomStorageTest`: profile CRUD, relations, stale/conflicting revisions, results, incidents, network snapshots, on-disk reopen.
- `HistoryMaintenanceTest`: retention cutoff, open-incident preservation, network cleanup, full history deletion.
- `DataStoreSettingsStorageTest`: legacy SharedPreferences migration, authoritative DataStore writes, startup snapshot repair after an interrupted write.
- `SettingsStoreTest`/`AppSettingsTest`: codec round-trip, corrupt-value fallback, initialization-before-write, cancellation after the write and updater-reserved fields.
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` passes (217 JVM tests, including updater tests).
- API 36 emulator: changing History retention to 90 days survives force-stop and relaunch; the DataStore preferences file is present in app-private storage.
