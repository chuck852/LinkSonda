# Changelog

## Unreleased

- Added the four-tab navigation shell with Overview, Checks, Presets and History, a saveable back stack, group filtering and a settings entry point.
- Added the overview with aggregate state, freshness, monitoring, network and VPN context, active profile, availability bars and manual-check state. Production remains empty until real monitoring is connected.
- Added explicit empty, unknown, running, partial, down and up states plus a debug-only demo harness and Compose previews.
- Added large-font and small-screen adaptations, including wrapping status badges and icon-only bottom navigation with accessible labels.
- Added 38 JVM tests covering navigation, overview state and demo consistency.
- Added the LinkSonda Material 3 design system with adaptive, accents and static color modes.
- Added light, dark and system brightness, semantic result tones with text and icons, and a 650 ms adaptive transition that respects system animation settings.
- Added Compose previews, contrast and palette tests, a debug screenshot harness, and design-system documentation.

## [v0.1.0-dev.1](https://github.com/chuck852/LinkSonda/releases/tag/v0.1.0-dev.1) — 2026-11-27

- Added the initial Android application with a Compose Material 3 starter screen.
- Added Gradle Wrapper builds, JVM tests, Android lint, and CI-generated debug APKs.
- Added build documentation, contributor resources, and dependency update configuration.
- Added the LinkSonda launcher artwork and a monochrome layer for supported launchers.
- Published the technical preview APK with a debug signature, SHA-256, and license notices. Network diagnostics are planned.
