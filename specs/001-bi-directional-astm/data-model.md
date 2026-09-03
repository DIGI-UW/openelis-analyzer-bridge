# Data Model: Bi-Directional ASTM Workflow Support

> Superseded historical model. Current runtime and contract structures are
> defined by current code and `contracts/analyzer/v1`.

**Feature**: 001-bi-directional-astm  
**Date**: 2025-12-03  
**Status**: Superseded

## Overview

This feature requires no new classes. Changes are limited to adding source IP extraction 
and passing it through the existing handler chain.

## Classes Modified

### 1. ASTMReceiveThread

**Location**: `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMReceiveThread.java`

**Current State**:
```java
public class ASTMReceiveThread extends Thread {
  private final Socket socket;
  private final Communicator communicator;
  private ASTMHandlerService astmHandlerService;
  
  @Override
  public void run() {
    // ... message receive logic ...
    ASTMHandlerServiceResponse response = astmHandlerService.handle(message);
  }
}
```

**Required Changes**:
- Add `extractSourceIp(Socket socket)` private method
- Call extraction before `handle()`
- Pass source IP to handler service

**Modified State**:
```java
public class ASTMReceiveThread extends Thread {
  private final Socket socket;
  private final Communicator communicator;
  private ASTMHandlerService astmHandlerService;
  
  @Override
  public void run() {
    String sourceIp = extractSourceIp(socket);  // NEW
    // ... message receive logic ...
    ASTMHandlerServiceResponse response = astmHandlerService.handle(message, sourceIp);  // CHANGED
  }
  
  // NEW METHOD
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
      String ip = address.getAddress().getHostAddress();
      log.debug("Extracted source IP: {}", ip);
      return ip;
    } catch (Exception e) {
      log.warn("Error extracting source IP", e);
      return null;
    }
  }
}
```

---

### 2. ASTMHandlerService

**Location**: `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMHandlerService.java`

**Current State**:
```java
public class ASTMHandlerService {
  public ASTMHandlerServiceResponse handle(ASTMMessage message) {
    for (ASTMHandler handler : handlers) {
      ASTMHandlerResponse response = handler.handle(message);
      // ...
    }
  }
}
```

**Required Changes**:
- Add `sourceIp` parameter to `handle()` method
- Pass source IP to each handler

**Modified State**:
```java
public class ASTMHandlerService {
  public ASTMHandlerServiceResponse handle(ASTMMessage message, String sourceIp) {  // CHANGED
    for (ASTMHandler handler : handlers) {
      ASTMHandlerResponse response = handler.handle(message, sourceIp);  // CHANGED
      // ...
    }
  }
  
  // Backward compatibility overload (optional)
  public ASTMHandlerServiceResponse handle(ASTMMessage message) {
    return handle(message, null);
  }
}
```

---

### 3. ASTMHandler Interface

**Location**: `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/ASTMHandler.java`

**Current State**:
```java
public interface ASTMHandler {
  ASTMHandlerResponse handle(ASTMMessage message);
  String getName();
}
```

**Required Changes**:
- Add `sourceIp` parameter to `handle()` method

**Modified State**:
```java
public interface ASTMHandler {
  ASTMHandlerResponse handle(ASTMMessage message, String sourceIp);  // CHANGED
  String getName();
  
  // Default method for backward compatibility (optional)
  default ASTMHandlerResponse handle(ASTMMessage message) {
    return handle(message, null);
  }
}
```

---

### 4. DefaultForwardingASTMToHTTPHandler

**Location**: `astm-http-lib/src/main/java/org/itech/ahb/lib/astm/handling/DefaultForwardingASTMToHTTPHandler.java`

**Current State**:
```java
public class DefaultForwardingASTMToHTTPHandler implements ASTMHandler {
  @Override
  public ASTMHandlerResponse handle(ASTMMessage message) {
    Builder requestBuilder = HttpRequest.newBuilder()
      .uri(forwardingUri)
      .POST(HttpRequest.BodyPublishers.ofString(message.getMessage()));
    // ... send request ...
  }
}
```

**Required Changes**:
- Update method signature to accept `sourceIp`
- Add `X-Source-Analyzer-IP` header when source IP is available

**Modified State**:
```java
public class DefaultForwardingASTMToHTTPHandler implements ASTMHandler {
  private static final String SOURCE_IP_HEADER = "X-Source-Analyzer-IP";
  
  @Override
  public ASTMHandlerResponse handle(ASTMMessage message, String sourceIp) {  // CHANGED
    Builder requestBuilder = HttpRequest.newBuilder()
      .uri(forwardingUri)
      .POST(HttpRequest.BodyPublishers.ofString(message.getMessage()));
    
    // NEW: Add source IP header if available
    if (sourceIp != null && !sourceIp.isEmpty()) {
      requestBuilder.header(SOURCE_IP_HEADER, sourceIp);
      log.debug("Added {} header: {}", SOURCE_IP_HEADER, sourceIp);
    }
    
    // ... send request ...
  }
}
```

---

## Impact Analysis

### Files Requiring Updates

| File | Change Type | Risk |
|------|-------------|------|
| `ASTMReceiveThread.java` | Add method + modify call | Low |
| `ASTMHandlerService.java` | Update signature | Low |
| `ASTMHandler.java` | Update interface | Medium (affects all implementations) |
| `DefaultForwardingASTMToHTTPHandler.java` | Add header logic | Low |

### Handler Implementations to Update

All `ASTMHandler` implementations must be updated with new signature:

1. `DefaultForwardingASTMToHTTPHandler` - Primary target, adds header
2. Any other custom handlers - Update signature, can ignore `sourceIp` if not needed

### Test Files Requiring Updates

1. Existing tests calling `handle()` - Update to pass `null` for source IP or use overload
2. New tests for source IP extraction - `SourceIPExtractionTest.java`
3. New integration tests - `MultiAnalyzerTest.java`

---

## Data Flow Diagram

```
┌─────────────────┐    ┌──────────────────┐    ┌────────────────────┐
│   TCP Socket    │───→│  ASTMReceiveThread │───→│  ASTMHandlerService │
│ (has remote IP) │    │  extractSourceIp() │    │  handle(msg, ip)    │
└─────────────────┘    └──────────────────┘    └─────────┬──────────┘
                                                          │
                                                          ▼
                       ┌────────────────────────────────────────────────┐
                       │     DefaultForwardingASTMToHTTPHandler        │
                       │     handle(message, sourceIp)                  │
                       │     → requestBuilder.header("X-Source-...", ip)│
                       └──────────────────────┬─────────────────────────┘
                                              │
                                              ▼
                       ┌────────────────────────────────────────────────┐
                       │              HTTP POST to OpenELIS            │
                       │  Headers: X-Source-Analyzer-IP: 192.168.1.10  │
                       └────────────────────────────────────────────────┘
```

---

## No New Entities

This feature adds metadata (source IP) to existing message flow. No new domain entities 
are required.

**Existing Entities (Unchanged)**:
- `ASTMMessage` - Message content (not modified)
- `ASTMFrame` - Protocol frames (not modified)
- `ASTMRecord` - Message records (not modified)

**New Metadata (Passed as Parameter)**:
- `sourceIp` (String) - Extracted from socket, passed through handler chain

---

## Backward Compatibility

**Option A: Default Method (Recommended)**
- Add default method to interface with `null` sourceIp
- Existing code continues to work without changes

**Option B: Overloaded Methods**
- Keep old signature, add new signature
- More code but clearer intent

**Decision**: Use Option A (default method) for minimal disruption.
