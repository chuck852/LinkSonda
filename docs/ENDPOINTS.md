# Control endpoint catalog

This document records the verification of candidate control endpoints for LinkSonda. It is a decision record, not a claim that any public service is available, reachable or suitable for monitoring. The machine-readable form is [catalog.json](endpoints/catalog.json) with [catalog.schema.json](endpoints/catalog.schema.json).

## Verification status

Documentation review was completed on 2026-11-28. No diagnostic probe or load test was sent to a candidate endpoint, including from Russian Wi-Fi, mobile networks or VPN. Official terms and documentation pages were fetched to verify permission; those fetches included the `rt.ru` root (which redirected to a regional site) and the `gosuslugi.ru` terms page (which returned an anti-bot shell). Those fetches were source review, not availability measurements. The owner-run scenario below is required before a region-specific availability claim is made; actual status codes, RCODEs and `Retry-After` values belong in a separate evidence record, not in this public catalog.

## Rules

- Technical reachability is not permission. A successful response, the absence of a `429`, or a public "status" page does not authorize automated monitoring.
- Only official owner documentation is evidence. Third-party blogs and independent availability probes are not permission.
- No endpoint in this catalog is activated by this change. The app's built-in catalog remains empty until #22 integrates the approved subset.
- No load test, burst test or automated diagnostic probe was sent to a third-party service during this verification. Source pages were fetched only to read terms and documentation, as disclosed above.
- The safe default is `DISABLED`. Unknown permission is not permission.

## Status model

| Status | Meaning | Traffic |
| --- | --- | --- |
| `UNVERIFIED` | Official terms were not found or do not settle the intended use. | None. Not eligible for a built-in profile. |
| `MANUAL_ONLY` | Ordinary, user-triggered use is consistent with the owner's documented purpose, but background or mass monitoring is not approved. | One user-initiated check, subject to the catalog interval and budget. |
| `BACKGROUND_APPROVED` | The owner explicitly permits unattended third-party monitoring at the documented rate. | Background scheduling allowed within the catalog budget. |
| `DISABLED` | OS-internal, owner-only, no third-party permission, or otherwise unsuitable. | None. |

`MANUAL_ONLY` is not a licence for a monitoring loop. The app must still use the shared admission controller, one in-flight request per target, jitter and the catalog interval. A `403`, `429`, `503` with `Retry-After`, CAPTCHA or an explicit anti-bot response stops automatic attempts for that group; it is not evidence of a network outage.

## Catalog fields

Each entry carries:

- owner/operator and `admissionGroup` (shared quota for aliases and related hosts);
- kind, protocol, exact address/port/path/method and expected response;
- regional group (`GLOBAL`, `RU`, `US`, `EU`) and any blocked regions;
- official source links and the verification date;
- allowed modes, minimum interval, daily budget and status reason;
- redirect and retry policy, limits and notes, including known aliases and caveats.

`dailyBudget` is a per-install engineering limit, not a provider quota. A zero budget means no traffic. The admission group is the unit for shared quotas; changing the hostname, port, protocol or IP address must not reset it.

## Provider summary

| Admission group | Candidates reviewed | Status | Documented basis |
| --- | --- | --- | --- |
| Android system validation | HTTP and HTTPS `generate_204` probes | `DISABLED` | AOSP OS-internal validation; use passive `NetworkCapabilities` instead. |
| Google Public DNS | UDP, DoH, DoT | `MANUAL_ONLY` | Free resolver with published per-client rate limits; no permission for background monitoring. |
| Cloudflare 1.1.1.1 | UDP, DoH, DoT | `MANUAL_ONLY` | Ordinary use is documented; security scanning may be rate-limited; no background permission. |
| Cloudflare captive portal | `cp.cloudflare.com` | `DISABLED` | Not in Cloudflare's documented captive-portal domain list. |
| Microsoft NCSI | current `connecttest.txt`, legacy `ncsi.txt`, DNS probe | `DISABLED` | Windows OS-internal, no third-party permission. |
| Quad9 | UDP, DoH, DoT | `MANUAL_ONLY` | Ordinary use is allowed; large-scale traffic requires prior contact; France is disabled. |
| AdGuard DNS | UDP, DoH, DoT | `DISABLED` | EULA prohibits automated requests/agents without written consent. |
| Cisco OpenDNS | UDP, DoH | `MANUAL_ONLY` | Cisco rate-limits at its discretion and restricts regular automated queries. |
| Yandex Public DNS | UDP, DoH, DoT (common variant) | `MANUAL_ONLY` | Personal non-commercial use only; other use needs prior written consent; automated access may be prohibited. |
| Russian public sites | `ya.ru`, `vk.com`, `mail.ru`, `gosuslugi.ru`, `ria.ru`, `rt.ru`, `sber.ru` | `DISABLED` | No monitoring permission; published terms restrict automated interaction, require consent, or no permission is published. |
| Russian carrier DNS | regional MTS, MegaFon, Beeline, T2, Rostelecom resolvers | `DISABLED` / `UNVERIFIED` (outside JSON) | Subscriber configuration data, often regional or private; no public resolver permission. |

