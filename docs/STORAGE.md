# Local storage — public #13

LinkSonda keeps settings in DataStore and monitoring data in Room. Everything stays in the app's private storage: there is no account, cloud sync, telemetry or network permission. The storage layer is app-scoped in `LinkSondaApplication`; screens are connected in later work, so the current UI still shows honest empty states.

## Settings

`AppSettings` is the UI-facing model. `SettingsStore` and `PersistentSettingsStore` keep the same contract and stable preference keys across adapters. `SharedPreferencesSettingsStorage` remains only as the migration source and as a small test adapter.

The production adapter is `DataStoreSettingsStorage`:

- file: `files/datastore/linksonda_settings.preferences_pb`;
- one-time `SharedPreferencesMigration` from the previous `linksonda_settings` preferences file;
- all values are strings encoded by `SettingsCodec`, so missing, unknown or corrupt values fall back to product defaults;
- a synchronous startup snapshot exists because `MainActivity.attachBaseContext` must choose the locale before the first frame;
- writes run through the DataStore transaction API on the caller's IO dispatcher.

The model also reserves the fields needed by the future updater (#57): whether automatic checks are enabled, the selected channel (`STABLE` or `PREVIEW`), the last check time, the last ETag and the last offered versionCode. No updater, notification, download or installation code exists yet.

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

`RoomResultRepository` saves network snapshots, results and incidents. Results are observed by `ProbeKey` and optional network; incidents by profile and network.

## Retention and deletion

`HistoryMaintenance` uses wall-clock time and a configurable retention (30 days by default):

- `prune(retentionDays)` deletes results older than the cutoff, recovered incidents older than the cutoff, and network snapshots that are both old and no longer referenced by a result or incident;
- `clearHistory()` deletes all results and recovered incidents, and removes networks that are no longer referenced;
- open incidents and the network snapshot referenced by their evidence are never deleted by either operation.

The maintenance API is ready for a future scheduler or startup job; no background worker is added in #13. The existing History screen remains a placeholder until the monitoring engine writes results and the UI observes them.

## Migration policy

The settings migration from SharedPreferences to DataStore is covered by a Robolectric test. Room schema version 1 is the initial schema and is exported for future migration tests. Any later Room schema change must add a migration and a `MigrationTestHelper` test; destructive fallback is not used.

## Verification

- `RoomStorageTest`: profile CRUD, relations, stale/conflicting revisions, results, incidents, network snapshots, on-disk reopen.
- `HistoryMaintenanceTest`: retention cutoff, open-incident preservation, network cleanup, full history deletion.
- `DataStoreSettingsStorageTest`: legacy SharedPreferences migration and persisted writes.
- `SettingsStoreTest`/`AppSettingsTest`: codec round-trip, corrupt-value fallback and updater-reserved fields.
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` passes (132 JVM tests).
- API 36 emulator: changing History retention to 90 days survives force-stop and relaunch; the DataStore preferences file is present in app-private storage.
