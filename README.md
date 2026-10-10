# OpenELIS Analyzer Bridge

Middleware that runs analyzer connections, parses analyzer traffic from pinned
profiles, and sends one normalized result contract to OpenELIS.

This repository was previously named **ASTM-HTTP Bridge**. The internal rename to `openelis-analyzer-bridge` is complete across Maven, Docker, and scripts. The Docker Hub image `itechuw/astm-http-bridge` is still published as a legacy alias via CI.

## Ownership Model

Bridge and OpenELIS responsibilities are explicitly separated:

- Bridge owns portable profiles, durable analyzer connections and runtime
  configuration, listeners, parsing, probes, control recognition, FILE
  watching, and normalized delivery.
- OpenELIS owns the lab-facing setup workflow, references to Bridge
  connections, lab units, local catalog bindings, verification and audit,
  activation intent, operational QC, held results, and review.
- A profile defines communication behavior for one analyzer type and supplies
  defaults for creating a new Bridge connection of that type.

## Architecture

Saved connections support ASTM, FILE and serial analyzers, profile-driven HTTP
ASTM/HL7/CSV/TSV input, and inbound HL7/MLLP server listeners.
HL7 listeners use saved connection identity and the pinned profile's recognition
rules, and recover the last successfully activated configuration after restart.
Enabling the HL7 runtime binds the shared deployment listener; it does not
create a saved analyzer identity.
HL7 `TCP/IP` and `MLLP` saved transports use the same MLLP wire protocol.
A CLIENT connection opens outbound order sessions without creating a
connection-owned inbound listener. Persistent client-side result reception is
not implemented; CLIENT activation requires enabled outbound orders.

```
Analyzer(s)                                    OpenELIS
───────────                                    ────────
ASTM/TCP     ─┐
HL7/MLLP     ─┤
RS232/Serial ─┼─> [OpenELIS Analyzer Bridge] ──FHIR──> /analyzer/fhir
Files        ─┤   │ Pinned profile parsing  │          normalized result contract
HTTP /input  ─┘   │ Saved connection lookup │
                   │ Metrics + health checks │
                   └─────────────────────────┘
                     │
                     ├─ /actuator/health      (status; per-transport detail with auth)
                     ├─ /actuator/prometheus   (Prometheus metrics, auth)
                     └─ /actuator/metrics      (Micrometer metrics, auth)

OpenELIS ──HTTP POST /api/orders──> [Bridge] ──TCP/MLLP──> Analyzer (outbound orders)
```

### Protocol vs Transport

| Concept | Options | Description |
|---------|---------|-------------|
| **Protocol** | ASTM, HL7, CSV | Message format/syntax |
| **Transport** | TCP, MLLP, Serial, File, HTTP | How the message arrives |

## Quick Start

### Using Docker (Recommended)

```bash
git clone https://github.com/DIGI-UW/openelis-analyzer-bridge.git
cd openelis-analyzer-bridge

docker compose up -d
docker logs --follow openelis-analyzer-bridge
```

`docker-compose.yml` runs the published `itechuw/openelis-analyzer-bridge:latest`
image, for amd64 and arm64 hosts; it does not build from this checkout. It runs
the image's default `prod` profile. `SPRING_PROFILES_ACTIVE` selects another;
the older `SPRING_PROFILE` still works when it is the only one set, and logs a
warning.

### Building from Source

```bash
cd astm-http-lib
mvn clean install

cd ..
mvn clean package

java -jar target/openelis-analyzer-bridge-*.jar --spring.config.location=configuration.yml
```

## Docker Deployment

### Port Mapping

| External | Internal | Service |
|----------|----------|---------|
| 8442 | 8443 | HTTPS API endpoint |
| 12000 | 12001 | Shared ASTM LIS1-A listener, bound at boot |
| 12010 | 12011 | Shared ASTM E1381-95 listener, bound at boot |
| 2575 | 2575 | Shared HL7 MLLP listener, bound at boot when `org.itech.ahb.mllp.enabled` |

### Volume Mounts

| Host Path | Container Path | Purpose |
|-----------|---------------|---------|
| `./configuration.yml` | `/app/configuration.yml` | Runtime configuration |
| Named volume `bridge-data` | `/data/openelis-analyzer-bridge` | Durable state: delivery outbox, FILE state, saved connections and profile revisions. Keep it across upgrades |
| `/path/to/import` | `/data/analyzer-imports` | FILE connection directories (optional). FILE directories must lie under `bridge.file.import-roots` (`BRIDGE_FILE_IMPORT_ROOTS`, default `/data/analyzer-imports,/data/analyzer-drops`) |

### Serial Devices

Uncomment in `docker-compose.yml` if using serial transport:

```yaml
devices:
  - /dev/ttyUSB0:/dev/ttyUSB0
```

## Configuration

Runtime configuration is read from `configuration.yml` (mounted into container at `/app/configuration.yml`).

### Configuration Properties Reference

