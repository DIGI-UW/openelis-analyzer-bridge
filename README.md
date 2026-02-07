# OpenELIS Analyzer Bridge

Middleware that receives analyzer messages over multiple **protocols/transports** and forwards them to **OpenELIS via HTTP**.

This repository was previously named **ASTM-HTTP Bridge**. For **backward compatibility**, some technical identifiers (Maven `artifactId`, jar filename, and some Docker/Compose service naming) still use `astm-http-bridge`.

## Architecture

```
Analyzer(s)                                    OpenELIS
───────────                                    ────────
ASTM/TCP    ─┐
HL7/MLLP    ─┤                                        ┌─ /analyzer/astm
RS232/Serial─┼─> [OpenELIS Analyzer Bridge] ──HTTP──> ├─ /analyzer/hl7
Files (CSV) ─┤   │ Protocol Detection      │          ├─ /analyzer/csv
HTTP /input ─┘   │ Analyzer Identification  │          └─ /analyzer/raw
                  │ Message Normalization    │
                  │ Metrics + Health Checks  │
                  └─────────────────────────┘
                     │
                     ├─ /actuator/health      (per-transport status)
                     ├─ /actuator/prometheus   (Prometheus metrics)
                     └─ /actuator/metrics      (Micrometer metrics)

OpenELIS ──HTTP POST──> [Bridge] ──TCP──> Analyzer (ASTM host query / outbound)
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

docker compose up -d --build
docker logs --follow astm-http-bridge
```

### Building from Source

```bash
cd astm-http-lib
mvn clean install

cd ..
mvn clean package

java -jar target/astm-http-bridge-*.jar --spring.config.location=configuration.yml
```

## Docker Deployment

### Port Mapping

| External | Internal | Service |
|----------|----------|---------|
| 8442 | 8443 | HTTPS API endpoint |
| 12000 | 12001 | ASTM LIS1-A listener |
| 12010 | 12011 | ASTM E1381-95 listener |
| 2575 | 2575 | MLLP HL7 listener |

### Volume Mounts

| Host Path | Container Path | Purpose |
|-----------|---------------|---------|
| `./configuration.yml` | `/app/configuration.yml` | Runtime configuration |
| `/path/to/import` | `/mnt/analyzer-import` | File watcher input (optional) |
| `/path/to/archive` | `/mnt/analyzer-archive` | Processed files (optional) |

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
| **OpenELIS Target** | | |
| `org.itech.ahb.forward-http-server.uri` | OpenELIS endpoint URI | Required |
| `org.itech.ahb.forward-http-server.username` | Basic auth username | Optional |
| `org.itech.ahb.forward-http-server.password` | Basic auth password | Optional |
| **ASTM TCP** | | |
| `org.itech.ahb.listen-astm-server.port` | ASTM LIS1-A listen port | 12001 |
| `org.itech.ahb.listen-astm-e1381-95-server.port` | E1381-95 listen port | 12011 |
| **MLLP (HL7)** | | |
| `org.itech.ahb.mllp.enabled` | Enable MLLP listener | false |
| `org.itech.ahb.mllp.port` | MLLP listen port | 2575 |
| **Serial** | | |
| `org.itech.ahb.serial.enabled` | Enable serial listener | false |
| **File Watcher** | | |
| `bridge.file.enabled` | Enable file watcher | false |
| `bridge.file.watchDirectories` | Directories to watch | [] |
| `bridge.file.archiveDirectory` | Processed files archive | Required if enabled |
| `bridge.file.pollIntervalMs` | Poll interval | 5000 |
| **Security (M7.1)** | | |
| `bridge.security.enabled` | Enable HTTP Basic auth on /input | true |
| `bridge.security.username` | HTTP Basic username | bridge |
| `bridge.security.password` | HTTP Basic password (use env var) | changeme |
| **Server** | | |
| `server.port` | HTTP server port | 8443 |

### Analyzer Identification

Configure analyzer mappings in `bridge.analyzers`:

```yaml
bridge:
  analyzers:
    "192.168.1.10":
      id: MINDRAY-BC5380-001
      name: "Mindray BC-5380"
      expectedProtocol: ASTM
    "/dev/ttyUSB0":
      id: HORIBA-PENTRA60-001
      name: "Horiba Pentra 60"
      expectedProtocol: ASTM
    "quantstudio-*":
      id: QUANTSTUDIO-001
      name: "QuantStudio 7 Flex"
      expectedProtocol: CSV
```

