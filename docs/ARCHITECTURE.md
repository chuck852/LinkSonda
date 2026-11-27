# Architecture

## Current build

`:app` is a native Android application written in Kotlin with Jetpack Compose and Material 3. `MainActivity` hosts `LinkSondaApp`, which owns the four-tab navigation shell and the overview. The project currently has one Android module, JVM tests for the debug build configuration, domain model, theme, navigation, overview state, check editor and settings persistence, and an Android CI workflow. It uses no backend or account system.

## Navigation and presentation

`AppNavigationState` is a saveable list of routes: top-level tabs, filtered Checks and Settings. Selecting a tab returns to its root; group cards and Settings push one route; the back action pops it. The shell has no separate navigation dependency.

Production starts from `OverviewUiState.Empty`, so availability is UNKNOWN, freshness is missing, network context is unknown, and no profile, groups or results are invented. `ChecksScreen` shows all probes or one group filter; Presets and History show empty states. The UI listens to repositories only after the monitoring engine and storage-backed screens are connected.

The debug source set contains `OverviewDemoActivity`, demo scenarios and Compose previews. Every demo screen has a visible banner, sends no request and persists nothing. Debug classes and preview resources are excluded from release builds. See [navigation and overview](NAVIGATION.md) for screens, states and accessibility checks.

## Checks and details — public #16

The Checks tab keeps `ProbeConfig` and `ProbeState` in `ProbeListItem`, so the list and details screen preserve the configuration revision and every optional observation. `ProbeListItem` rejects a state whose context key differs from the configuration revision. Search and filters are a pure function over the list and are unit-tested separately from Compose. All P0 request families are mapped to a presentation protocol: TCP, TLS, HTTP, HTTPS, system DNS, DNS over UDP/TCP, DoH, DoT and ICMP. IPv6 endpoints with ports use brackets.

The details screen renders protocol-specific configuration, the latest `ProbeResult.Completed` or `Skipped`, freshness, outcome, measured phases and optional observations. Missing facts are rendered as not observed or not measured; the UI never substitutes zero. Retry and edit use nullable callbacks with a `ProbeKey`; edit opens the check editor, while retry stays disabled until the runner is connected. See [checks and details](CHECKS.md).

## Check editor — public #19

`ProbeEditorState` is a pure, saveable presentation model for all supported request families. It keeps partially typed values as strings, validates hosts, IPv4/IPv6, ports, URLs, timeouts, HTTP status ranges, DNS fields and TLS server names, and is the only place that constructs `ProbeConfig`. A protocol change resets fields that do not apply to the new request family. Editing a request or timeout delegates to `ProbeConfig.update`, which advances the revision; renaming keeps it. TLS and DoT require a DNS server name and certificate verification cannot be disabled.

`ProbeEditorActions` is the UI boundary: `onSave` receives a `ProbeDraft` and `onTest` receives a `ProbeConfig`; a completed test result keeps a snapshot of the tested probe ID, request and timeout and is hidden when that snapshot no longer matches the draft. Production leaves both null until `ProfileRepository` storage and `ProbeRunner` transport are connected, so the editor shows the exact limitation instead of adding a temporary repository. The debug harness implements the callbacks with `DemoProfileRepository` and `DemoProbeRunner`; the demo runner authorizes through `ProbeAdmission`, returns a domain result and sends no traffic. Neither demo class is present in release builds. See [the check editor](CHECK_EDITOR.md).

## Settings, localization and first run — public #17

`AppSettings` keeps language, theme mode and brightness, monitoring and notification preferences, history retention, the selected profile, the onboarding flag and whether a notification-permission request was shown. It also reserves the updater fields for #57: automatic-check preference, channel, last-check time, ETag and last offered versionCode. `SettingsStore` is the app-facing contract; `SettingsStorage` isolates the platform adapter. The production adapter is DataStore with a one-time SharedPreferences migration, behind `PersistentSettingsStore`. `attachBaseContext` reads a small last-written SharedPreferences startup snapshot for the locale; it never reads DataStore on the main thread. DataStore remains authoritative; successful writes refresh the snapshot, and initialization loads DataStore before any write and repairs a stale snapshot.

Room stores probe configurations, profiles, results, network snapshots and incidents. The schema, indices, revision rules, atomic result/network writes, retention maintenance, backup exclusions and migration policy are documented in [local storage](STORAGE.md). The storage layer is app-scoped in `LinkSondaApplication`; UI screens and the probe runner are connected in later work.

`MainActivity` reads the saved language in `attachBaseContext`, so the first frame after a cold start uses the selected resources, and recreates the activity after a language change. On API 33+ `LocaleManager.setApplicationLocales` keeps the platform per-app language picker in sync; `SYSTEM` clears that override. `res/xml/locales_config.xml` exposes English and Russian to the platform picker. `LinkSondaApp` passes the saved theme mode and brightness to `LinkSondaTheme` and gates the shell behind the first-run screen until onboarding completes.