| Property | Description | Default |
|----------|-------------|---------|
| **OpenELIS Forwarding** | | |
| `org.itech.ahb.forward-http-server.uri` | OpenELIS base URL, for example `https://openelis:8443/OpenELIS-Global`. Results are posted to `{uri}/analyzer/fhir` and forwarding health reads `{uri}/health`. Set it for every deployment | `https://localhost:8443` |
| `org.itech.ahb.forward-http-server.username` | Basic auth username, sent only before the Bridge is paired | Optional |
| `org.itech.ahb.forward-http-server.password` | Basic auth password, sent only before the Bridge is paired | Optional |
| `org.itech.ahb.forward-http-server.insecure-tls` | Disable TLS verification for forwarding and health checks before pairing. Ignored once paired | false |
| `org.itech.ahb.forward-http-server.max-response-bytes` | Largest OpenELIS answer read; a larger one fails the attempt | 1048576 |
| `org.itech.ahb.forward-http-server.connect-timeout-seconds` | HTTP connect timeout | 30 |
| `org.itech.ahb.forward-http-server.read-timeout-seconds` | HTTP read timeout | 30 |
| `org.itech.ahb.forward-http-server.max-attempts` | Deprecated. Retry scheduling moved to `bridge.outbox.retry.*` when delivery became durable; this property is still bound but unused | 3 |
| `org.itech.ahb.forward-http-server.backoff-ms` | Deprecated, as above | 1000 |
| **Delivery Outbox** | | |
| `bridge.outbox.db-path` | Durable store holding received results until OpenELIS accepts them. This is the only copy of a result between receipt and delivery, so it must be on a persistent volume; the bridge logs a warning at startup when it is in the temporary directory | `/data/openelis-analyzer-bridge/outbox.db` in the Docker image; JVM temporary directory otherwise |
| `bridge.outbox.poll-interval` | Dispatcher idle wait. The receive path wakes it directly, so this is a safety net rather than the normal path to delivery | 1s |
| `bridge.outbox.lease` | How long a claimed delivery stays leased. Must exceed connect plus read timeout | 120s |
| `bridge.outbox.retry.max-attempts` | Attempts before a delivery is dead-lettered. Sized for an overnight OpenELIS outage | 150 |
| `bridge.outbox.retry.base-delay` | Delay before the first retry | 5s |
| `bridge.outbox.retry.multiplier` | Growth factor per attempt | 2.0 |
| `bridge.outbox.retry.max-delay` | Ceiling on the delay | 10m |
| `bridge.outbox.retry.jitter` | Random proportion applied to each delay, so analyzers that failed together do not retry together | 0.2 |
| `bridge.outbox.retention.delivered` | How long delivered entries, with their raw message and rendered bundle, are kept as the audit copy of what the analyzer sent. Unset keeps them indefinitely | unlimited |
| `bridge.outbox.retention.dismissed` | How long dismissed dead letters are kept. Undismissed dead letters are never purged | 90d |
| `bridge.outbox.payload-access-enabled` | Whether `/admin/outbox/<id>/payload` serves clinical content. Access is audited either way | true |
| `management.health.outbox.enabled` | Report the delivery queue in `/actuator/health`. UP while results are queued, since riding out an outage is the job; DOWN when the store is unreadable or had to be replaced, or the delivery dispatcher has stopped | true |
| **ASTM TCP** | | |
| `org.itech.ahb.astm.enabled` | Bind the shared ASTM listeners at boot | true |
| `org.itech.ahb.listen-astm-server.port` | Shared ASTM LIS1-A listener port (`ORG_ITECH_AHB_LISTEN_ASTM_SERVER_PORT`) | 12001 |
| `org.itech.ahb.listen-astm-server.e1381-95.port` | Shared ASTM E1381-95 listener port | 12011 |
| **MLLP (HL7)** | | |
| `org.itech.ahb.mllp.enabled` | Run HL7 MLLP: bind the shared listener at boot and allow HL7 server connections | false |
| `org.itech.ahb.mllp.port` | Shared MLLP listener port | 2575 |
| `org.itech.ahb.mllp.max-connections` | Concurrent MLLP connections; more are closed on accept | 64 |
| `org.itech.ahb.mllp.max-message-bytes` | Largest MLLP message before its connection is closed | 16777216 |
| `org.itech.ahb.mllp.message-timeout-seconds` | Time a message may take from start to end block | 60 |
| **File Watcher** | | |
| `bridge.file.enabled` | Enable FILE connection runtime | true |
| `bridge.file.stateStorePath` | Durable file-processing state database | `/data/openelis-analyzer-bridge/state.db` in the Docker image; JVM temporary directory otherwise |
| `bridge.file.pollIntervalMs` | Poll interval | 5000 |
| `bridge.file.fileStabilityTimeoutMs` | Stable-file wait | 3000 |
| `bridge.file.maxRetryAttempts` | Processing attempts before a file is parked for an operator | 150 |
| `bridge.file.retryDelayMs` | Initial retry backoff | 1000 |
| `bridge.file.maxRetryDelayMs` | Ceiling on the file retry backoff | 600000 |
| `bridge.file.maxFileSizeBytes` | Largest watched file read; a larger one is parked as FAILED_NEEDS_HANDLING (`BRIDGE_FILE_MAX_FILE_SIZE_BYTES`) | 20971520 |
| `bridge.file.importRoots` | Directories FILE connections may use, with links resolved (`BRIDGE_FILE_IMPORT_ROOTS`) | `/data/analyzer-imports,/data/analyzer-drops` |
| **Profile Catalog** | | |
| `bridge.profile-catalog.directory` | Durable site-profile revision store | `/data/openelis-analyzer-bridge/profile-catalog` |
| `bridge.profile-catalog.shipped-pattern` | Packaged profile resource pattern | `classpath*:/analyzer-profiles/**/*.json` |
| **Connection Catalog** | | |
| `bridge.connection-catalog.directory` | Durable analyzer connection store | `/data/openelis-analyzer-bridge/connections` |
| **Connectivity** | | |
| `bridge.connectivity.advertised-host` | Reserved; currently unused by connection activation and receiver probes | Optional; setting it has no runtime effect |
| **Security** | | |
| `bridge.pairing.code` | One-time code OpenELIS pairs with (`BRIDGE_PAIRING_CODE`); without one, a generated code is logged | Generated |
| `bridge.identity.directory` | Generated key pair and pairing record (`BRIDGE_IDENTITY_DIRECTORY`) | `/data/openelis-analyzer-bridge/identity` |
| `bridge.security.username` | HTTP Basic username, until pairing | bridge |
| `bridge.security.password` | HTTP Basic password until pairing: plaintext or `{bcrypt}...`; empty means pairing only | Empty |
| `bridge.http.max-input-bytes` | Largest HTTP input body | 20971520 |
| **Server** | | |
| `server.port` | HTTP server port | 8443 |

