# Architecture

## Current build

`:app` is a native Android application written in Kotlin with Jetpack Compose and Material 3. `MainActivity` displays the starter screen. The project currently has one Android module, JVM tests for the debug build configuration and domain model, and an Android CI workflow. It uses no backend or account system.

## Domain contracts

The Kotlin domain package defines typed requests and expectations for TCP, TLS, HTTP, system and direct DNS, DoH, DoT, and ICMP. Presets group probes. Profiles add scheduling, thresholds, notifications, and probe groups.

History and failure streaks are scoped by `ProbeId`, configuration revision, and network. Endpoint, protocol, criteria, and timeout changes advance the revision through `ProbeConfig.update`. Renames keep it. A run retains its initial key even if settings change during execution. Storage must persist the revision with its definition and reject stale or conflicting writes for the same ID. Independently editable copies receive a new ID. History uses wall-clock instants. Elapsed times and observed phases use durations measured by the runner with a monotonic clock. Omitted phases have no measurement. Probe status, lifecycle, freshness and aggregate status can be represented independently. Incident recovery may be recorded at or after opening, with diagnostic hypotheses tied to evidence in the same network context.

Completed observations carry protocol facts independently of the outcome. HTTP stores status and optional marker match, DNS stores available RCODE, answer count and truncation, TLS stores the negotiated version, and ICMP stores sent and received counts with optional RTT statistics. Combined transports can supply several observations. The model stores no response bodies, headers, raw DNS records, certificates, or peer addresses.

An attempted check whose outcome is obscured by OS limits, Doze, or a network change is inconclusive and can retain partial facts. Failure streaks exclude inconclusive results and skipped slots. A slot in which no attempt starts is skipped. Permission denial has a dedicated reason before execution and when it interrupts an attempted check.

`ProbeRunner` is a suspending transport boundary. Its admission contract accounts for each observable outbound attempt, including retries, redirects and fallbacks. Manual and background calls use the same policy and endpoint and infrastructure scope. Restricted responses and policy skips have separate types. Repository contracts expose Kotlin Flow, so UI code can observe domain data independently of transport and storage choices. These contracts are the boundary for future transport, monitoring, and storage implementations.

## Planned components

- UI presents probe results, their age and context, monitoring controls, and local history.
- Probe runners perform bounded TCP, TLS, HTTP and DNS operations using platform trust defaults. Additional transports depend on device support and validation.
- Monitoring uses a scheduler and an explicit, user-started session. A foreground service is a candidate for periodic work. Stopping a session must cancel active I/O and close sockets. Long device sleep and vendor power policies may introduce gaps.
- Local persistence stores actual results and incidents. Scheduled slots missed through sleep, a permission denial, or rate limits preserve the last result and age without counting as network failures.

Network checks use an explicit concurrency limit and a shared request budget across manual and scheduled modes. Built-in endpoints require verified permitted use. Respect cooldowns, avoid catch-up bursts after sleep, and use controlled or explicitly authorized targets for intensive tests. A failed probe describes its target and observed error.
