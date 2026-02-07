package org.itech.ahb.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final MeterRegistry registry;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startRouting() {
        return Timer.start(registry);
    }

    public void recordReceived(String protocol, String transport) {
        registry.counter("bridge.messages.received",
            "protocol", protocol,
            "transport", transport
        ).increment();
    }

    public void recordRouted(Timer.Sample sample, String protocol, String transport, boolean success) {
        String result = success ? "success" : "failure";

        registry.counter("bridge.messages.routed",
            "protocol", protocol,
            "transport", transport,
            "result", result
        ).increment();

        if (sample != null) {
            sample.stop(registry.timer("bridge.messages.routing.duration",
                "protocol", protocol,
                "transport", transport
            ));
        }
    }
}