### Shared analyzer listeners

The bridge binds its analyzer ports at boot, whether or not any analyzer is
configured yet: ASTM LIS1-A on 12001, ASTM E1381-95 on 12011, and, when
`org.itech.ahb.mllp.enabled` is set (`MLLP_ENABLED` with the production
profile), HL7 MLLP on 2575. An analyzer that connects before OpenELIS has
finished configuring it reaches a listening port, and its results are kept in
the outbox until they can be attributed.

A saved TCP/IP `SERVER` connection joins the deployment-configured shared
listener for its protocol and lower layer. It has no per-analyzer incoming port.
Historical saved SERVER `port` values do not select listeners or become outbound
destinations, including when changing the connection role to CLIENT without an
explicit new destination. Activation fails if the configured listener cannot be
started. HL7 also accepts the `MLLP` transport label. Saved HL7 CLIENT
connections support outbound order sessions when the profile permits LIS-initiated
orders and the saved data flow allows them; activation otherwise fails. They do
not create an inbound listener or a persistent result-receive session.

Attribution is not peer authentication: a message is attributed, not
authorized, by its address and sender name. Use network access controls to
restrict who can reach the analyzer ports.

Deactivating an HL7 connection removes its routing. A shared listener bound at
boot keeps running. A listener that was not bound at boot stops when its last
connection leaves: it closes admissions and waits up to 30 seconds for active
delivery, and a failed drain reports failure and keeps ownership. On restart, the durable
connection catalog restores active connections from their last successfully
activated values and pinned profiles, even when a newer saved edit has not been
activated. These values remain internal to Bridge; OpenELIS receives the active
reference, not a second configuration copy.

### Serial restart and reconnect

Explicit activation requires the configured serial device to open successfully.
A previously activated connection restored at startup retains its assignment when
the device is absent, and reconnects using its pinned profile's interval and retry
limit. Other connections continue to restore. Disconnect events, failed reads and
closed-device checks use the same reconnect path. Deactivation cancels reconnect.

The saved `ACTIVE` state describes applied connection configuration; it does not
mean the cable is currently connected. `/actuator/health/serial` reports each
configured device's `open`, `pendingReconnect` and `reconnectAttempts`, and is DOWN
while any device is unavailable. Exhausted reconnect remains visible; correcting
the device and restarting or deactivating/reactivating retries the assignment.
Invalid serial settings still fail rather than being treated as temporary absence.

### FILE shutdown and recovery

Stopping the FILE service closes admissions for watcher work before stopping its
polling and processing executors. Already-started operations retain ownership
through their final state write. Shutdown then waits up to 30 seconds for
remaining claims. A timeout or
interruption reports incomplete shutdown; it does not release those claims or
report successful cancellation. Do not treat this failure as a completed drain.

Watched files commit their exact original bytes, pinned profile identity and
parser settings to the common outbox before reporting receipt. Parsing and OpenELIS delivery run from that retained receipt.
After receipt, deletion or renaming of the source file does not prevent recovery.
Partial delivery retries only outstanding accessions; an unacknowledged delivery
keeps its identifier and payload. Exhausted delivery stays in the common dead
message queue for operator retry. Preserve the outbox volume across restarts.

The separate FILE state database tracks discovery: `PROCESSED` means durably
queued, not accepted by OpenELIS. Its retry timers cover failures before durable
capture. Preserve source files and discovery state for files not yet received.

On upgrade, old unresolved `RETRYING` discovery rows are held as
`FAILED_NEEDS_HANDLING`. Older releases did not retain original bytes, so operators
must place the original file in the watched directory again.
Existing paths, attempt counts and errors are retained. Missing historical bytes
or selections cannot be reconstructed. New receipts recover automatically from
the outbox. A stopped watcher cannot be restarted; recovery creates a new instance.

### Analyzer Identification

Create a durable Bridge connection from a published profile revision. Activating
that connection materializes its source binding and profile-owned behavior into
the runtime registry. There is no separate static analyzer map.

#### Resolution Policy

Analyzer identification uses three distinct concepts:

