package org.itech.ahb.normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.metrics.MetricsService;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bridge corroborates the two analyzer-identity signals — the connection
 * source IP (resolvedAnalyzerId, via {@link AnalyzerIdentifier}) and the
 * in-message sender (protocolHint) — and surfaces the outcome as a
 * {@code bridge.identity.mismatch} metric. Identity NEVER gates routing
 * (transparent-pipe rule): a mismatch is a warning, not a rejection. These tests
 * pin the three outcomes and that routing always proceeds.
 */
class MessageNormalizerIdentityCorroborationTest {

    private static final String SOURCE_IP = "10.42.21.10";

    private SimpleMeterRegistry meters;
    private AnalyzerRegistryConfig analyzerRegistry;
    private HttpForwardingRouter forwardingRouter;
    private AnalyzerIdentifier identifier;
    private MessageNormalizer normalizer;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        MetricsService metrics = new MetricsService(meters);
        analyzerRegistry = new AnalyzerRegistryConfig();
        forwardingRouter = mock(HttpForwardingRouter.class);
        identifier = mock(AnalyzerIdentifier.class);
        when(forwardingRouter.route(any(MessageEnvelope.class))).thenReturn(true);
        // Synchronous executor so any async work completes before assertions.
        normalizer = new MessageNormalizer(
            forwardingRouter, identifier, analyzerRegistry, metrics, null, null, Runnable::run);
    }

    private void registerAnalyzer(String id, String name) {
        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setId(id);
        entry.setName(name);
        analyzerRegistry.register(SOURCE_IP, entry);
    }

    private MessageEnvelope hl7FromSourceIp(String sender) {
        return MessageEnvelope.builder()
            .protocol(Protocol.HL7)
            .transport(Transport.MLLP)
            .sourceId(SOURCE_IP)
            .rawMessage("MSH|^~\\&|MINDRAY|BS-200|OE|LAB|20260101||ORU^R01|C1|P|2.3.1\r"
                + "OBX|1|NM|^^^GLU||5|mg/dL\r")
            .protocolAnalyzerHint(sender)
            .build();
    }

    private double identityCount(String mode) {
        Counter c = meters.find("bridge.identity.mismatch").tag("mode", mode).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("source IP and in-message sender agree -> corroborated; routing proceeds")
    void corroborated() {
        when(identifier.identify(any())).thenReturn("5");
        registerAnalyzer("5", "Demo Mindray BS-200"); // registered name contains the sender token

        assertTrue(normalizer.process(hl7FromSourceIp("MINDRAY-BS-200")));

        assertEquals(1.0, identityCount("corroborated"));
        assertEquals(0.0, identityCount("mismatch"));
        verify(forwardingRouter).route(any(MessageEnvelope.class));
    }

    @Test
    @DisplayName("source IP and in-message sender disagree -> mismatch; routing STILL proceeds")
    void mismatch() {
        when(identifier.identify(any())).thenReturn("7");
        registerAnalyzer("7", "Abbott Architect"); // does not contain the Mindray sender token

        assertTrue(normalizer.process(hl7FromSourceIp("MINDRAY-BS-200")));

        assertEquals(1.0, identityCount("mismatch"));
        assertEquals(0.0, identityCount("corroborated"));
        // Identity is observability only — a mismatch must never gate routing.
        verify(forwardingRouter).route(any(MessageEnvelope.class));
    }

    @Test
    @DisplayName("source IP unresolved but sender present -> degraded_content_only; routing proceeds")
    void degradedContentOnly() {
        when(identifier.identify(any())).thenReturn(null); // IP signal absent

        assertTrue(normalizer.process(hl7FromSourceIp("MINDRAY-BS-200")));

        assertEquals(1.0, identityCount("degraded_content_only"));
        verify(forwardingRouter).route(any(MessageEnvelope.class));
    }
}
