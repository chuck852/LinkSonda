# Network transports

`SocketProbeRunner` and `DnsProbeRunner` are transport implementations of the domain `ProbeRunner` contract. They are not connected to production UI. The manifest still has no `INTERNET` permission, the app sends no probe traffic, and no public endpoint is activated. Tests use loopback servers only.

`SocketProbeRunner` handles `ProbeRequest.TcpConnect` and `ProbeRequest.TlsHandshake`; `DnsProbeRunner` handles `ProbeRequest.SystemDns` and `ProbeRequest.DirectDns`. Every other request family returns `ProbeResult.Skipped` with `SkipReason.UNSUPPORTED` without calling admission.

## TCP and TLS targets and DNS

- `SocketEndpoint.host` accepts an IPv4 literal, an IPv6 literal, a bracketed IPv6 literal, or a DNS name.
- An optional `IpFamily` filters hostname resolution. Matching addresses are tried in resolver order, preferring families present in the network snapshot; a literal or resolution result of the wrong family fails as `DNS`.
- IP literals are parsed locally and need no DNS admission. Hostnames use `SystemHostResolver` on a bounded daemon worker pool (2 workers, 8 queued) so a blocking platform lookup does not delay the run timeout or cancellation. When the pool is full, the run returns `Inconclusive(OS_LIMITATION)` instead of reporting a DNS or network failure.
- The DNS phase is recorded only when resolution starts; a capacity rejection does not invent a DNS duration.

## TCP and TLS admission

Every outbound attempt goes through the caller's `ProbeAdmission` before it starts:

| Attempt | Endpoint key | Infrastructure key |
| --- | --- | --- |
| DNS resolution | `<host>` | `system-dns` |
| TCP connect | `<host>` | `<host>` |
| TLS handshake | `<host>` | `<host>` |

Keys use the normalized endpoint host only. Changing the port, transport, or TLS SNI does not create a new endpoint or infrastructure identity, so the shared admission controller can group those attempts. Alias and resolver/provider grouping belongs in that controller, not in this runner. A TLS handshake is part of the authorized socket attempt: one grant covers the TCP connection and the handshake on that socket. Each fallback address is a separate socket attempt and is authorized separately. A denied attempt returns `ProbeResult.Skipped` with the denial reason and `nextAllowedAt`; no socket is opened for it.

## TCP and TLS outcomes and timing

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

## System DNS and direct DNS

`DnsProbeRunner` keeps the system resolver and explicit DNS requests separate. It uses `System.nanoTime()` for elapsed and phase durations and closes UDP and TCP sockets on timeout, cancellation, and completion.

### System resolver

- Only `A` (type 1) and `AAAA` (type 28) are supported. Other record types and a non-zero expected RCODE are skipped as `UNSUPPORTED` without admission, because the Android system resolver does not expose the DNS response header.
- The runner authorizes one system lookup per normalized question name: endpoint `<name>`, infrastructure `system-dns`. It records `DnsObservation(answerCount = matching addresses)` and the `DNS` phase. RCODE and truncation stay unset; the runner does not invent values the platform API cannot observe.
- `UnknownHostException` and other lookup failures are `Failed(DNS)`. Permission denial is `Inconclusive(PERMISSION_DENIED)`, and bounded-worker exhaustion is `Inconclusive(OS_LIMITATION)`.
- The system resolver's actual server identity is selected by Android and is not exposed by the API, so it is not recorded. The platform may also return cached or combined results; direct DNS sends a fresh wire query. Neither path exposes cache state, so the runner does not record it.

### Direct DNS

- The request's `DirectDns.resolver` endpoint is used exactly as configured. The result model has no resolver field, so the persisted probe definition remains the source of the selected resolver; the runner does not add an invented identity.
- An IP-literal resolver is parsed locally without DNS admission. A hostname resolver is resolved through the bounded system resolver with a separate attempt: endpoint `<resolver host>`, infrastructure `system-dns`.
- Every query attempt authorizes endpoint `<normalized question name>` and infrastructure `<normalized resolver host>`. Changing the resolver port, UDP/TCP transport, or the question type does not create a new identity; alias and provider grouping belongs to the shared admission controller. Each resolved resolver address is a separate attempt. A TCP fallback after truncation is another separate attempt.
- Queries use a fresh random transaction ID, recursion desired, one question, and class IN. Any record type accepted by `DnsQuestion` is encoded.
- Responses are parsed from the wire. The runner validates the transaction ID, QR bit, opcode, QDCOUNT, the matching question name/type/class, section counts, compression-pointer bounds and loops, record lengths, and rejects trailing bytes. It records the low four RCODE bits, header ANCOUNT, and the TC bit. It does not persist TTLs, record data, certificates, or peer addresses.
- UDP sends the query and then waits for a validated response. Sending a datagram is never success on its own. A timeout is `Failed(TIMEOUT)`, a malformed or wrong-ID/question response is `Failed(UNEXPECTED_RESPONSE)`, a RCODE different from the expectation is `Failed(UNEXPECTED_DNS_RESPONSE_CODE)`, and a required answer with `ANCOUNT = 0` is `Failed(UNEXPECTED_RESPONSE)`. NXDOMAIN and SERVFAIL are recorded RCODE values; whether they match depends on `DnsExpectation`.
- When a parsed UDP response has TC, the runner sends the same question over TCP with a new transaction ID and a separate admission. A successful TCP response keeps `truncated = true` and records the TCP RCODE/ANCOUNT. If the TCP fallback fails, the failure keeps the observed UDP RCODE/ANCOUNT with `truncated = true`. A denied fallback returns `Skipped` and opens no TCP socket. There is no fallback after a timeout or malformed UDP response.
- A direct TCP request records the `TCP_CONNECT` and `DNS` phases. A TCP response with TC is `Failed(UNEXPECTED_RESPONSE)` because it is incomplete.
- The runner's own timeout produces `Failed(TIMEOUT)`; external coroutine cancellation propagates as cancellation. A blocking resolver or socket operation does not delay cancellation past the socket close.

## Verification

```bash
./gradlew --console=plain testDebugUnitTest lintDebug assembleDebug
```

Transport tests cover:

- TCP and TLS: IPv4 and IPv6 loopback, success/refusal, fallback across resolved addresses, DNS timeout and failure, blocking DNS that ignores interruption, DNS executor exhaustion, hostname admission order, stable host admission keys across port/transport/SNI, denied admission, TLS with SNI and TLS 1.2/1.3, untrusted certificate, hostname mismatch, negative IP subject alternative name verification, handshake timeout, external cancellation propagation, and prompt cancellation with socket release.
- DNS: system A/AAAA counts and missing answers, unsupported types and RCODE expectations, admission denial, timeout and external cancellation, permission and capacity failures, direct UDP success, NXDOMAIN, SERVFAIL mismatch, missing answers, wrong transaction ID, malformed and truncated responses, TC fallback and its separate admission, failed and denied fallback, direct TCP success/refusal/truncation, hostname resolver admission order, IPv6 loopback, invalid question names, and cancellation.

All test servers are local. The TLS certificate is generated at test runtime, and no public endpoint is contacted.

## Current limits

HTTP and ICMP runners, DoH and DoT, Android network binding, the `INTERNET` permission, and production UI integration are not part of this implementation. A later integration issue must add the permission and connect the runners to the monitoring engine without weakening admission or TLS verification. A platform DNS lookup that ignores interruption can keep its bounded worker thread until the resolver returns; the probe result itself is still bounded by the configured timeout, and further lookups return `Inconclusive(OS_LIMITATION)` when the pool is full.