- **Source binding**: where a message came from (serial port, file directory,
  HTTP sender, or the shared listener and peer address for ASTM and HL7 over TCP).
- **Sender name**: how the instrument names itself in the message, component 1
  of ASTM H.5 or HL7 MSH-3. A GeneXpert sends the System
  Name from its own configuration there.
- **Bridge connection ID**: the durable identity emitted in every normalized
  result bundle and used by OpenELIS for exact lookup.

A message received on a shared listener is resolved among the active
connections that declare that listener, in this order:

1. **Address.** Use connections matching the peer address when any exist.
   Otherwise, consider only connections without an address restriction.
2. **Sender name.** The one connection whose `senderId` matches the sender name,
   ignoring case.
3. **Profile pattern.** The pinned profile's `identifier_pattern` rules out
   connections for another kind of analyzer. It is type-level
   (`GENEXPERT|CEPHEID`), so it never chooses between two of the same kind.
4. **Uniqueness.** One candidate left.

A connection whose `host` is a different literal address, or whose `senderId`
names a different instrument, is never a candidate. A configured sender name
must match even when there is only one candidate at that address; an absent
sender name cannot match a connection that requires one. When none or several remain,
the message is dead-lettered with its complete payload: `UNREGISTERED_SOURCE`,
or `AMBIGUOUS_SOURCE` naming the connections that could own it. Once the
configuration is fixed, retrying the dead letter resolves it again.

When each value is needed:

| Situation | `host` | `senderId` |
|---|---|---|
| One analyzer on a listener | optional | optional |
| Several analyzers, each at a fixed address | set on each | optional |
| Several analyzers without fixed addresses, or behind one address | optional | set on all but at most one |

Activating a connection that no message could tell apart from an active one on
the same listener (the same address, or neither has one, and no `senderId`
separates them) is refused, and the refusal names the other connection. Restoring
connections at boot only logs a warning, so an existing pair cannot stop the
bridge.

`host` must be a literal IP address to match: a hostname is kept as entered and
never resolved, because sender identity is numeric. Each GeneXpert needs a
unique System Name (Cepheid LIS Interface Protocol Specification 302-2261,
Table 10-1); where two share a listener, enter that name as `senderId`. GeneXpert
profile revision 5 offers both fields for SERVER connections. Earlier revisions
keep working and resolve by uniqueness.

For serial, FILE and HTTP input the source binding is looked up directly, as
before. On any transport a sender name that contradicts the resolved connection
is recorded as a mismatch, but it never overrides the connection.

### Checking a Connection

`POST /api/connections/{connectionId}/probe` checks a saved connection without
changing it. A SERVER connection checks the configured shared listener. When
Bridge must initiate a separate connection (CLIENT role, or enabled two-way
orders), it also probes the actual remote destination with an ASTM or MLLP
handshake. A healthy inbound listener cannot make a failed outbound probe pass.

The destination port is always optional in setup. Resolution order is:

1. Explicit `outboundPort` override; historical CLIENT `port` values remain valid
   remote overrides when the connection has no explicit default-selection mode.
2. The pinned profile's `transport_config[transport].default_port`. Older CLIENT
   profiles may also supply a remote `configDefaults.port`.
3. `bridge.outbound-defaults.astm-port` (12001) or
   `bridge.outbound-defaults.hl7-port` (2575), configurable deployment fallbacks.
   These defaults do not establish an instrument's actual listening port.

A profile can declare `outboundPortMode` as an optional SELECT field, with
`DEFAULT` and `OVERRIDE` choices, and default it to `DEFAULT` in `configDefaults`.
An optional NUMBER field `outboundPort` can depend on `outboundPortMode=OVERRIDE`.
Selecting `DEFAULT` ignores a previously saved numeric override, so an editor
that omits hidden fields can reset the destination without clearing unrelated
configuration. With no saved override, a blank field uses the fallback chain.
After saving an override, choose `DEFAULT` to stop using it; clearing the numeric
field alone is omitted by the current editor and does not clear saved values. Published revisions and
existing connection pins are not rewritten; changed profile descriptors require
an explicitly adopted revision. Replies on an established incoming session need
no destination port.

Remote probe details include the attempted host, port, port source, and failure
remediation. A refusal or timeout does not establish that the port alone is
wrong: check the address, port, listening service and network access, then retest.
The current OpenELIS screen shows the failure status but does not render all
these returned details; richer display remains an OpenELIS follow-up.

For results-only SERVER connections, the additional `analyzer` reachability
check is advisory. Without a saved host it is skipped. Reachability uses ICMP
where permitted, otherwise TCP port 7; a refused connection proves the host is
up, but a firewall can make a working analyzer appear unreachable. Only a result
arriving proves the analyzer-to-Bridge path. A probe does not activate the
connection and does not block activation.

Retained-message replay enforces the same saved protocol and transport as first
receipt. A mismatched message remains held until compatible connection
configuration is activated. When a raw receipt becomes per-accession deliveries,
its retry actor/time and recorded retry history are retained on those deliveries.

Retried HL7 messages resolve the sender from MSH-3 in the stored raw message,
including entries whose historical hint combined application and facility.
The original hint and raw message remain unchanged for audit.

HL7 messages are not rejected by a per-IP spacing timer. Each received message
uses the durable ingestion path, including messages from multiple instruments
sharing one address.

### Test Times

