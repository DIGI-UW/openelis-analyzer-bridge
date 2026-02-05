# Feature Specification: Bi-Directional ASTM Workflow Support

**Feature Branch**: `001-bi-directional-astm`  
**Created**: 2025-12-03  
**Status**: Complete  
**Input**: Deep analysis of ASTM bridge documentation and OpenELIS integration requirements  
**Test Tool**: [ASTM Mock Server](https://github.com/DIGI-UW/astm-mock-server)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Analyzer Sends Results with Source Identification (Priority: P1)

A medical laboratory analyzer sends test results through the ASTM-HTTP Bridge to OpenELIS. 
OpenELIS needs to know which specific analyzer sent the results so it can apply the correct 
field mappings and route results to the appropriate test configurations.

**Why this priority**: Without source analyzer identification, OpenELIS cannot distinguish 
between multiple analyzers using the same bridge, making multi-analyzer deployments impossible. 
This is the critical missing capability identified in the gap analysis.

**Independent Test**: Can be fully tested using the [ASTM Mock Server](https://github.com/DIGI-UW/astm-mock-server) 
in push mode (`python server.py --push`) to send ASTM messages through the bridge, then verifying 
that OpenELIS receives an HTTP request containing the mock server's IP address in the 
`X-Source-Analyzer-IP` header. Delivers immediate value for multi-analyzer laboratory deployments.

**Acceptance Scenarios**:

1. **Given** an analyzer at IP address 192.168.1.10 connected to the bridge on TCP port 12001, 
   **When** the analyzer sends an ASTM result message, **Then** the bridge forwards the message 
   to OpenELIS via HTTP POST with an `X-Source-Analyzer-IP: 192.168.1.10` header.

2. **Given** multiple analyzers (192.168.1.10, 192.168.1.11, 192.168.1.12) connected 
   simultaneously to the bridge, **When** each analyzer sends messages, **Then** each HTTP 
   forward to OpenELIS contains the correct source IP for that specific analyzer.

3. **Given** an analyzer connected through a NAT gateway or proxy, **When** the bridge 
   extracts the source IP, **Then** it uses the actual remote socket address (the IP visible 
   to the bridge), not any proxy headers, since the bridge is the first point of contact.

---

### User Story 2 - OpenELIS Queries Analyzer Fields (Priority: P2)

A laboratory administrator configures a new analyzer in OpenELIS and needs to query the 
analyzer to discover its available test fields and data structure. OpenELIS sends a query 
message through the bridge, which forwards it to the specific analyzer's TCP endpoint.

**Why this priority**: Field discovery enables dynamic analyzer configuration without manual 
data entry, significantly reducing setup time and errors. This flow already works but needs 
verification and documentation.

**Independent Test**: Can be tested using the [ASTM Mock Server](https://github.com/DIGI-UW/astm-mock-server) 
in server mode (`python server.py --port 5000`) to receive queries. Send an HTTP POST to the 
bridge with `forwardAddress` and `forwardPort` targeting the mock server, and verify the mock 
server receives the query and the bridge returns the mock server's field list response.

**Acceptance Scenarios**:

1. **Given** OpenELIS wants to query an analyzer at 192.168.1.10:5000, **When** OpenELIS sends 
   `POST /?forwardAddress=192.168.1.10&forwardPort=5000` with an ASTM query message, **Then** 
   the bridge establishes a TCP connection to the analyzer and forwards the query.

2. **Given** the analyzer responds to a query with a field list (using line contention/role 
   reversal per CLSI LIS1-A 8.3.5), **When** the bridge receives the response, **Then** the 
   bridge returns the complete response to OpenELIS in the HTTP response body.

3. **Given** the analyzer doesn't respond within the configured timeout, **When** the timeout 
   expires, **Then** the bridge returns an appropriate error response to OpenELIS indicating 
   the query failed.

---

### User Story 3 - Configuration and Documentation (Priority: P3)

A DevOps engineer or system administrator deploys the ASTM-HTTP Bridge in a laboratory 
environment and needs clear documentation to configure the bridge for their specific 
network topology, including multiple analyzers and integration with OpenELIS.

**Why this priority**: Proper documentation reduces deployment errors and support burden. 
The current configuration file structure doesn't match Spring Boot properties, causing 
confusion.

**Independent Test**: Can be tested by following the documentation to deploy the bridge 
in a test environment and verifying all features work as documented.

**Acceptance Scenarios**:

1. **Given** the bridge README documents the configuration property structure, **When** an 
   administrator creates a `configuration.yml` file following the documentation, **Then** 
   the bridge starts successfully and connects to the specified OpenELIS endpoint.

2. **Given** the README includes multi-analyzer setup instructions, **When** an administrator 
   configures multiple analyzers to connect to the same bridge, **Then** each analyzer's 
   messages are correctly forwarded to OpenELIS with proper source identification.

3. **Given** the README includes troubleshooting guidance, **When** an administrator 
   encounters common issues (analyzer can't connect, messages not reaching OpenELIS), 
   **Then** they can follow documented steps to diagnose and resolve the issue.

---

### Edge Cases

- What happens when the source IP extraction fails (socket in unexpected state)?
  - Bridge MUST log a warning and forward the message without the header (graceful degradation)
  - Bridge SHOULD NOT fail or drop the message
  
- What happens when multiple messages arrive from the same analyzer simultaneously?
  - Bridge handles concurrent connections via separate threads
  - Each thread extracts IP from its own socket independently
  
- What happens when the bridge cannot connect to the target analyzer for a query?
  - Bridge returns an error response to OpenELIS with details
  - Bridge retries per configured retry policy before failing
  
- What happens when an analyzer uses IPv6 vs IPv4?
  - Bridge MUST support both address formats in the `X-Source-Analyzer-IP` header
  
- What happens during line contention (both sides try to send)?
  - Bridge handles line contention per CLSI LIS1-A 8.3.5 (existing capability)
  - Bridge receives incoming message when line is contested

## Requirements *(mandatory)*

### Functional Requirements

#### Source Analyzer Identification (P1)

- **FR-001**: Bridge MUST extract the source IP address from the TCP socket when receiving 
  ASTM messages from analyzers.

- **FR-002**: Bridge MUST include the source analyzer IP address in an `X-Source-Analyzer-IP` 
  HTTP header when forwarding messages to OpenELIS.

- **FR-003**: Bridge MUST handle IPv4 and IPv6 addresses in the source IP extraction.

- **FR-004**: If source IP extraction fails, bridge MUST log a warning and forward the 
  message without the header (graceful degradation - do not drop messages).

- **FR-005**: Bridge MUST continue to support multiple concurrent analyzer connections, 
  with each connection's messages correctly tagged with their respective source IP.

#### Bi-Directional Query Support (P2)

- **FR-006**: Bridge MUST accept HTTP POST requests with `forwardAddress` and `forwardPort` 
  parameters to route ASTM messages to specific analyzers (existing capability - verification).

- **FR-007**: Bridge MUST support the `forwardAstmVersion` parameter to select protocol 
  version (LIS01_A or E1381_95) for outbound messages (existing capability - verification).

- **FR-008**: Bridge MUST handle line contention (role reversal) per CLSI LIS1-A 8.3.5 
  when the analyzer responds to queries (existing capability - verification).

- **FR-009**: Bridge MUST return analyzer responses to OpenELIS in the HTTP response body 
  when handling query operations.

- **FR-010**: Bridge MUST apply retry logic for failed outbound connections to analyzers, 
  with configurable retry count and delay.
  - **Note**: Current implementation uses hardcoded values (max 3 retries, 10s delay). 
    Configuration via YAML is a future enhancement.

#### Configuration and Documentation (P3)

- **FR-011**: Configuration file structure MUST use correct Spring Boot property paths:
  - `org.itech.ahb.forward-http-server.uri` for OpenELIS endpoint
  - `org.itech.ahb.listen-astm-server.port` for ASTM LIS1-A listener
  - `org.itech.ahb.listen-astm-e1381-95-server.port` for E1381-95 listener
  - `org.itech.ahb.forward-astm-server.hostName` and `port` for default analyzer target

- **FR-012**: README MUST include architecture overview explaining bi-directional 
  communication flows (Analyzer → OpenELIS and OpenELIS → Analyzer).

- **FR-013**: README MUST include multi-analyzer setup instructions explaining that 
  the bridge automatically handles multiple concurrent connections.

- **FR-014**: README MUST include configuration examples for common deployment scenarios.

- **FR-015**: README MUST include troubleshooting section for common issues.

- **FR-016**: README MUST document the `X-Source-Analyzer-IP` header and its purpose.

### Key Entities

- **ASTM Message**: The raw ASTM protocol message containing laboratory data segments 
  (H, P, O, R, L records). Bridge translates but does not interpret message content.

- **Source IP**: The IP address of the analyzer that sent the message, extracted from 
  the TCP socket's remote address. Used by OpenELIS to identify which analyzer sent 
  the message.

- **Forward Target**: The destination for outbound messages (either OpenELIS for 
  analyzer-originated messages, or analyzer IP:port for OpenELIS-originated queries).

- **HTTP Headers**: Metadata included in HTTP requests, specifically `X-Source-Analyzer-IP` 
  for analyzer identification.

## Assumptions

1. **IP-based identification is sufficient**: OpenELIS can identify analyzers by IP address. 
   More complex identification (analyzer serial numbers, certificates) is out of scope.

2. **Bridge is directly connected**: The bridge receives connections directly from analyzers 
   (not through additional proxies). Source IP is the TCP socket's remote address.

3. **Single bridge per deployment**: Each laboratory deployment uses one bridge instance. 
   Multi-bridge clustering is out of scope.

4. **Existing protocol support is correct**: LIS01-A, E1381-95, and non-compliant mode 
   implementations are functionally correct (validated in compatibility analysis).

5. **OpenELIS integration points exist**: OpenELIS has the `/analyzer/astm` endpoint and 
   can handle the `X-Source-Analyzer-IP` header (integration is OpenELIS responsibility).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Messages from multiple analyzers (3+) connected simultaneously are each 
  correctly tagged with their respective source IP addresses with 100% accuracy.

- **SC-002**: OpenELIS can successfully query an analyzer through the bridge and receive 
  the response within 30 seconds (excluding analyzer processing time).

- **SC-003**: A new administrator can deploy and configure the bridge using only the 
  README documentation in under 30 minutes for a standard single-analyzer setup.

- **SC-004**: Bridge handles analyzer connection failures gracefully - no messages are 
  lost when source IP extraction fails, and appropriate warnings are logged.

- **SC-005**: Configuration file structure matches documented Spring Boot property paths, 
  eliminating configuration errors caused by incorrect property names.

- **SC-006**: All existing functionality (ASTM → HTTP forwarding, concurrent connections, 
  protocol support) continues to work after modifications (regression-free).

### Qualitative Outcomes

- **SC-007**: Documentation is comprehensive enough that support requests related to 
  bridge configuration decrease after deployment.

- **SC-008**: Multi-analyzer laboratory deployments can use a single bridge instance 
  without custom modifications.

## Testing Strategy

All functional requirements MUST be validated using the [ASTM Mock Server](https://github.com/DIGI-UW/astm-mock-server), 
which provides full CLSI LIS1-A compliance and bi-directional communication support.

### Test Environment Setup

The mock server can be deployed alongside the bridge for comprehensive testing:

1. **Mock Server as Analyzer**: Simulates analyzer sending results to bridge
2. **Mock Server as Query Target**: Receives queries from bridge and responds with field lists
3. **Multiple Mock Instances**: Simulates multi-analyzer deployments

### Test Scenarios Using ASTM Mock Server

#### TS-001: Source IP Header Verification (FR-001, FR-002)

**Setup**: 
- Start mock server: `python server.py --port 5000`
- Configure bridge to forward to a test HTTP endpoint
- Use mock server push mode to send messages through bridge

**Steps**:
1. Start mock server in push mode: `python server.py --push http://bridge:12001 --analyzer-type HEMATOLOGY`
2. Intercept HTTP request at OpenELIS endpoint
3. Verify `X-Source-Analyzer-IP` header contains mock server's IP

**Expected**: HTTP request includes correct source IP header

#### TS-002: Multi-Analyzer Concurrent Connections (FR-005)

**Setup**: 
- Start 3 mock server instances on different ports (5001, 5002, 5003)
- Use Docker to assign different IP addresses to each instance

**Steps**:
1. Start all mock servers in push mode simultaneously
2. Each mock server sends 10 messages through the bridge
3. Verify each message's `X-Source-Analyzer-IP` header matches its source

**Expected**: 30 messages received, each with correct source IP (no cross-contamination)

#### TS-003: Query Response Flow (FR-006, FR-008, FR-009)

**Setup**: 
- Start mock server in server mode: `python server.py --port 5000`
- Configure mock server with test field definitions in `fields.json`

**Steps**:
1. Send HTTP POST to bridge: `curl -X POST "http://bridge:8443/?forwardAddress=mockserver&forwardPort=5000" -d "H|\^&|||"`
2. Mock server detects query (header-only message) and responds with field list
3. Verify bridge returns field list in HTTP response body

**Expected**: Complete field list returned to caller

#### TS-004: Line Contention Handling (FR-008)

**Setup**: 
- Start mock server with query detection enabled
- Use `test_communication.py` from mock server repo

**Steps**:
1. Run: `python test_communication.py --host bridge --port 12001 --mode query`
2. Verify mock server detects query and initiates role reversal (ENQ to become sender)
3. Verify bridge handles line contention and receives response

**Expected**: Query-response cycle completes successfully via role reversal

#### TS-005: Protocol Version Selection (FR-007)

**Setup**: 
- Start mock server in E1381-95 mode if supported, or use default LIS1-A
- Configure bridge with protocol version parameter

**Steps**:
1. Send query with `forwardAstmVersion=LIS01_A`
2. Send query with `forwardAstmVersion=E1381_95`
3. Verify bridge uses correct protocol for each request

**Expected**: Bridge communicates using specified protocol version

#### TS-006: Graceful Degradation (FR-004)

**Setup**: 
- Start mock server
- Simulate IP extraction failure scenario (if possible via test framework)

**Steps**:
1. Send message through bridge under degraded conditions
2. Verify message is forwarded to OpenELIS
3. Verify warning is logged (but message not dropped)

**Expected**: Message forwarded without IP header, warning logged

### Integration Test with Docker Compose

The mock server repository includes Docker Compose configuration for OpenELIS integration:

```bash
# Start full test environment with mock server
docker compose -f dev.docker-compose.yml -f docker-compose.astm-test.yml up -d

# Run communication tests
python test_communication.py --host localhost --port 12001
```

### Automated Test Validation

Using the mock server's API mode for automated testing:

```bash
# Start mock server with HTTP API
python server.py --push https://localhost:8443 --api-port 8080

# Trigger test pushes via HTTP API
curl -X POST "http://localhost:8080/push?analyzer_type=HEMATOLOGY&count=5"

# Verify results at OpenELIS endpoint
```

### Test Coverage Matrix

| Requirement | Test Scenario | Mock Server Mode | Automated |
|-------------|---------------|------------------|-----------|
| FR-001 | TS-001 | Push | Yes |
| FR-002 | TS-001 | Push | Yes |
| FR-003 | TS-001 (IPv6) | Push | Manual |
| FR-004 | TS-006 | Push | Manual |
| FR-005 | TS-002 | Push (multiple) | Yes |
| FR-006 | TS-003 | Server | Yes |
| FR-007 | TS-005 | Server | Yes |
| FR-008 | TS-004 | Server | Yes |
| FR-009 | TS-003 | Server | Yes |
| FR-010 | TS-003 (retry) | Server (restart) | Manual |

### Mock Server Capabilities Leveraged

Based on [ASTM Mock Server documentation](https://github.com/DIGI-UW/astm-mock-server):

| Capability | Use in Testing |
|------------|----------------|
| Push Mode (`--push`) | Simulate analyzer sending results to bridge |
| Server Mode (default) | Receive queries from bridge, respond with field lists |
| API Mode (`--api-port`) | Automated test triggering via HTTP |
| Multiple Analyzer Types | Test HEMATOLOGY, CHEMISTRY, IMMUNOLOGY, MICROBIOLOGY |
| Query Detection | Validate query flow with line contention handling |
| `test_communication.py` | Comprehensive protocol compliance testing |
| Docker Support | Containerized testing environment |
