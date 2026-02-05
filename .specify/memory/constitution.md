<!--
Sync Impact Report (v1.0.0)
- Version change: N/A → 1.0.0 (initial constitution)
- New constitution created from project analysis
- Templates requiring updates:
  - .specify/templates/plan-template.md ✅ updated - Constitution Check table + Technical Context
  - .specify/templates/spec-template.md ✅ no updates needed (generic template)
  - .specify/templates/tasks-template.md ✅ no updates needed (generic template)
  - .specify/templates/checklist-template.md ✅ no updates needed (generic template)
  - .specify/templates/agent-file-template.md ✅ no updates needed (generic template)
- Follow-up TODOs: None
-->

# OpenELIS Analyzer Bridge Constitution

## Purpose

The OpenELIS Analyzer Bridge is a **simple, reliable protocol/transport bridge** that enables
bi-directional communication between medical laboratory analyzers and **OpenELIS**.

The bridge MUST remain focused on **transport + protocol mediation** (and minimal metadata
propagation) and MUST NOT implement OpenELIS business logic (mapping, persistence, workflow).

## Core Principles

### I. Single Responsibility

The bridge has ONE job: translate analyzer protocol/transports to **OpenELIS HTTP** endpoints
(and support outbound forwarding from OpenELIS to analyzers where required).

- Bridge MUST translate inbound analyzer messages to HTTP POST requests and vice versa (when applicable)
- Bridge MUST NOT interpret message content beyond protocol requirements (framing, checksums, acknowledgments)
- Bridge MUST NOT implement analyzer identification, mapping, or data transformation logic (OpenELIS responsibility)
- Bridge MUST NOT persist domain data (stateless translation only)
- Bridge MAY attach **minimal source metadata** derived from the transport (e.g., source IP / port / transport type)

**Rationale**: Keeping the bridge simple ensures reliability, maintainability, and clear 
separation of concerns. OpenELIS owns all business logic.

### II. Protocol Compliance

The bridge MUST comply with the relevant standards for each supported protocol/transport.

- **ASTM**:
  - Bridge MUST support CLSI LIS1-A (LIS01-A)
  - Bridge MUST support E1381-95 for legacy analyzers
  - Bridge MUST handle framing, checksums, ENQ/ACK/NAK handshakes, and line contention (CLSI LIS1-A §8.3.5)
- **HL7 v2.x (MLLP framing)** (when enabled):
  - Bridge MUST respect MLLP framing delimiters (VT start, FS+CR end)
  - Bridge MUST generate appropriate ACK/NAK responses where required by the transport/server role
- Bridge MUST provide configurable timeouts appropriate to the transport (e.g., establishment/receive)

**Rationale**: Standards compliance ensures interoperability with diverse medical analyzers.

### III. Bi-Directional Communication

The bridge MUST support communication in both directions (where the protocol/transport requires it).

- **Analyzer → OpenELIS**: Bridge receives messages (e.g., ASTM/TCP) and forwards via HTTP POST
- **OpenELIS → Analyzer**: Bridge receives HTTP POST and forwards to analyzer (e.g., ASTM/TCP host queries)
- Bridge MUST include source analyzer identity hints where safely derivable (e.g., `X-Source-Analyzer-IP`)
- Bridge MUST accept target routing overrides (e.g., `forwardAddress`, `forwardPort`) when OpenELIS sends outbound messages
- Bridge MUST handle concurrent connections (one thread per connection)

**Rationale**: Bi-directional flow enables both result submission and query operations.

### IV. Test-Driven Development (TDD)

All new features MUST be developed test-first.

- Tests MUST be written before implementation code
- Tests MUST fail before implementation (Red phase)
- Implementation MUST make tests pass with minimal code (Green phase)
- Code MUST be refactored while maintaining passing tests (Refactor phase)
- Integration tests MUST verify protocol compliance with mock analyzers
- Unit tests MUST verify message parsing and frame handling

**Rationale**: TDD ensures reliability for critical healthcare infrastructure and provides 
living documentation of expected behavior.

### V. Configuration Over Code

Runtime behavior MUST be configurable without code changes.