An ASTM result's `effectiveDateTime` is the time the analyzer performed the test:
the first readable of ASTM R.13 (completed) and R.12 (started). ASTM times carry no
offset, so they are read in the JVM's zone, which follows the container's `TZ`;
set `TZ` to the site's zone (for example `TZ=Pacific/Port_Moresby`); the image
defaults to UTC. Without a readable time, OpenELIS records the import time. HL7
and file results do not carry the analyzer's test time.

## Monitoring & Observability

### Health Checks

```bash
# Overall status only (public)
curl -k https://localhost:8442/actuator/health

# Every component with its details (authenticated)
curl -k --cert openelis.crt --key openelis.key https://localhost:8442/actuator/health

# Individual transport health (authenticated)
curl -k --cert openelis.crt --key openelis.key https://localhost:8442/actuator/health/httpforward   # OpenELIS connectivity
curl -k --cert openelis.crt --key openelis.key https://localhost:8442/actuator/health/mllp          # MLLP listener status
curl -k --cert openelis.crt --key openelis.key https://localhost:8442/actuator/health/serial        # Serial port status
curl -k --cert openelis.crt --key openelis.key https://localhost:8442/actuator/health/filewatcher   # File watcher status
```

The server always uses TLS on 8443; `-k` accepts the self-signed development
certificate.

Anonymous callers get only `{"status":"UP"}` (or `DOWN`). Components and their
details, which include connection IDs, outbox counts and serial device paths, are
shown to authenticated callers only (`show-details: when-authorized`).

Health indicators are individually enabled/disabled via configuration:

```yaml
management:
  health:
    mllp:
      enabled: true
    serial:
      enabled: true
    filewatcher:
      enabled: true
    httpforward:
      enabled: true
```

### Prometheus Metrics

When `prometheus` is in `management.endpoints.web.exposure.include` (the sample
`configuration.yml` includes it), Prometheus-format metrics are served at
`/actuator/prometheus`. Like every endpoint except the health status, it requires
HTTP Basic, so the scrape job needs `basic_auth`:

```yaml
scrape_configs:
  - job_name: openelis-analyzer-bridge
    scheme: https
    metrics_path: /actuator/prometheus
    basic_auth:
      username: bridge
      password_file: /etc/prometheus/bridge-password
    static_configs:
      - targets: ["openelis-analyzer-bridge:8443"]
```

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `bridge_messages_received_total` | Counter | protocol, transport | Messages received from analyzers |
| `bridge_messages_routed_total` | Counter | protocol, transport, result | Messages durably accounted for: queued for delivery or held as dead letters (`success`), or not persisted (`failure`). This is not delivery to OpenELIS; see the outbox for that |
| `bridge_messages_routing_duration_seconds` | Timer | protocol, transport | Time from receipt until the message is queued |
| `bridge_identity_mismatch_total` | Counter | protocol, transport, mode | Cross-checks of the connection source against the in-message sender: corroboration, mismatch, or rejection of an unregistered source |

**Example PromQL queries:**

```promql
# Message throughput per minute
rate(bridge_messages_received_total[1m])

# Routing success rate
rate(bridge_messages_routed_total{result="success"}[5m])
  / rate(bridge_messages_routed_total[5m])

# P95 latency by protocol
histogram_quantile(0.95, rate(bridge_messages_routing_duration_seconds_bucket[5m]))
```

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8443
    scheme: HTTPS
  initialDelaySeconds: 120
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8443
    scheme: HTTPS
  initialDelaySeconds: 30
  periodSeconds: 10
```

## Security

### Pairing with OpenELIS

The Bridge serves HTTPS with its own identity. When no `server.ssl` keystore is
mounted, it generates a key pair in `bridge.identity.directory` on first start
and keeps it there, so keep that directory on the persistent volume. A mounted
keystore is used as it is.

OpenELIS authenticates by pairing. Until it pairs, anonymous callers reach only
`GET /actuator/health` (overall status), `GET /pairing`, `POST /pairing`, and the
analyzer transport `POST /input`. Every other endpoint, including any added
later, answers 401. To pair, OpenELIS posts the pairing code to `/pairing` while
presenting its TLS client certificate:

```bash
curl -k --cert openelis.crt --key openelis.key -H 'Content-Type: application/json' \
  -d '{"code":"LSHE-NEYZ-MNJ4-RP98-QR7B","serverCertificateSha256":"<sha256 of the certificate OpenELIS serves>"}' \
  https://localhost:8442/pairing
