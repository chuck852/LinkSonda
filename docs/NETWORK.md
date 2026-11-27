# TCP Connect and TLS

`SocketProbeRunner` is the first transport implementation of the domain `ProbeRunner` contract. It handles `ProbeRequest.TcpConnect` and `ProbeRequest.TlsHandshake`; every other request family returns `ProbeResult.Skipped` with `SkipReason.UNSUPPORTED` without calling admission.

The runner is not connected to production UI. The manifest still has no `INTERNET` permission, the app sends no probe traffic, and no public endpoint is activated. Tests use loopback servers only.

## Targets and DNS

- `SocketEndpoint.host` accepts an IPv4 literal, an IPv6 literal, a bracketed IPv6 literal, or a DNS name.
- An optional `IpFamily` filters hostname resolution. Matching addresses are tried in resolver order, preferring families present in the network snapshot; a literal or resolution result of the wrong family fails as `DNS`.
- IP literals are parsed locally and need no DNS admission. Hostnames use `SystemHostResolver` on a bounded daemon worker pool (2 workers, 8 queued) so a blocking platform lookup does not delay the run timeout or cancellation. When the pool is full, the run returns `Inconclusive(OS_LIMITATION)` instead of reporting a DNS or network failure.
- The DNS phase is recorded only when resolution starts; a capacity rejection does not invent a DNS duration.

## Admission

Every outbound attempt goes through the caller's `ProbeAdmission` before it starts:

| Attempt | Endpoint key | Infrastructure key |
| --- | --- | --- |
| DNS resolution | `<host>` | `system-dns` |
| TCP connect | `<host>` | `<host>` |
| TLS handshake | `<host>` | `<host>` |

Keys use the normalized endpoint host only. Changing the port, transport, or TLS SNI does not create a new endpoint or infrastructure identity, so the shared admission controller can group those attempts. Alias and resolver/provider grouping belongs in that controller, not in this runner. A TLS handshake is part of the authorized socket attempt: one grant covers the TCP connection and the handshake on that socket. Each fallback address is a separate socket attempt and is authorized separately. A denied attempt returns `ProbeResult.Skipped` with the denial reason and `nextAllowedAt`; no socket is opened for it.

## Outcomes and timing

- Elapsed time and phase durations use `System.nanoTime()`. `recordedAt` uses the wall clock. A phase is present only after its attempt starts; a timeout keeps the duration of the interrupted phase. Missing phases are not zero.
- DNS, TCP, and TLS failures remain separate. The runner maps lookup errors to `DNS`, refusal and known unreachable/timeout connect errors to `REFUSED`/`UNREACHABLE`/`TIMEOUT`, other connect errors to `TCP`, and handshake or certificate failures to `TLS`.
- The runner's own overall timeout produces `Failed(TIMEOUT)`, including while DNS is blocked in the platform resolver. An external coroutine timeout or cancellation propagates as cancellation; it is not converted into a probe result.
- The live socket is closed when the attempt finishes, times out, or is cancelled. The cancellation handler closes it immediately rather than waiting for the coroutine to finish.
- `SecurityException` and socket errors reporting `EACCES`/`EPERM` are `Inconclusive(PERMISSION_DENIED)` for an attempted operation, not a network failure. Local DNS worker exhaustion is `Inconclusive(OS_LIMITATION)`, not a network failure.

## TLS

TLS uses the platform trust store and HTTPS endpoint identification. Certificate and hostname verification cannot be disabled.

- SNI is sent for DNS server names and omitted for IP literals; IP literals are verified against IP subject alternative names.
- The server name defaults to the endpoint host. An explicit name is normalized but not resolved separately.
- TLS 1.2 is the default minimum; a request can require TLS 1.3. Negotiated TLS 1.2 and 1.3 are recorded as a `TlsObservation`.
- Certificates, raw handshake data, and peer addresses are not persisted.

## Verification

```bash
./gradlew --console=plain testDebugUnitTest lintDebug assembleDebug
```

Runner tests cover IPv4 and IPv6 loopback, TCP success/refusal, fallback across resolved addresses, DNS timeout and failure, blocking DNS that ignores interruption, DNS executor exhaustion, hostname admission order, stable host admission keys across port/transport/SNI, denied admission, TLS success with SNI and TLS 1.2/1.3, untrusted certificate, hostname mismatch, negative IP subject alternative name verification, handshake timeout, external cancellation propagation, and prompt cancellation with socket release. The TCP and TLS test servers are local; the TLS certificate is generated at test runtime. No public endpoint is contacted.

## Current limits

HTTP, DNS, and ICMP runners, Android network binding, the `INTERNET` permission, and production UI integration are not part of this implementation. A later integration issue must add the permission and connect the runner to the monitoring engine without weakening admission or TLS verification. A platform DNS lookup that ignores interruption can keep its bounded worker thread until the resolver returns; the probe result itself is still bounded by the configured timeout, and further lookups return `Inconclusive(OS_LIMITATION)` when the pool is full.
