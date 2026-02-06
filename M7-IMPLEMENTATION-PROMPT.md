# M7: Message Normalizer — Implementation Prompt

**Branch**: `feat/universal-bridge-normalizer` (this branch)
**Repo**: `DIGI-UW/astm-http-bridge`
**Working directory**: `tools/astm-http-bridge/`

---

## Goal

Unify all 5 transport listeners to route through a single `MessageNormalizer` → `HttpForwardingRouter` pipeline. Currently only MLLP uses the router; Serial, File, ASTM, and HTTP Input each have their own forwarding logic (or none at all).

### Current State (BROKEN — 4 of 5 paths bypass the router)

```
MLLP:    HapiReceivingApplication → MessageRouter → HttpForwardingRouter  ✅ CORRECT (but skips normalizer)
Serial:  SerialMessageHandler → own forwardMessage() with HttpClient      ❌ BYPASS
File:    FileMessageHandler → own forwardToOpenELIS() with RestTemplate   ❌ BYPASS
ASTM:    ASTMReceiveThread → ASTMHandlerService → legacy handler chain    ❌ LEGACY
HTTP:    AnalyzerInputController → creates envelope → DOES NOTHING        ❌ BROKEN (TODO)
```

### Target State (ALL paths through MessageNormalizer)

```
All listeners → MessageEnvelope → MessageNormalizer → HttpForwardingRouter → OpenELIS
                                       |
                                       ├── AnalyzerIdentifier (multi-strategy)
                                       ├── Retry/backoff (from config)
                                       └── Audit logging
```

### Key Design Decision: MessageNormalizer implements MessageRouter

`MessageNormalizer` MUST implement `MessageRouter` and be annotated `@Primary`.
This way, `HapiReceivingApplication` (which already depends on `MessageRouter`)
automatically gets the normalizer via Spring injection — **zero changes to MLLP code**.

```
HapiReceivingApplication → @Autowired MessageRouter → MessageNormalizer (@Primary)
                                                            ↓
Serial/File/HTTP/ASTM → normalizer.process(envelope) → same MessageNormalizer
                                                            ↓
                                                    HttpForwardingRouter (injected by concrete type)
```

`MessageNormalizer` injects `HttpForwardingRouter` by **concrete type** (not `MessageRouter` interface) to avoid circular injection ambiguity.

---

## Existing Code You MUST Reuse

These files exist and are correct. Do NOT rewrite them — build on top of them:

### `routing/MessageRouter.java` — Interface (DO NOT MODIFY)

```java
package org.itech.ahb.routing;
import org.itech.ahb.normalizer.MessageEnvelope;

public interface MessageRouter {
    boolean route(MessageEnvelope envelope);
}
```

### `routing/HttpForwardingRouter.java` — Routes by protocol to OpenELIS

Already routes `MessageEnvelope` to protocol-specific endpoints:
- `Protocol.HL7` → `/analyzer/hl7`
- `Protocol.ASTM` → `/analyzer/astm`
- `Protocol.CSV` → `/analyzer/csv`

Adds these headers: `X-Analyzer-Id`, `X-Source-Protocol`, `X-Source-Transport`, `X-Source-Id`, `X-Source-Analyzer-IP`

Uses `HTTPForwardServerConfigurationProperties` for URI + credentials.
Has secure password handling (CharsetEncoder, no String pool exposure).
**MODIFY THIS FILE** only to add retry/backoff logic (T04).

### `normalizer/MessageEnvelope.java` — Input DTO (DO NOT MODIFY)

```java
@Getter @Builder @ToString
public class MessageEnvelope {
    private final Protocol protocol;
    private final Transport transport;
    private final String sourceId;
    private final String rawMessage;
    @Builder.Default private final Instant receivedAt = Instant.now();
    private final String analyzerId;
}
```

### `normalizer/NormalizedMessage.java` — Output DTO (exists, may be used for audit)

Has fields: `analyzerId`, `protocol`, `transport`, `message`, `sourceId`, `timestamp`, `messageId`, `error`

### `model/Protocol.java` — Enum: `ASTM, HL7, CSV, UNKNOWN`

### `model/Transport.java` — Enum: `TCP, MLLP, SERIAL, FILE, HTTP`

### `util/ProtocolDetector.java` — Static utility: `Protocol detect(String message)`

### `mllp/HapiReceivingApplication.java` — THE CORRECT PATTERN