```

The Bridge records the SHA-256 fingerprints of that client certificate and of
the certificate OpenELIS serves (the client certificate's, when
`serverCertificateSha256` is omitted), and answers with its own certificate and
fingerprint for OpenELIS to pin. From then on only that client certificate
authenticates, and result delivery and the OpenELIS health probe present the
Bridge certificate and trust OpenELIS by its pinned fingerprint, or by the system
trust store for its host.

The code comes from `BRIDGE_PAIRING_CODE`. Without one, an unpaired Bridge logs a
generated code at each start. A code pairs once; ten wrong codes close pairing
until the next start. To pair a different OpenELIS, or after OpenELIS changes its
certificate, set a new `BRIDGE_PAIRING_CODE` and restart: the next pairing with
it replaces the current one. Removing `pairing.json` from the identity directory
unpairs the Bridge.

### Passwords until pairing

A configured `bridge.security.password` keeps HTTP Basic working for a Bridge
that has not been paired, so an OpenELIS that still uses a password is not cut
off by an upgrade. The first pairing ends password access. Leave the password
empty to rely on pairing alone; a blank password is never a credential. The
shipped default `changeme` stops startup unless `spring.profiles.active` includes
`dev` or `test`, and `bridge.security.enabled=false` stops startup too.
Pre-encoded passwords in Spring's delegating form (`{bcrypt}$2a$10$...`) are
stored as-is; plaintext is BCrypt-encoded once at startup.

### Analyzer transports

Non-HTTP transports (ASTM/TCP, MLLP, serial, file) do not authenticate; they
attribute traffic by address and sender name. Each is bounded: MLLP caps
concurrent connections, message size and message time
(`org.itech.ahb.mllp.max-connections`, `max-message-bytes`,
`message-timeout-seconds`); ASTM receipts have per-frame and per-message
deadlines and size limits; watched files over `bridge.file.max-file-size-bytes`
are parked unread; FILE directories must lie under `bridge.file.import-roots`.

Active connections have distinct runtime registrations even when they share an
analyzer host. A host-only inbound lookup is accepted only when it identifies one
active connection; shared hosts require a connection-specific source binding.

HTTP input ignores `X-Forwarded-For`, `X-Real-IP`, and `X-Forwarded-Port` by default.
Behind a reverse proxy, set `bridge.http.trusted-proxies` to a comma-separated
string of trusted proxy IP addresses (for example, `"192.0.2.2,192.0.2.3"`). Only these
socket peers may supply forwarded identity. The address chain is read from right
to left and stops at the first untrusted peer, so a client-supplied prefix cannot
choose a different analyzer. Configure trusted proxies to append the actual peer
address and overwrite forwarded port and real-IP headers. Do not enable generic
servlet/container forwarded-header rewriting: keep
`server.forward-headers-strategy=none` so Bridge can inspect the real socket peer.

HTTP `/input` accepts ASTM, HL7 and profile-configured CSV/TSV messages through active saved connections. It uses the shared HTTP endpoint; no per-analyzer listening port or watched directory is required. The source peer identifies the connection, and a sender whose address is not an active HTTP connection is refused with 403 before anything is stored. A body larger than `bridge.http.max-input-bytes` (20 MB) is refused. An automatic connection test cannot prove that an incoming HTTP sender works; verification requires actual result delivery. The existing test response explains this limitation.

For HTTP connections, `host` must be a numeric IPv4 or IPv6 address, not a
hostname, port-qualified address, network range, or scoped/interface address.
Saved sender bindings, incoming addresses, and trusted proxy addresses use the
same normalized representation, including equivalent IPv6 spellings. Hostnames
are never resolved to authorize an HTTP sender. This restriction does not change
hostname support for outbound TCP connections.

## Result Delivery

### Analyzer -> OpenELIS (results submission)

- Bridge accepts traffic only for an active saved connection.
- The pinned profile determines parsing, result selection, control recognition,
  and optional LOINC hints.
- Bridge posts `application/fhir+json` to `/analyzer/fhir` for ASTM, HL7,
  serial, HTTP, and FILE traffic.
- The normalized bundle carries the exact Bridge connection and profile
  revision, raw analyzer code and value, transport, and control-recognition
  evidence. OpenELIS does not infer identity from source headers or analyzer
  names.

### The delivery guarantee

Once the bridge receives a result it keeps the complete message until OpenELIS
durably accepts it. DNS failures, OpenELIS outages, bridge restarts and
exhausted retries cannot discard it. Every received result ends up in one of two
places: delivered, or in the dead-message queue with its full payload and a
reason a person can act on.

This matters because most analyzer protocols give the bridge no way to refuse a
message after the fact. ASTM acknowledges each frame as it arrives, so by the
time a forward could fail the analyzer's session is over. The only thing that
can save the result is the bridge having stored it first, which is what it now
does before any network I/O.

Lifecycle of one delivery:

```
RECEIVED ──▶ PENDING ──▶ RETRYING ──▶ DELIVERED
     │            │           │
     └────────────┴───────────┴────▶ DMQ  (needs a person; payload intact)
```

| State | Meaning |
|---|---|
| `RECEIVED` | Stored on arrival, before the source is identified or anything is parsed |
| `PENDING` | Rendered into the OpenELIS contract and waiting for its first attempt |
| `RETRYING` | An attempt failed in a way that can still succeed; scheduled with backoff |
| `DELIVERED` | OpenELIS durably accepted it and returned a receipt |
| `DMQ` | Cannot be delivered without a person: retries spent, OpenELIS refused it, or it could not be rendered |

What a transport reports back to an analyzer means "the bridge is holding this
result", not "OpenELIS has it". The bridge refuses a message only when it does
not have it, because for analyzers that resend on failure that is the one answer
that can still save the result.

Retries are safe because each delivery carries an identity derived from the
received content, which OpenELIS deduplicates on. A redelivery after a restart
carries the same identity as the first attempt, so a result accepted once is
never staged twice.

A message whose rendering fails, including one that throws or exhausts memory,
goes to the dead-message queue with the error, where it can be retried after a
fix or dismissed; the dispatcher moves on to the entries behind it.

### Operating the delivery queue

All endpoints require authentication (see Security) and live under `/admin/outbox`.
The examples use a password, which works only before pairing; once paired, use the
OpenELIS client certificate (`--cert`/`--key`), or the OpenELIS analyzer pages.

```bash
# What is the bridge holding, and is anything stuck?
curl -u "$USER:$PASS" https://bridge:8443/admin/outbox/stats

