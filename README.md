# ASTM-HTTP Bridge

A bi-directional protocol translator for medical laboratory systems, enabling communication between ASTM/TCP analyzers and HTTP-based Laboratory Information Systems (LIS) like OpenELIS.

## Architecture Overview

The ASTM-HTTP Bridge provides two-way communication:

```
┌─────────────────────┐                    ┌─────────────────────┐
│   Medical Analyzer  │                    │      OpenELIS       │
│   (ASTM/TCP)        │                    │      (HTTP)         │
└─────────┬───────────┘                    └──────────┬──────────┘
          │                                           │
          │ ASTM Protocol                   HTTP POST │
          │ (TCP Port 12001)            (Port 8443)   │
          ▼                                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                      ASTM-HTTP Bridge                           │
│                                                                 │
│  ┌─────────────────┐              ┌─────────────────┐          │
│  │ ASTM Listener   │  ───────────►│ HTTP Forwarder  │          │
│  │ (Analyzer→LIS)  │              │ + X-Source-IP   │          │
│  └─────────────────┘              └─────────────────┘          │
│                                                                 │
│  ┌─────────────────┐              ┌─────────────────┐          │
│  │ HTTP Listener   │  ◄───────────│ ASTM Forwarder  │          │
│  │ (LIS→Analyzer)  │              │ + Query Support │          │
│  └─────────────────┘              └─────────────────┘          │
└─────────────────────────────────────────────────────────────────┘
```

### Communication Flows

1. **Analyzer → OpenELIS** (Results submission)
   - Analyzer sends ASTM message to bridge (TCP port 12001)
   - Bridge extracts analyzer's IP address from socket
   - Bridge forwards message to OpenELIS via HTTP POST
   - HTTP request includes `X-Source-Analyzer-IP` header with analyzer's IP

2. **OpenELIS → Analyzer** (Query/Configuration)
   - OpenELIS sends HTTP POST to bridge with `forwardAddress` and `forwardPort` parameters
   - Bridge establishes ASTM connection to specified analyzer
   - Bridge forwards the query and returns analyzer's response

## Quick Start

### Using Docker (Recommended)

```bash
# Clone the repository
git clone https://github.com/your-org/astm-http-bridge.git
cd astm-http-bridge

# Start with default configuration
docker compose up -d --build

# View logs
docker logs --follow astm-http-bridge
```

### Building from Source

```bash
# Build the library first
cd astm-http-lib
mvn clean install

# Build the main application
cd ..
mvn clean package

# Run the application
java -jar target/astm-http-bridge-*.jar --spring.config.location=configuration.yml
```

## Configuration

Create a `configuration.yml` file in the project root:

```yaml
org:
  itech:
    ahb:
      # Forward HTTP Server - OpenELIS endpoint
      forward-http-server:
        uri: https://openelis.example.org:8443/api/OpenELIS-Global/analyzer/astm
        # Optional: Basic authentication
        username: admin
        password: ${OPENELIS_PASSWORD}  # Use environment variable
      
      # ASTM LIS1-A Server - Analyzer connection port
      listen-astm-server:
        port: 12001
        establishment-timeout-seconds: 15
        receive-timeout-seconds: 30
      
      # ASTM E1381-95 Server - Legacy analyzer support
      listen-astm-e1381-95-server:
        port: 12011
      
      # Default ASTM target for queries
      forward-astm-server:
        host-name: analyzer.local
        port: 5000

server:
  port: 8443

logging:
  level:
    org.itech: INFO  # Set to DEBUG for troubleshooting

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### Configuration Properties Reference

| Property | Description | Default |
|----------|-------------|---------|
| `org.itech.ahb.forward-http-server.uri` | OpenELIS endpoint URI | Required |
| `org.itech.ahb.forward-http-server.username` | Basic auth username | Optional |
| `org.itech.ahb.forward-http-server.password` | Basic auth password | Optional |
| `org.itech.ahb.listen-astm-server.port` | ASTM LIS1-A listen port | 12001 |
| `org.itech.ahb.listen-astm-e1381-95-server.port` | E1381-95 listen port | 12011 |
| `org.itech.ahb.forward-astm-server.host-name` | Default analyzer host | localhost |
| `org.itech.ahb.forward-astm-server.port` | Default analyzer port | 5000 |
| `server.port` | HTTP server port | 8443 |

## Multi-Analyzer Setup

The bridge automatically supports multiple concurrent analyzer connections. Each analyzer connects to the same bridge port (12001), and the bridge:

1. **Identifies each analyzer** by its source IP address
2. **Tags each message** with the `X-Source-Analyzer-IP` HTTP header
3. **Maintains separate threads** for each connection

### OpenELIS Analyzer Configuration

Register each analyzer in OpenELIS with its IP address:

| Analyzer | IP Address | Type |
|----------|------------|------|
| Hematology Analyzer | 192.168.1.10 | Blood Count |
| Chemistry Analyzer | 192.168.1.11 | Clinical Chemistry |
| Immunology Analyzer | 192.168.1.12 | Immunoassay |

OpenELIS uses the `X-Source-Analyzer-IP` header to apply the correct field mappings for each analyzer.

## X-Source-Analyzer-IP Header

When forwarding ASTM messages to OpenELIS, the bridge includes the source analyzer's IP address in an HTTP header:

```http
POST /api/OpenELIS-Global/analyzer/astm HTTP/1.1
Host: openelis.example.org:8443
Content-Type: text/plain
X-Source-Analyzer-IP: 192.168.1.10