This is the **reference implementation** showing how a listener should use the router:
1. Extract metadata (source IP, analyzer ID from MSH-3/4)
2. Build `MessageEnvelope`
3. Call `router.route(envelope)`
4. Return success/failure

**All other listeners should follow this same pattern** after M7.

---

## Tasks (implement in order)

### T01: Create `MessageNormalizer` service

**File**: `src/main/java/org/itech/ahb/normalizer/MessageNormalizer.java`

Central orchestration service. All listeners delegate to this instead of doing their own forwarding.

```java
package org.itech.ahb.normalizer;

import org.itech.ahb.routing.HttpForwardingRouter;
import org.itech.ahb.routing.MessageRouter;

@Component
@Primary  // CRITICAL: Makes this the default MessageRouter bean.
          // HapiReceivingApplication depends on MessageRouter — @Primary ensures
          // it gets MessageNormalizer without any code changes to MLLP.
@Slf4j
public class MessageNormalizer implements MessageRouter {

    private final HttpForwardingRouter forwardingRouter;  // Inject by CONCRETE TYPE, not MessageRouter
    private final AnalyzerIdentifier identifier;

    // Constructor injection: (HttpForwardingRouter forwardingRouter, AnalyzerIdentifier identifier)

    /**
     * MessageRouter.route() implementation — allows MLLP to use this transparently.
     * Delegates to process().
     */
    @Override
    public boolean route(MessageEnvelope envelope) {
        return process(envelope);
    }

    /**
     * Process a message envelope: identify analyzer, route to OpenELIS.
     * Called directly by Serial/File/HTTP/ASTM handlers, or via route() by MLLP.
     * @return true if routing succeeded
     */
    public boolean process(MessageEnvelope envelope) {
        // 1. If analyzerId not set, try to identify via AnalyzerIdentifier
        String analyzerId = envelope.getAnalyzerId();
        if (analyzerId == null || analyzerId.isEmpty()) {
            analyzerId = identifier.identify(envelope);
        }

        // 2. Rebuild envelope with analyzerId if we found one
        MessageEnvelope enriched = (analyzerId != null && !analyzerId.equals(envelope.getAnalyzerId()))
            ? MessageEnvelope.builder()
                .protocol(envelope.getProtocol())
                .transport(envelope.getTransport())
                .sourceId(envelope.getSourceId())
                .rawMessage(envelope.getRawMessage())
                .receivedAt(envelope.getReceivedAt())
                .analyzerId(analyzerId)
                .build()
            : envelope;

        // 3. Audit log
        log.info("Normalizer processing: protocol={}, transport={}, source={}, analyzer={}",
            enriched.getProtocol(), enriched.getTransport(),
            enriched.getSourceId(), enriched.getAnalyzerId());

        // 4. Route via HttpForwardingRouter (NOT via this.route() — that would recurse!)
        boolean success = forwardingRouter.route(enriched);

        if (!success) {
            log.error("Failed to route message: protocol={}, source={}",
                enriched.getProtocol(), enriched.getSourceId());
        }

        return success;
    }
}
```

### T02: Create `AnalyzerIdentifier`

**File**: `src/main/java/org/itech/ahb/normalizer/AnalyzerIdentifier.java`

Multi-strategy identification. Strategies in priority order:
1. Envelope already has analyzerId (set by listener) → use it
2. IP-based lookup from `AnalyzerRegistryConfig`
3. Serial-port-based lookup from config
4. File-path-pattern lookup from config
5. Return null (OpenELIS will identify from message content)

```java
package org.itech.ahb.normalizer;

@Component
@Slf4j
public class AnalyzerIdentifier {

    private final AnalyzerRegistryConfig registry;  // may be null if not configured

    // Constructor: @Autowired(required = false) AnalyzerRegistryConfig

    public String identify(MessageEnvelope envelope) {
        // Strategy 1: Already identified
        if (envelope.getAnalyzerId() != null && !envelope.getAnalyzerId().isEmpty()) {
            return envelope.getAnalyzerId();
        }

        if (registry == null) {
            return null;
        }

        String sourceId = envelope.getSourceId();

        // Strategy 2: IP-based lookup
        // Strategy 3: Serial port lookup
        // Strategy 4: File path pattern
        // Check registry.getAnalyzers() map for sourceId key match

        return registry.findAnalyzerId(sourceId).orElse(null);
    }
}
```

