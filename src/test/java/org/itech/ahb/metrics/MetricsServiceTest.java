package org.itech.ahb.metrics;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for MetricsService.
 * Uses SimpleMeterRegistry (in-memory) to verify metric registration and recording.
 */
class MetricsServiceTest {

    private SimpleMeterRegistry registry;
    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new MetricsService(registry);
    }

    @Nested
    @DisplayName("Received Counter Tests")
    class ReceivedCounterTests {

        @Test
        @DisplayName("Should increment received counter")
        void shouldIncrementReceivedCounter() {
            metricsService.recordReceived("ASTM", "TCP");

            double count = registry.counter("bridge.messages.received",
                "protocol", "ASTM", "transport", "TCP").count();
            assertEquals(1.0, count);
        }

        @Test
        @DisplayName("Should track separate counters per protocol-transport combination")
        void shouldTrackSeparateCounters() {
            metricsService.recordReceived("ASTM", "TCP");
            metricsService.recordReceived("ASTM", "TCP");
            metricsService.recordReceived("HL7", "MLLP");

            assertEquals(2.0, registry.counter("bridge.messages.received",
                "protocol", "ASTM", "transport", "TCP").count());
            assertEquals(1.0, registry.counter("bridge.messages.received",
                "protocol", "HL7", "transport", "MLLP").count());
        }
    }

    @Nested
    @DisplayName("Routed Counter Tests")
    class RoutedCounterTests {

        @Test
        @DisplayName("Should increment routed counter with success result")
        void shouldIncrementRoutedCounterSuccess() {
            metricsService.recordRouted(null, "ASTM", "TCP", true);

            assertEquals(1.0, registry.counter("bridge.messages.routed",
                "protocol", "ASTM", "transport", "TCP", "result", "success").count());
        }

        @Test
        @DisplayName("Should increment routed counter with failure result")
        void shouldIncrementRoutedCounterFailure() {
            metricsService.recordRouted(null, "HL7", "MLLP", false);

            assertEquals(1.0, registry.counter("bridge.messages.routed",
                "protocol", "HL7", "transport", "MLLP", "result", "failure").count());
        }
    }

    @Nested
    @DisplayName("Timer Tests")
    class TimerTests {

        @Test
        @DisplayName("Should record routing duration")
        void shouldRecordRoutingDuration() {
            Timer.Sample sample = metricsService.startRouting();

            metricsService.recordRouted(sample, "CSV", "FILE", true);

            Timer timer = registry.timer("bridge.messages.routing.duration",
                "protocol", "CSV", "transport", "FILE");
            assertEquals(1, timer.count());
            assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
        }

        @Test
        @DisplayName("Should handle null sample gracefully")
        void shouldHandleNullSample() {
            assertDoesNotThrow(() ->
                metricsService.recordRouted(null, "ASTM", "SERIAL", true));
        }
    }
}