## Monitoring & Observability

### Health Checks

```bash
# Overall health
curl http://localhost:8442/actuator/health

# Individual transport health
curl http://localhost:8442/actuator/health/httpforward   # OpenELIS connectivity
curl http://localhost:8442/actuator/health/mllp          # MLLP listener status
curl http://localhost:8442/actuator/health/serial         # Serial port status
curl http://localhost:8442/actuator/health/filewatcher    # File watcher status
```

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

Prometheus-format metrics are exposed at `/actuator/prometheus`.

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `bridge_messages_received_total` | Counter | protocol, transport | Messages received from analyzers |
| `bridge_messages_routed_total` | Counter | protocol, transport, result | Messages forwarded to OpenELIS |
| `bridge_messages_routing_duration_seconds` | Timer | protocol, transport | End-to-end routing latency |

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
  initialDelaySeconds: 120
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8443
  initialDelaySeconds: 30
  periodSeconds: 10
```

## Security

The `/input` HTTP endpoint is protected with HTTP Basic authentication (M7.1).
Non-HTTP transports (ASTM/TCP, MLLP, Serial, File) are unaffected.

### Configuration

```yaml
bridge:
  security:
    enabled: true                               # false to disable (not recommended)
    username: bridge
    password: ${BRIDGE_AUTH_PASSWORD:changeme}   # Set via environment variable
```

### Usage

```bash
# Authenticated request to /input
curl -u bridge:changeme -X POST http://localhost:8442/input \
  -H "Content-Type: application/hl7-v2" \
  -d "MSH|^~\&|ANALYZER|LAB|..."

# Unauthenticated returns 401
curl -X POST http://localhost:8442/input -d "test"
# → 401 Unauthorized
```

### Production Setup

Set the password via environment variable:

```bash
export BRIDGE_AUTH_PASSWORD=your-secure-password
docker compose up -d
```

Or in Docker Compose:

```yaml
environment:
  BRIDGE_AUTH_PASSWORD: your-secure-password
```

### Disabling Security

For development only:

```yaml
bridge:
  security:
    enabled: false
```

## HTTP Behavior (ASTM Stable Path)

### Analyzer -> OpenELIS (results submission)

- Bridge receives ASTM over TCP.
- Bridge forwards the raw message to the configured OpenELIS endpoint as `text/plain`.
- Bridge adds `X-Source-Analyzer-IP` when source IP can be extracted from the TCP socket.

### OpenELIS -> Analyzer (query/config)

```bash
curl -X POST "http://bridge:8443/?forwardAddress=192.168.1.10&forwardPort=5000" \
  -H "Content-Type: text/plain" \
  -d "H|\^&|||"
```

## Testing

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify
```

### Docker E2E Tests

```bash
# Run full E2E suite (ASTM TCP, MLLP, File, HTTP)
./scripts/e2e-tests/run-all.sh

# Or start manually:
docker compose -f docker-compose.test.yml up -d --build
./scripts/e2e-tests/test-astm-tcp.sh
./scripts/e2e-tests/test-mllp.sh
./scripts/e2e-tests/test-file-csv.sh
./scripts/e2e-tests/test-http-input.sh
docker compose -f docker-compose.test.yml down
```

## Project Structure

```
openelis-analyzer-bridge/
├── src/main/java/org/itech/ahb/
│   ├── controller/          # HTTP endpoints (/input, query forwarding)
│   ├── config/              # Configuration classes
│   ├── file/                # File watcher transport
│   ├── health/              # Health indicators (HTTP, MLLP, Serial, File)
│   ├── metrics/             # Prometheus metrics service
│   ├── mllp/                # MLLP transport (HL7 v2.x)
│   ├── model/               # Protocol/Transport enums
│   ├── normalizer/          # Message normalization + routing
│   ├── routing/             # HTTP forwarding router
│   ├── serial/              # Serial port transport
│   └── util/                # Utilities
├── astm-http-lib/           # ASTM protocol library
├── configuration.yml        # Runtime configuration
├── docker-compose.yml       # Production deployment
├── docker-compose.test.yml  # E2E test environment
└── scripts/e2e-tests/       # E2E test scripts
```

## Docs

- `docs/SCOPE_AND_NAMING.md`: canonical naming + compatibility policy
- `docs/ASTM_MESSAGE_PROCESSING_FLOW.md`: ASTM flow details (OpenELIS-side processing)

## License / Contributing

TBD (add project license and contribution guidelines).
