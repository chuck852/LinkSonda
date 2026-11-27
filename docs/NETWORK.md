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
- The runner authorizes one system lookup per normalized question name: endpoint `<name>`, infrastructure `system-dns`. It records `DnsObservation(answerCount = matching addresses)` and the `DNS` phase. RCODE and truncation stay unset; the runner does not invent values the platform API cannot observe. An empty address list is `Failed(DNS)` even when `requiresAnswer = false`, because the platform does not distinguish NXDOMAIN from NODATA.
- `UnknownHostException` and other lookup failures are `Failed(DNS)`. Permission denial is `Inconclusive(PERMISSION_DENIED)`, and bounded-worker exhaustion is `Inconclusive(OS_LIMITATION)`.
- The system resolver's actual server identity is selected by Android and is not exposed by the API, so it is not recorded. The platform may also return cached or combined results; direct DNS sends a fresh wire query. Neither path exposes cache state, so the runner does not record it.

### Direct DNS

- The request's `DirectDns.resolver` endpoint is used exactly as configured. The result model has no resolver field, so the persisted probe definition remains the source of the selected resolver; the runner does not add an invented identity.
- An IP-literal resolver is parsed locally without DNS admission. A hostname resolver is resolved through the bounded system resolver with a separate attempt: endpoint `<resolver host>`, infrastructure `system-dns`.
- Every query attempt authorizes endpoint `<normalized question name>` and infrastructure `<normalized resolver host>`. Changing the resolver port, UDP/TCP transport, or the question type does not create a new identity; alias and provider grouping belongs to the shared admission controller. Each resolved resolver address is a separate attempt. Transport failures (timeout, refusal, unreachable, malformed response) continue with the next resolved address; the remaining run timeout is divided across the remaining addresses so a silent attempt cannot consume the whole budget. Inconclusive results and a valid DNS response with an unexpected RCODE or no matching required answer stop the run. A TCP fallback after truncation is another separate attempt sharing the current address budget.
- Queries use a fresh random transaction ID, recursion desired, one question, and class IN. Any record type accepted by `DnsQuestion` is encoded. Expected RCODE values above 15 are skipped as `UNSUPPORTED` before admission because the runner does not send EDNS.
- Responses are parsed from the wire. The runner validates the transaction ID, QR bit, opcode, QDCOUNT, the matching question name/type/class, section counts, compression-pointer bounds and loops, record lengths, and rejects trailing bytes. Answer records must have class IN and an owner equal to the question or reachable through a CNAME chain. A and AAAA records also require 4-byte and 16-byte RDATA respectively. For other record types the runner validates only the record structure, type, class, and owner; it does not claim semantic validation of arbitrary RDATA. It records the actual number of complete answer records, not a claimed ANCOUNT, and the low four RCODE bits. An OPT record's extended RCODE is combined instead of being reported as NOERROR; full EDNS options are not implemented. It does not persist TTLs, record data, certificates, or peer addresses.
- A truncated response counts only complete answer records and leaves `matchingAnswerCount` at zero; the TCP retry performs the full parse. This also applies when the message ends inside a later section.
- UDP sends the query and then waits for a validated response. Sending a datagram is never success on its own. A timeout is `Failed(TIMEOUT)`, a malformed or wrong-ID/question response is `Failed(UNEXPECTED_RESPONSE)`, a RCODE different from the expectation is `Failed(UNEXPECTED_DNS_RESPONSE_CODE)`, and a required answer with no matching answer record is `Failed(UNEXPECTED_RESPONSE)`. A CNAME-only answer does not satisfy an A or AAAA expectation even though the answer count is non-zero. NXDOMAIN and SERVFAIL are recorded RCODE values; whether they match depends on `DnsExpectation`.
- When a UDP response has TC, the runner closes the UDP socket and sends the same question over TCP with a new transaction ID and a separate admission. A successful TCP response keeps `truncated = true` and records the fully parsed TCP RCODE/answer count. If the TCP fallback fails, the failure keeps the complete UDP facts with `truncated = true`; a partially received answer is not counted. A denied fallback returns `Skipped` and opens no TCP socket. There is no fallback after a timeout or a header that fails validation; a TC header with partially truncated later sections still triggers the retry.
- A direct TCP request records the `TCP_CONNECT` and `DNS` phases. The same attempt budget covers connect and the complete length-prefixed exchange, including a slow-drip response; each read uses the time remaining before the attempt deadline. A TCP response with TC is `Failed(UNEXPECTED_RESPONSE)` because it is incomplete.
- When several addresses or a TCP fallback are attempted, each phase duration is the sum of that phase across attempts. The runner's own timeout produces `Failed(TIMEOUT)`; external coroutine cancellation propagates as cancellation. A blocking resolver or socket operation does not delay cancellation past the socket close.

## Verification

```bash
./gradlew --console=plain testDebugUnitTest lintDebug assembleDebug
```

Transport tests cover:

- TCP and TLS: IPv4 and IPv6 loopback, success/refusal, fallback across resolved addresses, DNS timeout and failure, blocking DNS that ignores interruption, DNS executor exhaustion, hostname admission order, stable host admission keys across port/transport/SNI, denied admission, TLS with SNI and TLS 1.2/1.3, untrusted certificate, hostname mismatch, negative IP subject alternative name verification, handshake timeout, external cancellation propagation, and prompt cancellation with socket release.
- DNS: system A/AAAA counts and missing answers (including `requiresAnswer = false`), unsupported types and RCODE expectations, admission denial, timeout and external cancellation, permission and capacity failures, direct UDP success, NXDOMAIN, SERVFAIL mismatch, missing and CNAME-only answers, wrong transaction ID, malformed and truncated responses, malformed A/AAAA RDATA, wrong answer class, unrelated owner, a valid CNAME chain, EDNS extended RCODE, retry of another resolved address after a malformed response and after a silent timeout, TC fallback and its separate admission, partial TC sections and actual answer counts, failed and denied fallback, direct TCP success/refusal/truncation, slow-drip exchange budget, hostname resolver admission order, IPv6 loopback, invalid question names, extended expected RCODE, phase summation, and cancellation.

All test servers are local. The TLS certificate is generated at test runtime, and no public endpoint is contacted.

## Current limits

HTTP and ICMP runners, DoH and DoT, Android network binding, the `INTERNET` permission, and production UI integration are not part of this implementation. A later integration issue must add the permission and connect the runners to the monitoring engine without weakening admission or TLS verification. EDNS is limited to reading the extended RCODE field from an OPT record; options, version negotiation, and full EDNS semantics are not implemented, and an extended expected RCODE still skips as unsupported. A platform DNS lookup that ignores interruption can keep its bounded worker thread until the resolver returns; the probe result itself is still bounded by the configured timeout, and further lookups return `Inconclusive(OS_LIMITATION)` when the pool is full.