The first-run screen explains that checks run from the device, LinkSonda is designed for local monitoring, and no placeholder profile is invented. It offers manual checks or an available profile. `POST_NOTIFICATIONS` is requested only when the user enables notifications in Settings. Android remains the source of truth: the screen rechecks permission on resume, stores that a request was shown, turns the effective switch off after revocation while keeping the user's preference, shows a live-region rationale and a link to Android notification settings, and does not block manual checks or the editor. See [settings, localization and first run](SETTINGS.md).

## Theme and design system

`LinkSondaTheme` owns the root Material 3 color scheme, shapes and semantic result colors. It accepts light, dark or system brightness and adaptive, accents or static color modes. Only a `ConfirmedAggregateStatus` can change the adaptive palette; probe lifecycle is rendered separately, including a local blue running state. All root roles animate together for 650 ms with ease-in-out, while sizes and positions stay fixed. Compose's duration scale disables the transition when system animations are off. Contrast tests cover static schemes and sampled transitions. See [theme and design system](THEME.md) for tokens, previews, screenshots and verification.

## Domain contracts

The Kotlin domain package defines typed requests and expectations for TCP, TLS, HTTP, system and direct DNS, DoH, DoT, and ICMP. Presets group probes. Profiles add scheduling, thresholds, notifications, and probe groups.

History and failure streaks are scoped by `ProbeId`, configuration revision, and network. Endpoint, protocol, criteria, and timeout changes advance the revision through `ProbeConfig.update`. Renames keep it. A run retains its initial key even if settings change during execution. Storage must persist the revision with its definition and reject stale or conflicting writes for the same ID. Independently editable copies receive a new ID. History uses wall-clock instants. Elapsed times and observed phases use durations measured by the runner with a monotonic clock. Omitted phases have no measurement. Probe status, lifecycle, freshness and aggregate status can be represented independently. Incident recovery may be recorded at or after opening, with diagnostic hypotheses tied to evidence in the same network context.

Completed observations carry protocol facts independently of the outcome. HTTP stores status and optional marker match, DNS stores available RCODE, answer count and truncation, TLS stores the negotiated version, and ICMP stores sent and received counts with optional RTT statistics. Combined transports can supply several observations. The model stores no response bodies, headers, raw DNS records, certificates, or peer addresses.

An attempted check whose outcome is obscured by OS limits, Doze, or a network change is inconclusive and can retain partial facts. Failure streaks exclude inconclusive results and skipped slots. A slot in which no attempt starts is skipped. Permission denial has a dedicated reason before execution and when it interrupts an attempted check.

`ProbeRunner` is a suspending transport boundary. Its admission contract accounts for each observable outbound attempt, including retries, redirects and fallbacks. Manual and background calls use the same policy and endpoint and infrastructure scope. Restricted responses and policy skips have separate types. Repository contracts expose Kotlin Flow, so UI code can observe domain data independently of transport and storage choices. These contracts are the boundary for future transport, monitoring, and storage implementations.

## TCP and TLS transport — public #10

`SocketProbeRunner` implements `ProbeRunner` for TCP Connect and TLS handshake. It authorizes DNS resolution for hostnames and each resolved-address socket attempt before opening it; the TLS handshake is part of that socket attempt. Admission uses the normalized endpoint host as endpoint and infrastructure identity, so changing port, transport or SNI cannot bypass the shared policy. IPv4 and IPv6 literals and hostname resolution with an optional family filter are supported. Matching addresses are tried in order, preferring families present in the network snapshot. DNS, TCP and TLS failures stay separate, and all elapsed and phase durations use a monotonic clock.

TLS uses the platform trust store and HTTPS endpoint identification. Certificate and hostname verification cannot be disabled. SNI is sent only for DNS names, and negotiated TLS 1.2 or 1.3 is recorded. DNS resolution runs on a bounded daemon worker pool; capacity exhaustion is inconclusive rather than a network failure, and a blocking resolver does not delay the run timeout or external cancellation. Timeouts and cancellation close the active socket promptly.

The runner is internal to the app and is not connected to production UI. The manifest still has no `INTERNET` permission and no public endpoint is activated. The HTTP runner is described below; the ICMP runner, Android network binding and engine integration remain planned. See [network transports](NETWORK.md).

## DNS transport — public #11

`DnsProbeRunner` implements `ProbeRunner` for system DNS and direct DNS over UDP or TCP. The system path supports A and AAAA, authorizes a lookup with the question host as endpoint and `system-dns` as infrastructure, and records only the count of matching resolved addresses; RCODE and truncation stay unset because the platform resolver does not expose the DNS header. Unknown names are `DNS` failures, permission denial and bounded-worker exhaustion are inconclusive, and the selected system server is not invented.

