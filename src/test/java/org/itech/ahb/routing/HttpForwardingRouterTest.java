package org.itech.ahb.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.config.FhirRoutingConfig;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.file.RejectedBundle;
import org.itech.ahb.file.SqliteFileStateStore;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused tests for {@link HttpForwardingRouter}'s B1 rejection persistence
 * hook.
 * <p>
 * Runs a minimal {@link HttpServer} as the "OE webapp" stand-in. The router
 * is configured against it with a single attempt so retry-exhaustion fires
 * deterministically. A real {@link SqliteFileStateStore} (tempdir-backed)
 * verifies the full write path — no mocks at the persistence boundary.
 * </p>
 */
class HttpForwardingRouterTest {

    private HttpServer server;
    private int port;
    private AtomicInteger statusCodeToReturn;
    private AtomicInteger requestCount;
    private SqliteFileStateStore stateStore;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        statusCodeToReturn = new AtomicInteger(200);
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/analyzer", exchange -> {
            requestCount.incrementAndGet();
            int code = statusCodeToReturn.get();
            byte[] body = ("status " + code).getBytes();
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        stateStore = new SqliteFileStateStore(tmp.resolve("state.db"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (stateStore != null) stateStore.close();
    }

    @Test
    void fourHundredOne_persistsRejectedBundleWithHttpStatusAndPayload() {
        HTTPForwardServerConfigurationProperties httpConfig = minimalConfig();
        HttpForwardingRouter router = new HttpForwardingRouter(httpConfig, null, stateStore, null);

        statusCodeToReturn.set(401);
        MessageEnvelope env = envelope("100.127.144.150", "H|... raw astm payload ...");
        boolean result = router.route(env);

        assertFalse(result, "4xx must be reported as a routing failure");
        List<RejectedBundle> rows = stateStore.listRejections(10);
        assertEquals(1, rows.size(), "non-retryable 4xx must persist exactly one rejection");
        RejectedBundle r = rows.get(0);
        assertEquals("100.127.144.150", r.sourceId());
        assertEquals("ASTM", r.protocol());
        assertEquals(401, r.httpStatus());
        assertNotNull(r.lastError());
        assertTrue(r.lastError().contains("401"),
                "lastError should name the status code for operator triage");
        assertNotNull(r.payloadSnippet());
        assertTrue(r.payloadSnippet().startsWith("H|"),
                "payloadSnippet must reflect the raw message the bridge tried to forward");
    }

    @Test
    void fiveHundred_exhaustedRetries_persistsWithStatusZero() {
        HTTPForwardServerConfigurationProperties httpConfig = minimalConfig();
        HttpForwardingRouter router = new HttpForwardingRouter(httpConfig, null, stateStore, null);

        statusCodeToReturn.set(500);
        boolean result = router.route(envelope("10.0.0.5", "raw body"));

        assertFalse(result);
        List<RejectedBundle> rows = stateStore.listRejections(10);
        assertEquals(1, rows.size(),
                "retry-exhausted 5xx must persist exactly one rejection (not one per attempt)");
        assertEquals(0, rows.get(0).httpStatus(),
                "httpStatus=0 distinguishes transport/5xx exhaustion from a deterministic 4xx");
        assertTrue(rows.get(0).lastError().contains("attempts failed"));
    }

    @Test
    void twoHundred_doesNotPersistAnything() {
        HTTPForwardServerConfigurationProperties httpConfig = minimalConfig();
        HttpForwardingRouter router = new HttpForwardingRouter(httpConfig, null, stateStore, null);

        statusCodeToReturn.set(200);
        boolean result = router.route(envelope("10.0.0.6", "body"));

        assertTrue(result, "2xx must succeed");
        assertEquals(0, stateStore.listRejections(10).size(),
                "successful forward must not generate a rejected_bundles row");
    }

    @Test
    void nullStateStore_rejectsPayloadLogsOnly_noThrow() {
        HTTPForwardServerConfigurationProperties httpConfig = minimalConfig();
        HttpForwardingRouter router = new HttpForwardingRouter(httpConfig, null, null, null);

        statusCodeToReturn.set(401);
        // Must not throw; router must still return false; the log line is the
        // only diagnostic available in this path.
        boolean result = router.route(envelope("src", "body"));
        assertFalse(result);
    }

    @Test
    void fhirRoutingRejectsAnAnalyzerWithoutProfileOwnedRecognition() {
        FhirRoutingConfig fhirConfig = new FhirRoutingConfig();
        fhirConfig.setUseFhir(true);
        AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setId("analyzer-1");
        entry.setExpectedProtocol("ASTM");
        registry.register("10.0.0.7", entry);
        HttpForwardingRouter router = new HttpForwardingRouter(
                minimalConfig(), fhirConfig, stateStore, registry);

        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("10.0.0.7")
                .resolvedAnalyzerId("analyzer-1")
                .rawMessage("H|\\^&|||Analyzer\rP|1\rO|1|SAMPLE-1\rR|1|^^^WBC|7.5|10*3/uL\rL|1")
                .build();

        assertFalse(router.route(envelope));
        assertEquals(0, requestCount.get(),
                "traffic without an explicit pinned-profile recognition mode must not be forwarded");
    }

    @Test
    void fhirRoutingRejectsAnAstmAnalyzerWithoutProfileOwnedResultSelection() {
        FhirRoutingConfig fhirConfig = new FhirRoutingConfig();
        fhirConfig.setUseFhir(true);
        AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setId("analyzer-1");
        entry.setExpectedProtocol("ASTM");
        entry.setControlResultRecognition(ControlResultRecognition.none());
        registry.register("10.0.0.8", entry);
        HttpForwardingRouter router = new HttpForwardingRouter(
                minimalConfig(), fhirConfig, stateStore, registry);

        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("10.0.0.8")
                .resolvedAnalyzerId("analyzer-1")
                .rawMessage("H|\\^&|||Analyzer\rP|1\rO|1|SAMPLE-1\rR|1|^^^WBC|7.5|10*3/uL\rL|1")
                .build();

        assertFalse(router.route(envelope));
        assertEquals(0, requestCount.get());
        assertTrue(stateStore.listRejections(10).get(0).lastError().contains("result-record selection"));
    }

    private HTTPForwardServerConfigurationProperties minimalConfig() {
        HTTPForwardServerConfigurationProperties c = new HTTPForwardServerConfigurationProperties();
        c.setUri(URI.create("http://localhost:" + port + "/analyzer"));
        c.setMaxAttempts(2);
        c.setBackoffMs(1);
        c.setConnectTimeoutSeconds(2);
        c.setReadTimeoutSeconds(2);
        return c;
    }

    private MessageEnvelope envelope(String sourceId, String rawBody) {
        return MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.HTTP)
                .sourceId(sourceId)
                .rawMessage(rawBody)
                .build();
    }
}