### T03: Create `AnalyzerRegistryConfig`

**File**: `src/main/java/org/itech/ahb/config/AnalyzerRegistryConfig.java`

```java
package org.itech.ahb.config;

@ConfigurationProperties(prefix = "bridge")
@Slf4j
public class AnalyzerRegistryConfig {

    private Map<String, AnalyzerEntry> analyzers = new LinkedHashMap<>();

    // Getters/setters for analyzers map

    public Optional<String> findAnalyzerId(String sourceId) {
        if (sourceId == null || analyzers.isEmpty()) return Optional.empty();

        // Direct match (IP address, serial port path)
        AnalyzerEntry entry = analyzers.get(sourceId);
        if (entry != null) return Optional.of(entry.getId());

        // Pattern match (file paths with wildcards)
        for (Map.Entry<String, AnalyzerEntry> e : analyzers.entrySet()) {
            String pattern = e.getKey();
            if (pattern.contains("*") && matchesGlob(sourceId, pattern)) {
                return Optional.of(e.getValue().getId());
            }
        }

        return Optional.empty();
    }

    @Data
    public static class AnalyzerEntry {
        private String id;
        private String name;
        private String expectedProtocol;
        private String filePattern;
    }
}
```

**IMPORTANT**: This class MUST NOT use `@ConditionalOnProperty`. It should always be loaded (even if the `bridge.analyzers` section is empty/missing in config). Use `@ConfigurationProperties` only.

### T04: Add retry/backoff to `HttpForwardingRouter`

**File**: `src/main/java/org/itech/ahb/routing/HttpForwardingRouter.java` (MODIFY)

Also **File**: `src/main/java/org/itech/ahb/config/OpenELISConfig.java` (MODIFY)

**What to do**:
1. Remove `@ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true")` from `OpenELISConfig` so retry config is always available (even when file watcher is disabled)
2. Add `OpenELISConfig` as an **optional** dependency to `HttpForwardingRouter`
3. Wrap the HTTP send in a retry loop with exponential backoff

**IMPORTANT**: `OpenELISConfig` currently has `@ConditionalOnProperty(prefix = "bridge.file", ...)`.
After removing that conditional, the class becomes available whenever `bridge.openelis.*` properties exist
in `configuration.yml` (they already do). Use `@Autowired(required = false)` in `HttpForwardingRouter`
so the router still works if the config is somehow missing.

The config properties already exist in `configuration.yml`:
```yaml
bridge:
  openelis:
    retry:
      maxAttempts: 3
      backoffMs: 1000
```

The retry config class already exists in `OpenELISConfig.RetryConfig` (nested static class with
`maxAttempts` and `backoffMs` fields).

Implementation approach — keep it simple, no Spring Retry dependency needed:
```java
// In HttpForwardingRouter, add constructor parameter:
public HttpForwardingRouter(
        HTTPForwardServerConfigurationProperties httpConfig,
        @Autowired(required = false) OpenELISConfig openelisConfig) {
    this.httpConfig = httpConfig;
    this.retryConfig = openelisConfig != null ? openelisConfig.getRetry() : null;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();
}

// Modify route() method — wrap the existing try/catch in a retry loop:
@Override
public boolean route(MessageEnvelope envelope) {
    int maxAttempts = retryConfig != null ? retryConfig.getMaxAttempts() : 1;
    long backoffMs = retryConfig != null ? retryConfig.getBackoffMs() : 1000;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            URI targetUri = buildTargetUri(envelope);
            HttpRequest request = buildRequest(envelope, targetUri);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) return true;
            // Non-retryable HTTP errors (4xx) — fail immediately
            if (statusCode >= 400 && statusCode < 500) {
                log.error("Non-retryable HTTP error {}: {}", statusCode, response.body());
                return false;
            }
            // 5xx errors — retry
            log.warn("Server error {} on attempt {}/{}", statusCode, attempt, maxAttempts);
        } catch (IOException e) {
            log.warn("IO error on attempt {}/{}: {}", attempt, maxAttempts, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (attempt < maxAttempts) {
            try {
                long waitMs = backoffMs * (1L << (attempt - 1)); // exponential
                log.info("Retrying in {}ms...", waitMs);
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
    log.error("All {} attempts failed for {} message", maxAttempts, envelope.getProtocol());
    return false;
}
```

### T05: Refactor `SerialMessageHandler` — delegate to `MessageNormalizer`