The direct path uses the configured resolver. A resolver hostname is resolved through the bounded system resolver with its own admission; an IP literal needs no DNS lookup. Every query authorizes the normalized question host as endpoint and the normalized resolver host as infrastructure; port, UDP/TCP transport and question type cannot bypass the shared identity, and each resolved resolver address and the TCP fallback are separate attempts. Transport failures continue with the next resolved address; the remaining run timeout is divided across the remaining addresses, so a silent attempt cannot consume the whole budget. Inconclusive results and a valid DNS response with an unexpected RCODE or missing required answer stop the run. Queries and responses validate the transaction ID, matching question, header and section structure, compression bounds and loops, and trailing bytes. Answer records must have class IN and an owner reachable from the question through a valid CNAME chain; A and AAAA RDATA must have their required lengths. Other record types get structural validation only, not semantic RDATA validation. EDNS extended RCODE is read from an OPT record and combined with the header RCODE; full EDNS is not implemented. The runner records only the wire facts exposed by `DnsObservation`: actual complete-answer count, RCODE and truncation. UDP send is not success; a datagram must be answered and validated. A TC header triggers a separately authorized TCP fallback even when later sections are partially truncated, and keeps `truncated = true`; failures and overall timeouts during the fallback keep only complete UDP answer facts. The TCP exchange, including slow-drip reads, shares the attempt deadline. Phase durations sum across attempts. TTLs, record data, peer addresses and the resolver identity are not persisted. Direct timeouts and cancellation close sockets, and external cancellation propagates.

The DNS runner is internal and is not connected to production UI. The manifest still has no `INTERNET` permission, and DoH, DoT and ICMP remain unimplemented. See [network transports](NETWORK.md).

## HTTP and HTTPS transport — public #15

`HttpProbeRunner` implements `ProbeRunner` for `ProbeRequest.Http` GET and HEAD requests over HTTP or HTTPS. It uses OkHttp with manual redirects disabled in the client. Each hop is authorized before the request with the normalized host as endpoint and infrastructure identity; a redirect target is a separate hop and a separate admission, and a denial returns `Skipped` without sending that request. Ports and schemes do not create a new identity in the runner. The redirect limit is three, and a missing `Location`, a non-HTTP(S) target, user info, or an HTTPS-to-HTTP downgrade is a `HTTP` failure.

HTTPS keeps OkHttp's platform trust store, certificate validation and hostname verification; the production constructor has no opt-out. HTTP is available only through an explicit `http` URL, and the manifest permission and cleartext policy stay for integration. There are no automatic retries in this runner; future retry scheduling belongs to the shared controller. 429 and 503 return `Restricted(RATE_LIMITED)` with `Retry-After` parsed as seconds or an RFC 1123 date; an overflow or invalid value leaves `retryAt` unset instead of becoming a transport failure. 403 returns `Restricted(ACCESS_DENIED)`, and 503 is a server restriction rather than a transport failure. Status mismatch is `UNEXPECTED_HTTP_STATUS`; a marker outside the first 64 KiB is not found and gives `UNEXPECTED_RESPONSE`. Bodies are closed and raw bodies, headers, cookies and secrets are not persisted. The approved `HttpObservation` exposes only status and marker match, so reuse and cache are not invented; cache is not configured.

Phases from OkHttp's `EventListener` cover DNS, TCP connect, TLS handshake and time from request headers end to response headers start. Durations sum across redirects; reused connections omit DNS/TCP/TLS. Total elapsed uses `System.nanoTime()`. Per-hop `callTimeout` and the outer run timeout bound the request; timeout is `Failed(TIMEOUT)`, and external cancellation propagates and cancels the call.

The runner is internal and not connected to production UI. The manifest still has no `INTERNET` permission, and no public endpoint is activated. DoH, DoT and ICMP remain unimplemented. See [network transports](NETWORK.md).

## Planned components

- UI presents probe results, their age and context, monitoring controls, and local history.
- Probe runners perform bounded HTTP and DNS operations using platform trust defaults; TCP Connect, TLS handshake, system DNS, direct UDP/TCP DNS, and HTTP/HTTPS are implemented behind `ProbeRunner`. DoH, DoT and ICMP depend on device support and validation.
- Monitoring uses a scheduler and an explicit, user-started session. A foreground service is a candidate for periodic work. Stopping a session must cancel active I/O and close sockets. Long device sleep and vendor power policies may introduce gaps.
- Local persistence stores actual results and incidents. Scheduled slots missed through sleep, a permission denial, or rate limits preserve the last result and age without counting as network failures.

Network checks use an explicit concurrency limit and a shared request budget across manual and scheduled modes. Built-in endpoints require verified permitted use. Respect cooldowns, avoid catch-up bursts after sleep, and use controlled or explicitly authorized targets for intensive tests. A failed probe describes its target and observed error.
