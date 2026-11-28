# Monitoring profiles — public #22

Profiles are stored locally in Room and connected to the production UI. The screen-level flow is:

- **Presets tab** lists user profiles and an empty built-in section.
- **Create** saves a new profile with no checks and the product defaults.
- **Edit** changes the name, schedule, thresholds, notifications and groups, and can add, edit or delete checks.
- **Copy** creates an independent snapshot with a new profile id and new probe ids and revisions.
- **Activate** stores the selected profile id in settings. It does not start monitoring.
- **Delete** removes the profile and its checks from this device; deleting the active profile clears the selection.

The built-in endpoint catalog is intentionally not populated in this build. The formal dependency on public #20 remains: verified endpoints and final catalog integration are separate work. No check is created from an unverified endpoint catalog, and the UI shows an explicit empty state instead.

## Defaults and inheritance

A new profile starts with:

| Setting | Value |
| --- | --- |
| Configured interval | 60 s |
| Maximum concurrent checks | 4 |
| Consecutive failures to DOWN | 3 |
| Consecutive successes to recover | 2 |
| Incident notifications | Off |
| Checks and groups | Empty |

Copying is **snapshot inheritance**, not a live link. Every copied probe receives a new `ProbeId` and revision 1, and group membership is remapped to those ids. Editing the copy never mutates the source or a future built-in original. The source must still be copied with a new id; the domain operation in `ProfileOperations.kt` owns this rule.

## Intervals, groups and restrictions

The configured interval is a user target, not a promise of execution frequency. A future monitoring engine and the shared admission controller may run checks less often because of endpoint policies, load limits, Doze, network changes or cooldowns. The editor shows both the configured interval and this distinction.

The interval choices are 60 s and 300 s. The 10 s option is visible but disabled: `docs/PROBE_POLICY.md` allows 10 s only for the user's own or explicitly allowed endpoints, and the verified catalog is not connected. The language is UI copy, not a hidden runtime fallback.

Groups are created with their first check. A group can be renamed; it disappears when its last check is removed. Existing ungrouped checks are shown in a localized "Ungrouped" group without changing the stored profile. The effective settings card shows the configured interval and concurrency limit, and states that actual execution can be less frequent.

## Empty and missing states

- With no active profile, Overview and Checks show a path to **Open profiles**; no group, check or result is invented.
- A profile with no checks keeps honest empty states in Overview, Checks and the profile editor.
- If a selected profile was deleted or is no longer present, the stale selection resolves to no active profile rather than a fabricated one.
- First run lists the stored profiles and their check count/interval. Choosing a profile saves its id; choosing manual checks keeps the selection empty. Neither choice starts monitoring.
- A check editor opened from a profile targets that profile, including a profile that is not active.

The profile editor has no network controls. Saving, copying or activating a profile starts no request and no background work. This build sends no check traffic and shows only UNKNOWN/MISSING check states in the production overview.

## Persistence and tests

`RoomProfileRepository` remains the storage boundary. `MainActivity` observes `observeProfiles()` and persists edits, copies and deletes. `ProfileEditorState` validates name, interval, concurrency and thresholds before a profile is saved. Probe checks continue to use the revision rules in `STORAGE.md`.

Covered by JVM tests:

- `ProfileOperationsTest`: defaults, deep-copy independence, new probe ids/revisions, add/replace/move, empty-group removal, rename rules.
- `ProfileEditorStateTest`: round-trip, valid settings, blocked 10 s interval, field errors.
- `ProfileOverviewTest`: active profile projection, groups, ungrouped checks, no invented results, empty profile.
- `AppNavigationTest`: profile editor route and check editor target profile survive recreation.
- `ProbeEditorStateTest`: blank group name blocks saving.

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` is the final local gate. E2E verification must use controlled fixtures and must not contact public endpoints.