**File**: `src/main/java/org/itech/ahb/serial/SerialMessageHandler.java` (MODIFY)
**Also update**: `src/test/java/org/itech/ahb/serial/SerialMessageHandlerTest.java` (MODIFY)

**What to remove**: The entire `forwardMessage()` method, `determineTargetUri()`, `determineContentType()`, `HttpClient` field, `HTTPForwardServerConfigurationProperties` field, `SOURCE_ID_HEADER`, `TRANSPORT_HEADER`, `ANALYZER_ID_HEADER` constants, and the `HTTP_TIMEOUT` constant.

**What to keep**: `handleMessage()` method signature (`String message, String serialPortPath, String analyzerId`), protocol detection, envelope creation, `HandleResult` record.

**What to change**: Inject `MessageNormalizer` instead. Call `normalizer.process(envelope)`.

```java
@Service
@Slf4j
public class SerialMessageHandler {

    private final MessageNormalizer normalizer;  // NEW (replaces httpConfig + httpClient)

    public SerialMessageHandler(MessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public HandleResult handleMessage(String message, String serialPortPath, String analyzerId) {
        if (message == null || message.isEmpty()) {
            log.warn("Received empty message from serial port {}", serialPortPath);
            return new HandleResult(false, "Empty message");
        }

        Protocol protocol = ProtocolDetector.detect(message);
        log.info("Received {} message from serial port {} ({} bytes)",
            protocol, serialPortPath, message.length());

        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(protocol)
            .transport(Transport.SERIAL)
            .sourceId(serialPortPath)
            .rawMessage(message)
            .analyzerId(analyzerId)
            .build();

        boolean success = normalizer.process(envelope);  // CHANGED: delegate to normalizer
        return new HandleResult(success, success ? "Routed via normalizer" : "Routing failed");
    }

    public record HandleResult(boolean success, String message) {}
}
```

