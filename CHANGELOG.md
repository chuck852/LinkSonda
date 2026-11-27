# Changelog

## [v0.2.0](https://github.com/chuck852/LinkSonda/releases/tag/v0.2.0) — 2026-11-27

Published as a debug-signed pre-release. Real manual and background checks are not available yet.

- Added persisted settings for adaptive, accents-only and static color modes, system/light/dark brightness, system/English/Russian language, monitoring and notification preferences, history retention and the selected profile.
- Added a localized settings screen and a first-run flow that explains local monitoring and offers manual checks or an available profile.
- Added contextual notification permission: Android asks only when notifications are enabled, and a denial leaves manual checks and the editor usable.
- Added complete English/Russian resources, per-app locale configuration, and 48 dp/accessibility semantics for the new screens.
- Added a universal check editor for all P0 protocols with dynamic fields, validation, protocol-aware field reset, revision handling and progressive disclosure of advanced options.
- Added check creation and editing through the domain models, an explicit TLS server-name requirement, read-only certificate validation and honest save/test limitations while storage and the network engine are not connected.
- Wired the debug editor to `ProbeRunner` and `ProfileRepository` implementations that keep all demo state in memory and send no network traffic.
- Added a domain-backed Checks list with search, group/status/protocol filters and an explicit unknown/stale/timeout/inconclusive/restricted/skipped presentation.
- Added a check details screen with protocol-specific configuration, preserved revisions, latest result, measured phases and observations. Missing measurements are shown as not observed, never as zero.
- Wired edit through `ProbeKey` callbacks to the check editor; retry remains disabled until the runner is connected.
- Added the four-tab navigation shell with Overview, Checks, Presets and History, a saveable back stack, group filtering and a settings entry point.
- Added the overview with aggregate state, freshness, monitoring, network and VPN context, active profile, availability bars and manual-check state. Production remains empty until real monitoring is connected.
- Added explicit empty, unknown, running, partial, down and up states plus a debug-only demo harness and Compose previews.
- Added large-font and small-screen adaptations, including wrapping status badges and icon-only bottom navigation with accessible labels.
- Added 96 JVM tests covering navigation, overview state, checks, editor validation, execution-snapshot test-result binding, concurrent settings updates, failed settings writes, locale/theme values and demo consistency.
- Added the LinkSonda Material 3 design system with adaptive, accents and static color modes.
- Added light, dark and system brightness, semantic result tones with text and icons, and a 650 ms adaptive transition that respects system animation settings.
- Added Compose previews, contrast and palette tests, a debug screenshot harness, and design-system documentation.
- Bumped the app to version `0.2.0` and prepared the debug-signed UI Developer Preview release candidate with SHA-256 and license notices. It has no network permission and no real monitoring.

## [v0.1.0-dev.1](https://github.com/chuck852/LinkSonda/releases/tag/v0.1.0-dev.1) — 2026-11-27

- Added the initial Android application with a Compose Material 3 starter screen.
- Added Gradle Wrapper builds, JVM tests, Android lint, and CI-generated debug APKs.
- Added build documentation, contributor resources, and dependency update configuration.
- Added the LinkSonda launcher artwork and a monochrome layer for supported launchers.
- Published the technical preview APK with a debug signature, SHA-256, and license notices. Network diagnostics are planned.