`catalog.json` currently has **30 entries: 14 `MANUAL_ONLY`, 16 `DISABLED`, 0 `UNVERIFIED` and 0 `BACKGROUND_APPROVED`**. Three Russian carrier resolvers are `UNVERIFIED` outside the JSON because they have no usable public address. No network endpoint is `BACKGROUND_APPROVED`. The only passive, zero-traffic signal is the Android `NetworkCapabilities` state, which is not a catalog entry.

### Russian carrier DNS

MTS and MegaFon document different resolvers per federal district; some MegaFon addresses are private `10.x` addresses. Beeline, T2 and Rostelecom did not document a public recursive resolver service for third-party use in the reviewed official materials; subscriber addresses are normally assigned by DHCP. The separate Beeline DNS service is authoritative hosting, not a recursive resolver. None of these is documented for third-party use. MTS and MegaFon are therefore `DISABLED`; Beeline, T2 and Rostelecom are `UNVERIFIED` outside the JSON with no usable public address. They are deliberately not JSON catalog entries, because an entry without an approved address would invite a request that has no permission. This carrier summary is a non-authoritative exclusion note, not a catalog entry or an approval source. Do not query them.

## Passive Android validation

Android already validates the active network. LinkSonda should read `ConnectivityManager` and `NetworkCapabilities` instead of sending its own captive-portal request:

- `NET_CAPABILITY_VALIDATED` — the system completed its validation;
- `NET_CAPABILITY_CAPTIVE_PORTAL` — the system detected a captive portal;
- no validated capability — unknown or unvalidated, not automatically "down".