H|\^&|||Analyzer^Model|||||||LIS01-A|P|1
P|1||PatientID||LastName^FirstName
...
```

### Header Behavior

- **Present**: When source IP is successfully extracted from the TCP socket
- **Absent**: If extraction fails (graceful degradation - message still forwarded)
- **Format**: IPv4 (`192.168.1.10`) or IPv6 (`2001:db8::1`)

## Querying Analyzers

OpenELIS can query analyzers through the bridge:

```bash
curl -X POST "http://bridge:8443/?forwardAddress=192.168.1.10&forwardPort=5000" \
  -H "Content-Type: text/plain" \
  -d "H|\^&|||"
```

### Query Parameters

| Parameter | Description | Required |
|-----------|-------------|----------|
| `forwardAddress` | Target analyzer IP | Yes |
| `forwardPort` | Target analyzer port | Yes |
| `forwardAstmVersion` | Protocol version (LIS01_A or E1381_95) | No (defaults to LIS01_A) |

## Testing

### Test Environment Setup

The project includes a Docker Compose test environment with mock analyzers:

```bash
# Clone the ASTM Mock Server
git clone https://github.com/DIGI-UW/astm-mock-server.git tools/astm-mock-server

# Start the test environment
docker compose -f docker-compose.test.yml up -d

# Watch HTTP capture logs to see X-Source-Analyzer-IP headers
docker logs -f astm-http-bridge-http-capture-1
```

### Verify Source IP Header

```bash
# From mock-analyzer-1 (IP: 172.28.0.10)
docker exec -it astm-http-bridge-mock-analyzer-1-1 \
  python server.py --push http://172.28.0.100:12001 --analyzer-type HEMATOLOGY

# Check the HTTP capture logs - should show:
# X-Source-Analyzer-IP: 172.28.0.10
```

### Run Unit Tests

```bash
mvn test
```

## Troubleshooting

### Analyzer Can't Connect

1. **Check port accessibility**:
   ```bash
   telnet bridge-host 12001
   ```

2. **Verify bridge is listening**:
   ```bash
   docker logs astm-http-bridge | grep "listening"
   ```

3. **Check firewall rules**:
   ```bash
   sudo ufw status
   ```

### Messages Not Reaching OpenELIS

1. **Enable debug logging**:
   ```yaml
   logging:
     level:
       org.itech: DEBUG
   ```

2. **Check connection to OpenELIS**:
   ```bash
   curl -X POST https://openelis:8443/api/OpenELIS-Global/analyzer/astm \
     -H "Content-Type: text/plain" \
     -d "test"
   ```

3. **Verify configuration URI**:
   ```bash
   docker exec astm-http-bridge cat /app/configuration.yml
   ```

### X-Source-Analyzer-IP Header Missing

1. **Check for extraction warnings in logs**:
   ```bash
   docker logs astm-http-bridge | grep -i "cannot extract"
   ```

2. **Verify analyzer is connecting directly** (not through proxy)

3. **Check socket state** - ensure analyzer maintains connection during transmission

### Query Timeouts

1. **Verify analyzer is reachable**:
   ```bash
   telnet analyzer-ip 5000
   ```

2. **Increase timeout if needed** in configuration

3. **Check for line contention** - analyzer may be sending while bridge is querying

## Protocol Support

| Protocol | Port | Standards |
|----------|------|-----------|
| ASTM LIS1-A | 12001 | CLSI LIS1-A (LIS01-A) |
| ASTM E1381-95 | 12011 | ASTM E1381-95 |
| HTTP | 8443 | REST API |

### CLSI LIS1-A Compliance

- Frame numbering and checksums
- ENQ/ACK/NAK handshakes
- Line contention handling (section 8.3.5)
- Configurable timeouts (15s establishment, 30s receive)

## Health Monitoring

```bash
# Health check endpoint
curl http://bridge:8443/actuator/health

# Response
{
  "status": "UP",
  "components": {
    "ping": {"status": "UP"}
  }
}
```

## Development

### Project Structure

```
astm-http-bridge/
├── src/main/java/org/itech/ahb/     # Spring Boot application
│   ├── controller/                   # HTTP endpoints
│   └── config/                       # Configuration classes
├── astm-http-lib/                    # ASTM protocol library
│   └── src/main/java/org/itech/ahb/lib/
│       ├── astm/                     # ASTM handling
│       └── http/                     # HTTP forwarding
├── configuration.yml                 # Runtime configuration
└── docker-compose.yml               # Deployment configuration
```

### Building

```bash
# Build library
cd astm-http-lib && mvn clean install

# Build application
cd .. && mvn clean package
```

## License

[Add your license information here]

## Contributing

[Add contribution guidelines here]