# Everything waiting for a person, most recent first
curl -u "$USER:$PASS" "https://bridge:8443/admin/outbox?state=DMQ"

# One entry, with what OpenELIS said on each attempt
curl -u "$USER:$PASS" https://bridge:8443/admin/outbox/<id>

# Send one held result again
curl -u "$USER:$PASS" -X POST https://bridge:8443/admin/outbox/<id>/retry

# After fixing an outage, release everything it stopped
curl -u "$USER:$PASS" -X POST https://bridge:8443/admin/outbox/retry \
  -H 'Content-Type: application/json' \
  -d '{"all":true,"failureReason":"RETRY_EXHAUSTED"}'
```

Listings never contain the result itself. The message is served only from
`/admin/outbox/<id>/payload?part=raw|fhir`, every read is logged with the user
who made it, and a deployment can switch that endpoint off with
`bridge.outbox.payload-access-enabled=false`.

#### Triaging by failure reason

| Reason | What happened | What to do |
|---|---|---|
| `RETRY_EXHAUSTED` | OpenELIS stayed unreachable for the whole retry budget | Fix the outage, then bulk retry |
| `OE_CONFIG_STATE` | OpenELIS returned 422: its own configuration does not accept this delivery yet (unknown connection, missing site binding, profile mismatch) | Fix it in OpenELIS, then retry |
| `OE_REJECTED` | OpenELIS refused the delivery outright | Read the attempt history; usually a contract or credentials problem |
| `UNREGISTERED_SOURCE` | No saved, active connection for the sender | Register the analyzer, then retry |
| `AMBIGUOUS_SOURCE` | Two or more connections on the same listener could own the message and nothing in it tells them apart; the detail names them | Give each a distinct `host`, or set `senderId` to each instrument's system name, then retry |
| `CONNECTION_TRANSPORT_MISMATCH` | Known sender, wrong transport for its saved connection | Correct the connection, then retry |
| `UNPINNED_PROFILE` | The connection has no pinned profile, so results cannot be classified | Pin a profile revision, then retry |
| `PARSE_NO_RESULTS` | The message parsed but produced nothing to deliver | Read the payload; usually a profile or fixture mismatch |
| `OE_UNEXPECTED_REDIRECT` | Something answered with a redirect, which a result POST never follows | Check what sits between the bridge and OpenELIS |

An operator retry re-sends the stored bundle as-is. A message that was never
rendered (unregistered or ambiguous source, for example) has no bundle yet, so a
retry resolves its source against the current configuration first. FILE receipts
instead retain their original parser context and selected assay; they never
reinterpret a source path or a changed live profile. Once rendered, retries send
the stored bundle, preserving the identity OpenELIS deduplicates on.

The payload endpoint identifies raw storage with `X-Bridge-Payload-Encoding`
(`UTF8` or `BASE64`). With payload access enabled, authenticated operators can
retrieve exact FILE bytes from `GET /admin/outbox/{id}/raw-file`. Access is audited.
Resetting discovery state does not remove queued deliveries or their deduplication
identity.

#### Acknowledged ASTM frames and interrupted transmissions

For TCP LIS01-A/E1381-95 and serial ASTM, each valid data frame is committed to
SQLite with `synchronous=FULL` before Bridge sends its ACK. Identical retransmission
of the previous frame is acknowledged without duplicating its content. Frame numbers
wrap through zero. ENQ acknowledgment only establishes the session; it does not
acknowledge any clinical data.

Until an ETX-ended message is terminated with EOT, its accepted frames appear in the
ordinary dead-message queue as `INCOMPLETE_TRANSMISSION`. Disconnect, timeout or a
process crash leaves those bytes there. Request a full retransmission from the analyzer:
Retry returns 409 for incomplete input, and bulk Retry skips it. Bridge must never turn
an acknowledged prefix into a clinical result. A missing EOT is incomplete even when
the last received frame used ETX.

On completion, the assembled message enters the ordinary outbox in the same transaction
that removes its incomplete receipt. Original frames remain attached to its deliveries.
`GET /admin/outbox/{id}/astm-frames` downloads the retained wire bytes, with the same
authentication, payload-access switch and read audit as other clinical payload endpoints.
Complete query-only sessions are not result deliveries and do not enter the result DMQ.
Serial HL7 likewise commits its complete received message before emitting `MSA|AA`.

The outbox upgrade to schema version 4 is automatic and additive. Preserve its persistent
volume. These guarantees require functioning durable storage; if a write fails Bridge
withholds the positive data acknowledgment. They do not claim receipt of bytes that never
reached Bridge or protection against destruction of the storage volume.

#### If the outbox database is lost

The outbox is the only copy of a result between receipt and delivery. If it
fails to open because the file is damaged, the bridge renames it aside and
starts a fresh one so the site keeps working, and logs the renamed path at
ERROR. That renamed file is incident evidence: preserve it, and reconcile
against OpenELIS before trusting that nothing was lost. The bridge cannot
recover those results by itself.

### OpenELIS -> Analyzer (orders)

`POST /api/orders` dispatches a LOINC-coded order through an active saved
connection that supports outbound orders; the Bridge translates each LOINC code
to the analyzer's test code and sends an HL7 ORM or ASTM order to the
connection's outbound endpoint.

```bash
curl -k --cert openelis.crt --key openelis.key -X POST https://localhost:8442/api/orders \
  -H "Content-Type: application/json" \
  -d '{"connectionId":"<saved connection id>",
       "order":{"accessionNumber":"ACC-1","patientId":"P-1","loincCodes":["94500-6"]}}'
