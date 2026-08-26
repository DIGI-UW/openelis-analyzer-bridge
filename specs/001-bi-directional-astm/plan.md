# Implementation Plan: Bi-Directional ASTM Workflow Support

> Superseded historical plan. Do not use it for dependencies or current
> implementation direction; use `README.md`, `contracts/analyzer/v1`, and
> current code.

**Branch**: `001-bi-directional-astm` | **Date**: 2025-12-03 | **Spec**: [spec.md](./spec.md)  
**Input**: Feature specification from `/specs/001-bi-directional-astm/spec.md`

## Summary

Enable bi-directional ASTM workflow support by adding source analyzer IP identification 
to HTTP forwards. The critical gap is that when an analyzer sends messages through the 
bridge to OpenELIS, OpenELIS cannot identify which analyzer sent the message because the 
source IP is not included in the HTTP request headers.

**Technical Approach**:
1. Extract source IP from TCP socket in `ASTMReceiveThread`
2. Pass source IP through the handler chain to `DefaultForwardingASTMToHTTPHandler`
3. Include `X-Source-Analyzer-IP` header in HTTP POST to OpenELIS
4. Verify existing HTTP→ASTM flow works correctly
5. Update configuration and documentation

## Technical Context

**Language/Version**: Java 21  
**Framework**: Spring Boot 3.3.0  
**Build Tool**: Maven  
**Primary Dependencies**: astm-http-lib, Spring Boot Actuator, Spring Boot Web  
**Storage**: N/A (stateless protocol translator)  
**Testing**: JUnit 5, Spring Boot Test, [ASTM Mock Server](https://github.com/DIGI-UW/astm-mock-server)  
**Target Platform**: Docker container (Linux)  
**Project Type**: Single module with internal library (astm-http-lib)  
**Performance Goals**: Handle multiple concurrent analyzer connections; <100ms message forwarding  
**Constraints**: Must comply with CLSI LIS1-A timing (15s establishment, 30s receive timeout)  
**Scale/Scope**: Multi-analyzer support; one thread per connection

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Requirement | Status |
|-----------|-------------|--------|
| I. Single Responsibility | Does this feature maintain protocol translation focus only? No business logic? | ✅ Pass - Only adds IP metadata to HTTP header, no content interpretation |
| II. Protocol Compliance | Does this feature comply with CLSI LIS1-A / E1381-95 standards? | ✅ Pass - No protocol changes, IP extracted from socket layer |
| III. Bi-Directional | Are both directions (ASTM→HTTP, HTTP→ASTM) considered? | ✅ Pass - P1 addresses ASTM→HTTP, P2 verifies HTTP→ASTM |
| IV. TDD | Are tests defined BEFORE implementation? Will tests fail first? | ✅ Pass - Test scenarios defined with ASTM Mock Server |
| V. Configuration | Are runtime settings configurable via YAML? No hardcoded values? | ✅ Pass - Configuration updates in P3 use existing patterns |
| VI. Observability | Is logging added for key events? Health checks maintained? | ✅ Pass - FR-004 requires logging on IP extraction failure |
| VII. Graceful Degradation | Are errors handled without crashes? Retry logic in place? | ✅ Pass - FR-004 requires graceful handling of failures |

**Note**: All principles pass. No justifications required in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/001-bi-directional-astm/
├── plan.md              # This file
├── research.md          # Phase 0: Code analysis and patterns
├── data-model.md        # Phase 1: Key classes affected
├── quickstart.md        # Phase 1: How to test the feature
├── contracts/           # Phase 1: API contract changes
│   └── http-headers.md  # X-Source-Analyzer-IP header contract
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
astm-http-bridge/
├── src/main/java/org/itech/ahb/
│   ├── AstmHttpBridgeApplication.java    # Main application, bean config
│   ├── controller/
│   │   ├── HTTPListenController.java     # HTTP → ASTM endpoint (P2 verify)
│   │   └── ASTMServerRunner*.java        # ASTM listener startup
│   └── config/properties/                # Configuration property classes
├── astm-http-lib/src/main/java/org/itech/ahb/lib/
│   ├── astm/
│   │   ├── servlet/ASTMServlet.java      # ASTM TCP listener
│   │   ├── handling/
│   │   │   ├── ASTMReceiveThread.java    # P1: Extract source IP from socket
│   │   │   ├── ASTMHandlerService.java   # P1: Pass source IP to handlers
│   │   │   └── ASTMHandler.java          # P1: Update interface
│   │   ├── communication/                # Protocol implementation
│   │   └── concept/                      # ASTM domain objects
│   └── http/handling/
│       └── DefaultForwardingASTMToHTTPHandler.java  # P1: Add X-Source-Analyzer-IP header
├── src/test/java/org/itech/ahb/          # Test sources
│   ├── astm/handling/                    # Unit tests for ASTM handling
│   │   └── SourceIPExtractionTest.java   # P1: IP extraction tests (IPv4 + IPv6)
│   ├── http/handling/                    # Unit tests for HTTP handling
│   │   └── HTTPForwardingHeaderTest.java # P1: Header addition tests
│   └── integration/                      # Integration tests
│       ├── MultiAnalyzerIPTest.java      # P1: Multi-analyzer header verification
│       ├── QueryFlowTest.java            # P2: Query response flow
│       ├── RetryLogicTest.java           # P2: Retry logic verification
│       ├── LineContentionTest.java       # P2: Line contention handling
│       └── ProtocolVersionTest.java      # P2: Protocol version selection
├── configuration.yml                     # Runtime configuration (P3)
├── README.md                             # Documentation (P3)
└── docs/                                 # Documentation
```

**Structure Decision**: Single Spring Boot application with internal protocol library 
(`astm-http-lib`). Main app handles HTTP endpoints and configuration; library handles 
ASTM protocol communication.

## Complexity Tracking

> **No violations to justify - all Constitution Check items pass.**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *None* | - | - |

## Phase 0: Research Summary

See [research.md](./research.md) for complete findings.

### Key Findings

1. **Source IP Extraction Point**: `ASTMReceiveThread` has access to `Socket` object 
   but doesn't extract remote IP
2. **Handler Chain**: IP must flow through `ASTMHandlerService` to reach HTTP handler
3. **HTTP Header Addition**: `DefaultForwardingASTMToHTTPHandler` uses Java HttpClient 
   - can add headers easily
4. **Existing Flow Verification**: `HTTPListenController` already supports `forwardAddress` 
   and `forwardPort` parameters

### Technical Decisions

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| Use `X-Source-Analyzer-IP` header name | Clear, descriptive, follows X- prefix convention | `X-Analyzer-IP` (less clear), `X-Forwarded-For` (confusing with proxy headers) |
| Extract IP in `ASTMReceiveThread` | Socket available here, single extraction point | Extract in servlet (duplicated), extract in handler (socket not available) |
| Pass IP through handler interface | Clean API change, type-safe | ThreadLocal (hidden state), Context object (over-engineering) |
| Log warning on extraction failure | Graceful degradation per constitution | Throw exception (loses messages), Silent failure (no observability) |

## Phase 1: Design Artifacts

### Data Model

See [data-model.md](./data-model.md) for complete class changes.

**Classes Modified**:
- `ASTMReceiveThread` - Add IP extraction method
- `ASTMHandlerService` - Add `sourceIp` parameter to `handle()` method
- `ASTMHandler` interface - Add `sourceIp` parameter to `handle()` method
- `DefaultForwardingASTMToHTTPHandler` - Add `X-Source-Analyzer-IP` header

**No New Classes Required** - Minimal change to existing structure.

### API Contracts

See [contracts/http-headers.md](./contracts/http-headers.md) for header specification.

**New HTTP Header**: `X-Source-Analyzer-IP`
- Direction: ASTM → HTTP (analyzer to OpenELIS)
- Value: IPv4 or IPv6 address string
- Required: No (omitted if extraction fails)
- Purpose: Allow OpenELIS to identify which analyzer sent the message

### Quickstart

See [quickstart.md](./quickstart.md) for testing guide.

**Test with ASTM Mock Server**:
```bash
# Start mock server in push mode
cd tools/astm-mock-server
python server.py --push http://localhost:12001 --analyzer-type HEMATOLOGY

# Verify X-Source-Analyzer-IP header in OpenELIS logs
docker logs openelis-global | grep "X-Source-Analyzer-IP"
```

## Implementation Phases

### Phase A: Source IP Extraction (P1 Requirements)

**Files**: `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/`

1. Update `ASTMReceiveThread` to extract source IP from socket
2. Update `ASTMHandlerService.handle()` signature to accept `sourceIp`
3. Update `ASTMHandler` interface to accept `sourceIp`
4. Update `DefaultForwardingASTMToHTTPHandler` to add header

**Tests**:
- Unit test: IP extraction from mock socket (IPv4 and IPv6)
- Unit test: HTTP header addition verification
- Integration test: Multi-analyzer header verification with mock server

### Phase B: Query Flow Verification (P2 Requirements)

**Files**: `src/main/java/org/itech/ahb/controller/HTTPListenController.java`

1. Verify `forwardAddress` and `forwardPort` parameters work correctly
2. Verify line contention handling works
3. Document existing capability

**Tests**:
- Integration test: Query response flow with mock server (TS-003)
- Integration test: Retry logic when analyzer unavailable (FR-010)
- Integration test: Line contention handling (TS-004)
- Integration test: Protocol version selection (TS-005)

### Phase C: Documentation (P3 Requirements)

**Files**: `README.md`, `configuration.yml`, `docs/`

1. Create enhanced README with architecture overview
2. Update configuration file with correct property structure
3. Document `X-Source-Analyzer-IP` header

**Tests**:
- Manual: Follow README to deploy and configure bridge

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Socket state prevents IP extraction | Low - graceful degradation handles it | Log warning, forward without header (FR-004) |
| Breaking change to handler interface | Medium - requires updates to all handlers | Internal API only, no external consumers |
| Performance impact of IP extraction | Low - single string operation per connection | Extraction happens once per connection, not per message |
| IPv6 address format issues | Low - Java handles both formats | Use `InetSocketAddress.getAddress().getHostAddress()` |

## Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Source IP accuracy | 100% | TS-002: 30 messages from 3 analyzers, all correct |
| Regression | 0 | All existing tests pass |
| Performance | <100ms added latency | Benchmark with/without feature |
| Documentation quality | Deployment in <30 min | Time new deployment using README only |