Official references: [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities) and [Android network validation](https://source.android.com/docs/core/ota/modular-system/networking). This signal is passive from the app's point of view: the OS performs the traffic, it is not attributable to LinkSonda, and it does not prove that an arbitrary service, DNS resolver or region is reachable. It is not a catalog entry and costs zero app-originated requests, so it is not a `BACKGROUND_APPROVED` network target. It must not be used as a substitute for a verified control endpoint, and the app must not call hidden validation APIs or force a captive-portal login.

## Load model

The following is an engineering model, not a provider limit and not a safety proof. It assumes a manual-only control point with a 300-second minimum interval per install and per admission group, i.e. at most 288 requests per 24 hours. Individual entries carry smaller caps (4–144 requests/day) so that each admission group stays at or below 288/day; the group cap is authoritative within a group, and the global per-install ceiling below is authoritative across groups. The five manual-only groups total 996 requests/day per install (Cloudflare, Quad9 and OpenDNS 288 each, Google 120, Yandex 12), below the policy ceiling of 1,000/day. Background approval is currently zero for every entry.

| Installs | Requests/day per group | Average requests/s if spread evenly | Synchronized burst |
| ---: | ---: | ---: | ---: |
| 100 | 28,800 | 0.33 | 100 |
| 1,000 | 288,000 | 3.33 | 1,000 |
| 10,000 | 2,880,000 | 33.3 | 10,000 |

These numbers are per admission group, not an additive target. The policy's global ceiling of 6 external attempts/minute and 1,000/24 h per install is binding across groups: if enabling every group would exceed it, the scheduler must scale down or disable groups. The burst column is the important one for a provider's per-IP limiter: a local scheduler can spread requests, but it cannot prevent users from starting at the same time after an update, a preset activation or a network change. A 60-second background loop would multiply the per-group daily and average columns by five; before the global cap, 10,000 installs alone would generate 14.4 million requests/day and about 167 requests/s. With the 1,000/day cap, the aggregate across groups is at most 10 million requests/day (about 116 requests/s), still before shared CGNAT egress. That is not an acceptable default for a third-party service.

### Shared CGNAT and VPN egress

If all installs share one public address (carrier CGNAT or a VPN exit), the provider sees the aggregate, not the per-install budget:

| Installs behind one egress | Shared-IP requests/s | Shared-IP burst |
| ---: | ---: | ---: |
| 100 | 0.33 | 100 |
| 1,000 | 3.33 | 1,000 |
| 10,000 | 33.3 | 10,000 |

A public resolver or captive-portal endpoint may rate-limit or block that address even when each individual installation follows its local interval. The app cannot enforce a global quota without a backend, so public targets must not be used for background monitoring and manual checks need jitter and a persistent limiter.

### Traffic estimate

Conservative per-exchange assumptions in decimal units (MB = 10^6 bytes): DoH/DoT 5 KB including TCP/TLS overhead, DNS over UDP/TCP 0.5 KB, HTTPS 204 2 KB assuming a reused connection. A cold HTTPS TLS handshake can be 5 KB or more, so the HTTPS column is a lower bound. The table is per admission group and per day.

| Installs | DoH/DoT at 288/day | DNS at 288/day | HTTPS at 288/day |
| ---: | ---: | ---: | ---: |
| 100 | 144 MB | 14.4 MB | 57.6 MB |
| 1,000 | 1.44 GB | 144 MB | 576 MB |
| 10,000 | 14.4 GB | 1.44 GB | 5.76 GB |

These estimates exclude retries, redirects, TLS session resumption and OS-level validation. They are another reason to keep the default budget small and to measure real traffic in #26.

## Russian manual verification scenario

This is an owner-run, low-volume verification, not a production feature and not a load test. It is intentionally outside the app: the current build has no connected monitoring engine, the internal probe runners are not wired to production, and `INTERNET` is used by the updater. Do not run it in a loop.

1. If the owner approves a small debug-only harness, use one physical Android device with a single **"Run once"** action. The current build has no such action; this is a proposed verification harness, not a shipped feature or part of this PR. No background worker, periodic job or automatic retry. Record the device, Android version and build hash.
2. Cover one network per day: home Wi-Fi (for example Rostelecom), MTS mobile data, Beeline or MegaFon mobile data, and optionally Wi-Fi with an already-configured VPN exit outside Russia. Do not change carrier or VPN to evade a block. Record whether the public address is CGNAT, dedicated or VPN, and record the resolver assigned by DHCP.
3. Before each run: force-stop the app, set Private DNS to Off, note the VPN state and wait 60 seconds after switching networks.
4. Make one try per query with a 5-second timeout, in this order:
   - `ya.ru A` and `ya.ru AAAA` via `77.88.8.8:53` (UDP) — 2 queries;
   - `vk.com A` and `mail.ru A` via `77.88.8.8` (UDP) — 2 queries, **these are DNS queries to Yandex, not requests to the sites**;
   - `ya.ru A` via DoH `https://common.dot.dns.yandex.net/dns-query` — 1 query;
   - `ya.ru A` via DoT `common.dot.dns.yandex.net:853` — 1 query.
   This is 6 DNS queries: four fit the Yandex UDP entry budget, and the DoH/DoT entries each get one. Do not resolve `ria.ru`, `gosuslugi.ru`, `sber.ru` or `rt.ru`: those targets are disabled and their DNS resolution is not approved. Do not open any HTTPS page in this scenario.
5. Record: UTC and local time, carrier/Wi-Fi/VPN, region, network technology, public-IP type, local resolver, target, name/type, transport, DNS RCODE and answers, elapsed time, byte count, `Retry-After`, block page and notes. Do not store bodies, cookies, tokens or account data.
6. Stop the run on a DNS timeout over 5 seconds, `SERVFAIL`, `REFUSED`, malformed/truncated response, an HTTP `403`/`429`/`503` from DoH with `Retry-After`, a block/CAPTCHA page, a TLS error, three consecutive failures, or any sign of rate limiting. End the run, wait at least 24 hours and report the exact values. Do not retry from another network to obtain a successful response.
7. Never test login, API, payment or account pages. Do not scrape, crawl, scan ports, run zone transfers, loop, parallelize or load-test. Do not query private `10.x` carrier resolvers from outside their network, bypass blocks or use a VPN to "prove" Russian availability.

Budget: at most 12 DNS queries per day; no direct HTTP or HTTPS page requests (DoH is a DNS query carried over HTTPS). A single **"Run once"** session is one manual verification check; its fixed four UDP queries count against the UDP entry budget, and the 300-second entry interval applies between sessions, not between packets in that fixed set. Do not repeat a session within 24 hours. This is a one-time verification, not a monitoring schedule. It does not approve any endpoint for background use, and it does not turn a `DISABLED` or `UNVERIFIED` candidate into an approved target.

## Decisions and maintenance

- A catalog entry changes status only with a dated official source and a recorded decision. The verification date is not an expiry date; re-check terms before each release.
- The catalog is versioned with the app. A problematic target can be disabled by an app update; there is no remote configuration or backend.
- Promotion to `BACKGROUND_APPROVED` requires an explicit owner statement or an official terms page that clearly permits unattended third-party monitoring at the proposed rate. Technical availability alone is never enough.
- The empty built-in catalog is intentional until #22 integrates only the approved subset.
