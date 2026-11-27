# Architecture

## Current build

`:app` is a native Android application written in Kotlin with Jetpack Compose and Material 3. `MainActivity` displays the starter screen. The project currently has one Android module, JVM tests for the debug build configuration, and an Android CI workflow. It uses no backend or account system.

## Planned components

- UI presents probe results, their age and context, monitoring controls, and local history.
- Domain models represent targets, schedules, results, and incidents. Unit tests cover thresholds, missing results, and network changes.
- Probe runners perform bounded TCP, TLS, HTTP and DNS operations using platform trust defaults. Additional transports depend on device support and validation.
- Monitoring uses a scheduler and an explicit, user-started session. A foreground service is a candidate for periodic work. Stopping a session must cancel active I/O and close sockets. Long device sleep and vendor power policies may introduce gaps.
- Local persistence stores actual results and incidents. Scheduled slots missed through sleep, a permission denial, or rate limits preserve the last result and age without counting as network failures.

Network checks use an explicit concurrency limit and a shared request budget across manual and scheduled modes. Built-in endpoints require verified permitted use. Respect cooldowns, avoid catch-up bursts after sleep, and use controlled or explicitly authorized targets for intensive tests. A failed probe describes its target and observed error.
