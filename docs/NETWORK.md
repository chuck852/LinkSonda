# TCP Connect and TLS

`SocketProbeRunner` is the first transport implementation of the domain `ProbeRunner` contract. It handles `ProbeRequest.TcpConnect` and `ProbeRequest.TlsHandshake`; every other request family returns `ProbeResult.Skipped` with `SkipReason.UNSUPPORTED` without calling admission.

The runner is not connected to production UI. The manifest still has no `INTERNET` permission, the app sends no probe traffic, and no public endpoint is activated. Tests use loopback servers only.

## Targets and DNS

- `SocketEndpoint.host` accepts an IPv4 literal, an IPv6 literal, a bracketed IPv6 literal, or a DNS name.
- An optional `IpFamily` filters hostname resolution. Matching addresses are tried in resolver order, preferring families present in the network snapshot; a literal or resolution result of the wrong family fails as `DNS`.
- IP literals are parsed locally and need no DNS admission. Hostnames use `SystemHostResolver`, which runs on a worker so a blocking platform lookup does not delay the run timeout or cancellation.
- The DNS phase is recorded only when resolution is attempted.

## Admission

Every outbound attempt goes through the caller's `ProbeAdmission` before it starts:

| Attempt | Endpoint key | Infrastructure key |
| --- | --- | --- |
| DNS resolution | `dns:<host>` | `system-dns` |
| TCP connect | `tcp:<host>:<port>` | `tcp:<host>` |
| TLS handshake | `tls:<server-name>:<port>` | `tls:<server-name>` |

A TLS handshake is part of the authorized socket attempt: one grant covers the TCP connection and the handshake on that socket. Each fallback address is a separate socket attempt and is authorized separately. A denied attempt returns `ProbeResult.Skipped` with the denial reason and `nextAllowedAt`; no socket is opened for it.

## Outcomes and timing

- Elapsed time and phase durations use `System.nanoTime()`. `recordedAt` uses the wall clock. A phase is present only after its attempt starts; a timeout keeps the duration of the interrupted phase. Missing phases are not zero.
- DNS, TCP, and TLS failures remain separate. The runner maps lookup errors to `DNS`, refusal and known unreachable/timeout connect errors to `REFUSED`/`UNREACHABLE`/`TIMEOUT`, other connect errors to `TCP`, and handshake or certificate failures to `TLS`.
- An overall timeout produces `Failed(TIMEOUT)`, including while DNS is blocked in the platform resolver. A caller cancellation propagates as `CancellationException`.
- The live socket is closed when the attempt finishes, times out, or is cancelled. The cancellation handler closes it immediately rather than waiting for the coroutine to finish.
- `SecurityException` and socket errors reporting `EACCES`/`EPERM` are `Inconclusive(PERMISSION_DENIED)` for an attempted operation, not a network failure.

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

Runner tests cover IPv4 and IPv6 loopback, TCP success/refusal, fallback across resolved addresses, DNS timeout and failure, hostname admission order, denied admission, TLS success with SNI and TLS 1.2/1.3, untrusted certificate, hostname mismatch, negative IP subject alternative name verification, handshake timeout, and prompt cancellation with socket release. The TCP and TLS test servers are local; the TLS certificate is generated at test runtime. No public endpoint is contacted.

## Current limits

HTTP, DNS, and ICMP runners, Android network binding, the `INTERNET` permission, and production UI integration are not part of this implementation. A later integration issue must add the permission and connect the runner to the monitoring engine without weakening admission or TLS verification. A platform DNS lookup that ignores interruption can keep its worker thread until the resolver returns; the probe result itself is still bounded by the configured timeout.