- Connection settings MUST be configurable via `configuration.yml`
- Forward HTTP server URI MUST be configurable (`org.itech.ahb.forward-http-server.uri`)
- Listen ports MUST be configurable (e.g., LIS1-A: 12001, E1381-95: 12011 by default)
- Optional transports (e.g., MLLP/Serial/File) MUST be enable/disable configurable
- Timeout values MUST be configurable
- Authentication credentials MUST be configurable and MUST NOT be hardcoded
- Sensitive values SHOULD use environment variable substitution (`${VAR_NAME}`)

**Rationale**: Configuration enables deployment flexibility across different environments.

### VI. Observability

The bridge MUST provide visibility into its operation.

- Bridge MUST log all connection events (connect, disconnect, timeout, error)
- Bridge MUST log message handling status (success, fail, unhandled)
- Bridge MUST provide health check endpoint (`/actuator/health`)
- Log levels MUST be configurable (DEBUG for development, INFO/WARN for production)
- Bridge SHOULD expose metrics for connection counts and message throughput (by transport/protocol where possible)

**Rationale**: Healthcare systems require auditability and operational visibility.

### VII. Graceful Degradation

The bridge MUST handle failures gracefully without losing messages.

- Bridge MUST retry failed forwards (configurable retry count and delay)
- Bridge MUST handle connection timeouts without crashing
- Bridge MUST log detailed error context for troubleshooting
- Bridge MUST close sockets cleanly after errors
- Bridge SHOULD fall back to non-compliant mode if an ASTM analyzer doesn't respond with control characters (where appropriate)

**Rationale**: Laboratory operations depend on reliable message delivery.

## Technical Context

| Aspect | Value |
|--------|-------|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| Build Tool | Maven |
| Deployment | Docker |
| Configuration | YAML (`configuration.yml`) |
| Testing | JUnit 5, Spring Boot Test |
| Protocol Library | `astm-http-lib` (internal module) |

## Project Structure

```text
astm-http-bridge/
├── src/main/java/org/itech/ahb/
│   ├── AstmHttpBridgeApplication.java    # Main application, bean config
│   ├── controller/
│   │   ├── HTTPListenController.java     # HTTP → ASTM endpoint
│   │   └── ASTMServerRunner*.java        # ASTM listener startup
│   └── config/properties/                # Configuration property classes
├── astm-http-lib/src/main/java/org/itech/ahb/lib/
│   ├── astm/
│   │   ├── servlet/ASTMServlet.java      # ASTM TCP listener
│   │   ├── handling/                     # Message handlers
│   │   ├── communication/                # Protocol implementation
│   │   └── concept/                      # ASTM domain objects
│   └── http/handling/                    # HTTP handlers
├── src/test/                             # Test sources
├── configuration.yml                     # Runtime configuration
├── docker-compose*.yml                   # Deployment configurations
└── docs/                                 # Documentation
```

## Governance

### Amendment Process

1. Proposed changes MUST be documented with rationale
2. Changes MUST be reviewed for impact on existing deployments
3. Breaking changes MUST increment major version and provide migration guidance
4. Documentation MUST be updated to reflect changes

### Versioning Policy

- **MAJOR**: Breaking changes to protocol support or API contracts
- **MINOR**: New features, new protocol support, new configuration options
- **PATCH**: Bug fixes, documentation, minor improvements

### Compliance Review

- All pull requests MUST verify compliance with these principles
- Test coverage MUST be maintained or improved
- Protocol compliance MUST be verified with integration tests
- Configuration changes MUST be documented

### Reference Documents

- [CLSI LIS1-A Standard](docs/CLSI-LIS1-A.pdf) - Primary protocol specification
- [E1381-95 Standard](docs/E1381-95.pdf) - Legacy protocol support
- [ASTM LIS2-A2](docs/LIS02A2E.pdf) - Message format specification
- [Compatibility Analysis](docs/ASTM_BRIDGE_COMPATIBILITY_ANALYSIS.md) - Protocol coverage
- [Message Flow](docs/ASTM_MESSAGE_PROCESSING_FLOW.md) - Integration architecture

**Version**: 1.1.0 | **Ratified**: 2025-12-03 | **Last Amended**: 2026-02-05