```

## Testing

The acceptance suite builds the Docker image and drives it through the delivery
outage scenarios, so a green run means the release artifact survives them, not
just the source tree:

```bash
ANALYZER_MOCK_DIR=/path/to/analyzer-mock-server ./scripts/e2e-tests/run-all.sh
```

It covers OpenELIS unreachable with the bridge container recreated mid-outage, an answer lost
after OpenELIS accepted the result, and a result recovered from the dead-message
queue by an operator retry. It needs the analyzer mock to send traffic, so it
runs where both are assembled: OpenELIS CI runs it against its Bridge and mock
submodules. This repository's CI runs only the Bridge's own tests.

Locally the suite starts its own isolated stack: a compose project named after
the checkout, free host ports, and a free test subnet (`scripts/e2e-tests/isolation.sh`),
so it runs next to any other stack on the machine. Set `E2E_BRIDGE_PORT`,
`E2E_WIREMOCK_PORT`, `E2E_MOCK_PORT`, `E2E_ASTM_LIS1A_PORT`, `E2E_ASTM_E1381_PORT`,
`E2E_MLLP_PORT`, `E2E_SUBNET_PREFIX` or `COMPOSE_PROJECT_NAME` to pin any of them.
In CI (`CI` set) the fixed defaults in `docker-compose.test.yml` apply.

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify
```

### Protocol Integration

These scripts require saved, active test connections; enabling a transport alone
does not register an analyzer. For the HL7 script, first activate an HL7 server
connection, then set `BRIDGE_CONNECTION_ID`, and `BRIDGE_MLLP_PORT` if the shared
MLLP listener is not published on 2575. Set
`BRIDGE_PASSWORD` (and optionally `BRIDGE_USER`) when API authentication is
enabled. Its forwarding destination must be the isolated test WireMock service.
The script verifies both the protocol acknowledgement and saved connection identity.

For self-contained HL7 lifecycle and disk-backed restart checks without preparing
a deployment, run `mvn test -Dtest=Hl7SavedConnectionTest,HapiConnectionLifecycleTest`.

```bash
# Assembled Bridge transport and normalized-contract tests
mvn -Dtest=UnifiedRoutingTest,HttpForwardingRouterTest test

# Virtual serial integration, when socat ports are available
./scripts/e2e-tests/test-serial.sh
```

Tests that need the Bridge and
[DIGI-UW/analyzer-mock-server](https://github.com/DIGI-UW/analyzer-mock-server)
together (the acceptance suite, and `PriorityProfileMockFixtureTest`, which runs
only with `-DanalyzerMockDir`) run in OpenELIS CI against its submodules, which
record the versions that go together. Visible OpenELIS user stories are tested
separately through the browser.

## Project Structure

```
openelis-analyzer-bridge/
├── src/main/java/org/itech/ahb/
│   ├── config/              # Configuration classes
│   ├── connection/          # Saved connections, activation and shared listeners
│   ├── connectivity/        # Connection probes
│   ├── controller/          # HTTP endpoints (/input, admin, outbox, orders, queries)
│   ├── fhir/                # Result parsers and normalized FHIR bundle building
│   ├── file/                # File watcher transport and FILE state store
│   ├── health/              # Health indicators (HTTP, MLLP, Serial, File, outbox)
│   ├── metrics/             # Prometheus metrics service
│   ├── mllp/                # MLLP transport (HL7 v2.x)
│   ├── model/               # Protocol/Transport enums
│   ├── normalizer/          # Message normalization and analyzer identification
│   ├── order/               # Outbound order building and ASTM dispatch
│   ├── outbox/              # Durable delivery outbox and dispatcher
│   ├── profile/             # Analyzer profile catalog and validation
│   ├── routing/             # Rendering and queueing for delivery
│   ├── serial/              # Serial port transport
│   ├── store/               # SQLite support
│   └── util/                # Utilities
├── astm-http-lib/           # ASTM protocol library
├── contracts/analyzer/v1/   # Profile, connection and normalized-result schemas
├── configuration.yml        # Sample runtime configuration
├── docker-compose.yml       # Runs the published image
└── scripts/e2e-tests/       # Docker acceptance suite
```

## Contracts

- `contracts/analyzer/v1/normalized-fhir-bundle.schema.json`: normalized result
  contract consumed by OpenELIS
- `contracts/analyzer/v1/fixtures/`: canonical ASTM, HL7, and FILE examples
- `src/main/resources/analyzer-profiles/`: shipped analyzer type profiles

The acceptance suite runs against the analyzer-mock revision pinned in
`.github/workflows/test.yml`.

## License

Mozilla Public License 2.0; see [LICENSE.md](LICENSE.md).