**CRITICAL — Update `SerialMessageHandlerTest`**: The existing test creates the handler with
`new SerialMessageHandler(httpConfig)` and uses an embedded HTTP server. After this refactor,
the constructor takes `MessageNormalizer`. Rewrite the test:
- Mock `MessageNormalizer` using Mockito
- Verify `normalizer.process()` is called with correct `MessageEnvelope` fields
- Remove all embedded HTTP server setup (`com.sun.net.httpserver.HttpServer`)
- Keep all protocol detection tests (they still apply)
- Keep empty/null message tests
- Remove HTTP error response and connection refused tests (those are now HttpForwardingRouter's concern)
- Remove authentication tests (those are now HttpForwardingRouter's concern)

### T06: Refactor `FileMessageHandler` — delegate to `MessageNormalizer`

**File**: `src/main/java/org/itech/ahb/file/FileMessageHandler.java` (MODIFY)

**What to remove**: The entire `forwardToOpenELIS()` method and `RestTemplate`/`OpenELISConfig` dependencies.

**What to keep**: `processFile()` method signature, file reading, protocol detection, validation.

**What to change**: Inject `MessageNormalizer`. After creating the envelope, call `normalizer.process(envelope)` instead of `forwardToOpenELIS()`.

```java
@Component
@ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true")
public class FileMessageHandler {

    private final CSVParser csvParser;
    private final MessageNormalizer normalizer;  // NEW (replaces restTemplate + openelisConfig)
    private final FileConfig fileConfig;

    public MessageEnvelope processFile(Path filePath, String analyzerId) throws IOException, FileProcessingException {
        // ... existing file reading, protocol detection, validation ...
        // ... existing MessageEnvelope creation ...

        // CHANGED: delegate to normalizer instead of forwardToOpenELIS()
        boolean success = normalizer.process(envelope);
        if (!success) {
            throw new FileProcessingException("Failed to route message for file: " + filePath);
        }

        return envelope;
    }

    // DELETE: forwardToOpenELIS(), getEndpointForProtocol()
    // DELETE: RestTemplate field, OpenELISConfig field
    // KEEP: validateFileContent(), FileProcessingException
}
```

**Also**: Remove `@Qualifier("fileWatcherRestTemplate") RestTemplate` parameter from constructor.
The `fileWatcherRestTemplate` bean is defined in `src/main/java/org/itech/ahb/config/HttpClientConfig.java` (line 31-38).
After this refactor, that bean has no consumers. **Remove the `fileWatcherRestTemplate` method** from `HttpClientConfig.java`.
Keep the `bridgeRestTemplate` method (it may be used elsewhere or in the future).
Also remove the `OpenELISConfig` import from `FileMessageHandler` since it's no longer used there.

### T07: Wire `AnalyzerInputController` — call `MessageNormalizer`

**File**: `src/main/java/org/itech/ahb/controller/AnalyzerInputController.java` (MODIFY)
**Also update**: `src/test/java/org/itech/ahb/controller/AnalyzerInputControllerTest.java` (MODIFY)

**What to change**:
1. Add `MessageNormalizer` as a constructor dependency
2. Replace the TODO comment with actual routing

Add constructor:
```java
private final MessageNormalizer normalizer;

public AnalyzerInputController(MessageNormalizer normalizer) {
    this.normalizer = normalizer;
}
```

Find this block (around line 109):
```java
// TODO: Forward to MessageNormalizer for routing once M7 integration is available
// For now, return success with envelope details
```

Replace with:
```java
boolean success = normalizer.process(envelope);

if (!success) {
    log.error("Failed to route {} message from {}", protocol, sourceIp);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new InputResponse(false, "Message routing failed", sourceIp, protocol.name(), null));
}
```

**CRITICAL — Update `AnalyzerInputControllerTest`**: The existing test creates the controller
with `controller = new AnalyzerInputController()` (no-arg). After adding the `MessageNormalizer`
dependency, this will fail to compile. Update the test:
- Add `@Mock private MessageNormalizer mockNormalizer;`
- Change setUp to: `controller = new AnalyzerInputController(mockNormalizer);`
- Add `when(mockNormalizer.process(any())).thenReturn(true);` in setUp (default success)
- Keep ALL existing test cases (protocol detection, source IP extraction, error handling) — they still apply
- Add new test: verify `normalizer.process()` is called for successful requests
- Add new test: verify 500 response when `normalizer.process()` returns false

### T08: Create `ASTMBridgeAdapter`

**File**: `src/main/java/org/itech/ahb/normalizer/ASTMBridgeAdapter.java` (NEW)

Implements `ASTMHandler` (the library interface at `org.itech.ahb.lib.astm.handling.ASTMHandler`).
Replaces `DefaultForwardingASTMToHTTPHandler` in the bean factory.

```java
package org.itech.ahb.normalizer;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.lib.astm.handling.ASTMHandler;
import org.itech.ahb.lib.astm.handling.ASTMHandlerResponse;
import org.itech.ahb.lib.common.handling.HandleStatus;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;

@Slf4j
public class ASTMBridgeAdapter implements ASTMHandler {

    private final MessageNormalizer normalizer;

    public ASTMBridgeAdapter(MessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public String getName() {
        return "ASTM Bridge Adapter";
    }

    @Override
    public ASTMHandlerResponse handle(ASTMMessage message, String sourceIp) {
        log.debug("ASTMBridgeAdapter handling message from {}", sourceIp);

        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(Protocol.ASTM)
            .transport(Transport.TCP)
            .sourceId(sourceIp != null ? sourceIp : "unknown")
            .rawMessage(message.getMessage())
            .build();

        boolean success = normalizer.process(envelope);

        return new ASTMHandlerResponse(
            "",
            success ? HandleStatus.SUCCESS : HandleStatus.FORWARD_FAIL_ERROR,
            false,
            this
        );
    }

    @Override
    public boolean matches(ASTMMessage message) {
        return message instanceof DefaultASTMMessage;
    }
}
```

**Then modify the bean factory** in `AstmHttpBridgeApplication.java`:

Replace the `astmHandlerService` bean method:

```java
@Bean
public ASTMHandlerService astmHandlerService(MessageNormalizer normalizer) {
    List<ASTMHandler> astmHandlers = Arrays.asList(
        new ASTMBridgeAdapter(normalizer)
    );
    return new ASTMHandlerService(astmHandlers, Mode.FIRST);
}
```

**CRITICAL — Also update the servlet beans** that call `astmHandlerService()` directly:

The two ASTM servlet beans (`astmLIS01AServlet` at line 90, `astmE138195Servlet` at line 111)
currently call `astmHandlerService(httpForwardConfig)` as a Java method call. After changing
the parameter from `HTTPForwardServerConfigurationProperties` to `MessageNormalizer`, these
calls will break.

**Best fix**: Change the servlet beans to receive `ASTMHandlerService` as a Spring-injected parameter instead:

```java
@Bean
public ASTMServlet astmLIS01AServlet(
    ASTMLIS1AListenServerConfigurationProperties astmListenConfig,
    ASTMHandlerService astmHandlerService  // CHANGED: inject bean instead of calling method
) {
    log.info("creating astm server bean to handle incoming astm LIS1-A requests on port " + astmListenConfig.getPort());
    return new ASTMServlet(
      astmHandlerService,  // CHANGED: use injected bean
      astmInterpreterFactory(),
      astmListenConfig.getPort(),
      ASTMVersion.LIS01_A
    );
}

@Bean
public ASTMServlet astmE138195Servlet(
    ASTME138195ListenServerConfigurationProperties astmListenConfig,
    ASTMHandlerService astmHandlerService  // CHANGED: inject bean instead of calling method
) {
    log.info("creating astm 1381-95 server bean...");
    return new ASTMServlet(
      astmHandlerService,  // CHANGED: use injected bean
      astmInterpreterFactory(),
      astmListenConfig.getPort(),
      ASTMVersion.E1381_95
    );
}
```

**Remove**: The `HTTPForwardServerConfigurationProperties httpForwardConfig` parameter from
`astmHandlerService()`, `astmLIS01AServlet()`, and `astmE138195Servlet()` methods. Also remove
the `DefaultForwardingASTMToHTTPHandler` import. Note: `HTTPForwardServerConfigurationProperties`
is still needed by `HttpForwardingRouter` — just not by any bean factory methods anymore.

**IMPORTANT**: Do NOT modify any files in `astm-http-lib/`. The adapter lives in the main app and implements the library interface.

### T09: Update `configuration.yml`

**File**: `configuration.yml` (MODIFY)

Add the `bridge.analyzers` section with examples:

```yaml
bridge:
  # ... existing openelis and file sections ...

  # Analyzer identification registry (M7)
  # Maps source identifiers to analyzer IDs for the AnalyzerIdentifier.
  # Keys can be: IP addresses, serial port paths, or glob patterns for file paths.
  analyzers: {}
  # Example configuration (uncomment and modify):
  #   "192.168.1.10":
  #     id: MINDRAY-BC5380-001
  #     name: "Mindray BC-5380"
  #     expectedProtocol: ASTM
  #   "192.168.1.11":
  #     id: SYSMEX-XN-001
  #     name: "Sysmex XN-1000"
  #     expectedProtocol: HL7
  #   "/dev/ttyUSB0":
  #     id: HORIBA-PENTRA60-001
  #     name: "Horiba Pentra 60"
  #     expectedProtocol: ASTM
  #   "quantstudio-*":
  #     id: QUANTSTUDIO-001
  #     name: "QuantStudio 7 Flex"
  #     expectedProtocol: CSV
  #     filePattern: ".*/quantstudio-.*\\.csv"
```

Also consolidate the dual OpenELIS URIs. Add a comment noting the legacy `org.itech.ahb.forward-http-server.uri` is used by `HttpForwardingRouter` (via `HTTPForwardServerConfigurationProperties`) while `bridge.openelis.url` was used by the now-removed File direct forwarding.

### T10: Unit Tests

**Files** (NEW):
- `src/test/java/org/itech/ahb/normalizer/MessageNormalizerTest.java`
- `src/test/java/org/itech/ahb/normalizer/AnalyzerIdentifierTest.java`
- `src/test/java/org/itech/ahb/normalizer/ASTMBridgeAdapterTest.java`

Use the existing test patterns: JUnit 5 + Mockito (from `spring-boot-starter-test`).
Follow the `@Nested` + `@DisplayName` pattern used in `SerialMessageHandlerTest` and
`AnalyzerInputControllerTest`.

**MessageNormalizerTest**: Mock `HttpForwardingRouter` (concrete type) and `AnalyzerIdentifier`.
- Test: envelope with analyzerId already set → `forwardingRouter.route()` called with same analyzerId
- Test: envelope without analyzerId, identifier returns "MINDRAY-001" → `forwardingRouter.route()` called with enriched envelope containing "MINDRAY-001"
- Test: envelope without analyzerId, identifier returns null → `forwardingRouter.route()` called with original envelope
- Test: forwardingRouter returns false → normalizer returns false
- Test: forwardingRouter returns true → normalizer returns true
- Test: `route(envelope)` delegates to `process(envelope)` (verifies MessageRouter implementation)

**AnalyzerIdentifierTest**: Mock or create test `AnalyzerRegistryConfig`.
- Test: direct IP match → returns analyzer ID
- Test: serial port path match → returns analyzer ID
- Test: glob pattern match for file path → returns analyzer ID
- Test: no match → returns null
- Test: null registry → returns null

**ASTMBridgeAdapterTest**: Mock `MessageNormalizer`.
- Test: handle with sourceIp → creates envelope with Protocol.ASTM, Transport.TCP, correct sourceIp
- Test: handle with null sourceIp → creates envelope with sourceId "unknown"
- Test: normalizer returns true → response has `HandleStatus.SUCCESS`
- Test: normalizer returns false → response has `HandleStatus.FORWARD_FAIL_ERROR`
- Test: matches `DefaultASTMMessage` → true
- Test: `getName()` returns "ASTM Bridge Adapter"

**NOTE on HandleStatus**: The enum has 9 values (SUCCESS, GENERIC_FAIL, FORWARD_FAIL_BAD_RESPONSE,
FORWARD_FAIL_ERROR, FAIL_TOO_MANY_ATTEMPTS, FAIL_LINE_CONTESTED, INTERRUPTED, and 2 deprecated).
The adapter only uses SUCCESS and FORWARD_FAIL_ERROR because it represents a simple pass/fail
from the normalizer. The other statuses (retry exhaustion, line contention, etc.) were specific
to the old `DefaultForwardingASTMToHTTPHandler` which handled its own HTTP forwarding.

### T11: Integration Test — Unified Routing

**File** (NEW): `src/test/java/org/itech/ahb/integration/UnifiedRoutingTest.java`

Verify all 5 listener types route through `MessageNormalizer` to `HttpForwardingRouter`:

Use `com.sun.net.httpserver.HttpServer` (JDK built-in) as the mock HTTP server to capture
forwarded requests. This is the **existing test pattern** used in `SerialMessageHandlerTest`.
Do NOT use `MockWebServer` (it's not a project dependency).

Configure `HttpForwardingRouter` to point to this embedded server.

Test scenarios:
1. MLLP HL7 message → `HapiReceivingApplication.processMessage()` → verify mock receives POST to `/analyzer/hl7` with correct headers (goes through normalizer because `MessageNormalizer` is `@Primary MessageRouter`)
2. Serial ASTM message → `SerialMessageHandler.handleMessage()` → verify mock receives POST to `/analyzer/astm` (no longer bypasses)
3. File CSV drop → `FileMessageHandler.processFile()` → verify mock receives POST to `/analyzer/csv` (no longer bypasses)
4. HTTP `/input` POST → `AnalyzerInputController.receiveAnalyzerMessage()` → verify mock receives POST to correct protocol endpoint (no longer drops)
5. ASTM TCP message → `ASTMBridgeAdapter.handle()` → verify mock receives POST to `/analyzer/astm` via MessageNormalizer

For each: verify `X-Source-Protocol`, `X-Source-Transport`, `X-Source-Id` headers are correct.
For MLLP: also verify `X-Analyzer-Id` is set from MSH-3/MSH-4 (via normalizer enrichment).

**Setup approach**: Create a `@SpringBootTest` or manual wiring that:
1. Starts `com.sun.net.httpserver.HttpServer` on a random port
2. Creates `HTTPForwardServerConfigurationProperties` pointing to that server
3. Creates the full chain: `AnalyzerRegistryConfig` → `AnalyzerIdentifier` → `HttpForwardingRouter` → `MessageNormalizer`
4. Tests each listener's handler method with the normalizer
5. Captures and asserts on the HTTP requests received by the mock server

---

## Additional Cleanup (R-BRIDGE items folded into this PR)

### RB-05: Document config prefix standardization

Add a note in `README.md` (or a comment in `configuration.yml`) that `org.itech.ahb.*` is legacy and `bridge.*` is the preferred format for new configuration.

### RB-06: Consolidate dual OpenELIS URIs

In `configuration.yml`, add a comment explaining:
- `org.itech.ahb.forward-http-server.uri` → used by `HttpForwardingRouter` (via `HTTPForwardServerConfigurationProperties`)
- `bridge.openelis.url` → was used by FileMessageHandler's direct forwarding (now removed by T06)
- Future: migrate `HttpForwardingRouter` to use `bridge.openelis.url` (deferred)

---

## Build & Verify

```bash
# Build (skip tests first to check compilation)
mvn clean compile

# Run all tests
mvn clean test

# Run specific M7 tests
mvn test -Dtest="MessageNormalizerTest,AnalyzerIdentifierTest,ASTMBridgeAdapterTest,UnifiedRoutingTest"

# Run full test suite including existing tests (ensure nothing broke)
mvn clean verify
```

### What Success Looks Like

1. `mvn clean verify` passes with zero failures (including all existing tests + new M7 tests)
2. All 5 listener types route through `MessageNormalizer`:
   - MLLP: via `@Primary MessageRouter` injection (no code change to `HapiReceivingApplication`)
   - Serial: via `normalizer.process()` (refactored T05)
   - File: via `normalizer.process()` (refactored T06)
   - HTTP: via `normalizer.process()` (wired T07)
   - ASTM: via `ASTMBridgeAdapter` → `normalizer.process()` (new T08)
3. `SerialMessageHandler` no longer has its own `HttpClient` or `forwardMessage()` method
4. `FileMessageHandler` no longer has its own `RestTemplate` or `forwardToOpenELIS()` method
5. `AnalyzerInputController` no longer has a TODO — it actually routes messages
6. ASTM messages go through `ASTMBridgeAdapter` → `MessageNormalizer` → `HttpForwardingRouter`
7. Retry/backoff works on `HttpForwardingRouter` (configurable via `bridge.openelis.retry.*`)
8. No changes to any files in `astm-http-lib/` directory
9. Existing tests (`SerialMessageHandlerTest`, `AnalyzerInputControllerTest`) updated and passing
10. `MessageNormalizer` is `@Primary MessageRouter` — zero changes to MLLP path

---

## Files Summary

### Create (NEW)
| File | Package |
|------|---------|
| `normalizer/MessageNormalizer.java` | `org.itech.ahb.normalizer` |
| `normalizer/AnalyzerIdentifier.java` | `org.itech.ahb.normalizer` |
| `normalizer/ASTMBridgeAdapter.java` | `org.itech.ahb.normalizer` |
| `config/AnalyzerRegistryConfig.java` | `org.itech.ahb.config` |
| `test/normalizer/MessageNormalizerTest.java` | test |
| `test/normalizer/AnalyzerIdentifierTest.java` | test |
| `test/normalizer/ASTMBridgeAdapterTest.java` | test |
| `test/integration/UnifiedRoutingTest.java` | test |

### Modify
| File | What Changes |
|------|-------------|
| `routing/HttpForwardingRouter.java` | Add retry/backoff loop, inject `OpenELISConfig` for retry config |
| `serial/SerialMessageHandler.java` | Remove direct HTTP; inject + delegate to MessageNormalizer |
| `file/FileMessageHandler.java` | Remove direct HTTP; inject + delegate to MessageNormalizer |
| `controller/AnalyzerInputController.java` | Add MessageNormalizer dependency; replace TODO with normalizer.process() call |
| `AstmHttpBridgeApplication.java` | Swap bean: ASTMBridgeAdapter replaces DefaultForwarding; update servlet beans to inject ASTMHandlerService |
| `config/OpenELISConfig.java` | Remove @ConditionalOnProperty("bridge.file") |
| `config/HttpClientConfig.java` | Remove unused `fileWatcherRestTemplate` bean |
| `configuration.yml` | Add bridge.analyzers section, document URI consolidation |
| `test/.../serial/SerialMessageHandlerTest.java` | Rewrite to mock MessageNormalizer (remove embedded HTTP server) |
| `test/.../controller/AnalyzerInputControllerTest.java` | Add mock MessageNormalizer to constructor |

### DO NOT Modify
| File | Reason |
|------|--------|
| `astm-http-lib/**/*` | Library module — adapter pattern avoids changes |
| `routing/MessageRouter.java` | Interface is correct as-is |
| `normalizer/MessageEnvelope.java` | DTO is correct as-is |
| `normalizer/NormalizedMessage.java` | Keep for future use |
| `mllp/HapiReceivingApplication.java` | Already uses `MessageRouter` — gets `MessageNormalizer` via `@Primary` injection automatically |
| `model/Protocol.java` | Enum is complete |
| `model/Transport.java` | Enum is complete |
| `util/ProtocolDetector.java` | Utility is correct as-is |
