# Research: Bi-Directional ASTM Workflow Support

> Superseded historical research. It does not define current implementation
> direction.

**Feature**: 001-bi-directional-astm  
**Date**: 2025-12-03  
**Status**: Superseded

## Research Tasks

### RT-001: Source IP Extraction Point Analysis

**Question**: Where in the codebase should source IP be extracted from the socket?

**Findings**:

The `ASTMReceiveThread` class is the ideal extraction point:

```java
// astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMReceiveThread.java
public class ASTMReceiveThread extends Thread {
  private final Socket socket;  // Socket available here
  
  public void run() {
    // Socket is available but IP not currently extracted
    // Can use: ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress().getHostAddress()
  }
}
```

**Decision**: Extract IP in `ASTMReceiveThread.run()` before calling handler service.

**Rationale**: 
- Socket object is available in constructor
- Single extraction point (not duplicated)
- Extracted once per connection, not per message

**Alternatives Considered**:
- `ASTMServlet.listen()` - Would require significant refactoring to pass IP through
- `GeneralASTMCommunicator` - Socket available but communicator shouldn't know about routing

---

### RT-002: Handler Chain API Update

**Question**: How should source IP flow through the handler chain?

**Findings**:

Current handler interface:
```java
// ASTMHandler interface
ASTMHandlerResponse handle(ASTMMessage message);

// ASTMHandlerService.handle()
public ASTMHandlerServiceResponse handle(ASTMMessage message) {
  // Calls handlers
}
```

**Decision**: Add `sourceIp` parameter to handler methods.

**Rationale**:
- Type-safe - compiler catches missing parameters
- Explicit - clear what data is available
- Simple - minimal code change

**Alternatives Considered**:
- `ThreadLocal<String>` - Hidden state, easy to forget to clear, testing complexity
- `ASTMContext` object - Over-engineering for single field, would need to update all call sites anyway
- Message wrapper - Changes message semantics, IP isn't part of ASTM message

**Implementation**:
```java
// Updated interface
ASTMHandlerResponse handle(ASTMMessage message, String sourceIp);

// Updated service
public ASTMHandlerServiceResponse handle(ASTMMessage message, String sourceIp) {
  for (ASTMHandler handler : handlers) {
    handler.handle(message, sourceIp);
  }
}
```

---

### RT-003: HTTP Header Implementation

**Question**: How to add custom header in Java HttpClient?

**Findings**:

Current implementation in `DefaultForwardingASTMToHTTPHandler`:
```java
Builder requestBuilder = HttpRequest.newBuilder()
  .uri(forwardingUri)
  .POST(HttpRequest.BodyPublishers.ofString(message.getMessage()));
```

**Decision**: Add `.header("X-Source-Analyzer-IP", sourceIp)` to request builder.

**Rationale**:
- Java HttpClient natively supports custom headers
- No additional dependencies needed
- Header name follows X- prefix convention for custom headers

**Implementation**:
```java
Builder requestBuilder = HttpRequest.newBuilder()
  .uri(forwardingUri)
  .POST(HttpRequest.BodyPublishers.ofString(message.getMessage()));

if (sourceIp != null && !sourceIp.isEmpty()) {
  requestBuilder.header("X-Source-Analyzer-IP", sourceIp);
}
```

---

### RT-004: IP Address Format Handling

**Question**: How to handle IPv4 vs IPv6 addresses?

**Findings**:

Java's `InetSocketAddress` handles both formats:
```java
// IPv4: "192.168.1.10"
// IPv6: "2001:db8::1" or "::1" (loopback)

String ip = ((InetSocketAddress) socket.getRemoteSocketAddress())
  .getAddress()
  .getHostAddress();
```

**Decision**: Use `getHostAddress()` which returns format-appropriate string.

**Rationale**:
- Java handles format automatically
- No parsing or validation needed
- Both formats valid in HTTP headers

**Edge Cases**:
- `null` socket address - Log warning, continue without header
- Closed socket - `getRemoteSocketAddress()` may return null

---

### RT-005: Graceful Degradation Pattern

**Question**: How should extraction failures be handled?

**Findings**:

Per Constitution Principle VII (Graceful Degradation):
- Bridge MUST NOT crash on extraction failure
- Bridge MUST NOT drop messages
- Bridge MUST log detailed error context

**Decision**: Log warning, continue without header.

**Rationale**:
- Message delivery more important than metadata
- OpenELIS can fall back to other identification methods (H-segment parsing)
- Logging enables troubleshooting

**Implementation**:
```java
private String extractSourceIp(Socket socket) {
  try {
    if (socket == null || socket.isClosed()) {
      log.warn("Cannot extract source IP: socket is null or closed");
      return null;
    }
    InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
    if (address == null) {
      log.warn("Cannot extract source IP: remote address is null");
      return null;
    }
    return address.getAddress().getHostAddress();
  } catch (Exception e) {
    log.warn("Error extracting source IP", e);
    return null;
  }
}
```

---

### RT-006: Existing Query Flow Verification

**Question**: Does HTTP→ASTM flow already work correctly?

**Findings**:

`HTTPListenController` already supports query parameters:
```java
@PostMapping
public HTTPHandlerServiceResponse recieveASTMMessageOverHttp(
  @RequestBody(required = false) String requestBody,
  @RequestParam(required = false) String forwardAddress,  // ✅ Target IP
  @RequestParam(required = false, defaultValue = "0") Integer forwardPort,  // ✅ Target port
  @RequestParam(required = false, defaultValue = "LIS01_A") ASTMVersion forwardAstmVersion,
  HttpServletResponse response
)
```

Line contention handling exists in `DefaultForwardingHTTPToASTMHandler`:
```java
private HTTPHandlerResponse handleLineContention(Communicator communicator, Socket socket, ASTMMessage message) {
  ASTMReceiveThread receiveThread = new ASTMReceiveThread(communicator, socket, astmHandlerService, true);
  receiveThread.run();
}
```

**Decision**: HTTP→ASTM flow is complete. P2 requirements are verification only.

**Rationale**:
- All parameters supported
- Line contention handled
- Protocol version selection supported

**Verification Tests Needed**:
- TS-003: Query response flow
- TS-004: Line contention handling
- TS-005: Protocol version selection

---

### RT-007: Test Strategy with ASTM Mock Server

**Question**: How to effectively test with the mock server?

**Findings**:

Mock server capabilities from [GitHub](https://github.com/DIGI-UW/astm-mock-server):

| Mode | Command | Use Case |
|------|---------|----------|
| Push | `python server.py --push http://bridge:12001` | Simulate analyzer sending results |
| Server | `python server.py --port 5000` | Receive queries from bridge |
| API | `python server.py --api-port 8080` | Automated test triggering |

**Decision**: Use mock server in all three modes for comprehensive testing.

**Test Plan**:
1. **TS-001/TS-002**: Push mode with multiple mock server instances
2. **TS-003/TS-004**: Server mode for query response testing
3. **CI Integration**: API mode for automated tests

**Docker Compose Setup**:
```yaml
services:
  mock-analyzer-1:
    image: astm-mock-server
    command: ["python", "server.py", "--port", "5001"]
    networks:
      astm-net:
        ipv4_address: 172.20.0.10
  
  mock-analyzer-2:
    image: astm-mock-server
    command: ["python", "server.py", "--port", "5002"]
    networks:
      astm-net:
        ipv4_address: 172.20.0.11
```

---

## Summary of Decisions

| Decision | Rationale |
|----------|-----------|
| Extract IP in `ASTMReceiveThread` | Socket available, single extraction point |
| Add `sourceIp` parameter to handler interface | Type-safe, explicit API |
| Use `X-Source-Analyzer-IP` header | Clear name, standard convention |
| Use `getHostAddress()` for IP format | Handles IPv4/IPv6 automatically |
| Log warning on failure, continue without header | Graceful degradation per constitution |
| HTTP→ASTM is verification only | Already implemented correctly |
| Test with ASTM Mock Server in all modes | Comprehensive coverage |

## Open Questions

None - all research questions resolved.

## References

- [ASTM Mock Server](https://github.com/DIGI-UW/astm-mock-server)
- [Java InetSocketAddress](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/InetSocketAddress.html)
- [Java HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
- [Constitution](../../.specify/memory/constitution.md) - Principles I, VI, VII
