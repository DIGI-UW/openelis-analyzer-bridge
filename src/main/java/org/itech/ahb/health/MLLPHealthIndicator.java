package org.itech.ahb.health;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.mllp.HapiMLLPListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("mllp")
@ConditionalOnEnabledHealthIndicator("mllp")
@Slf4j
public class MLLPHealthIndicator implements HealthIndicator {

    private final HapiMLLPListener listener;

    public MLLPHealthIndicator(@Autowired(required = false) HapiMLLPListener listener) {
        this.listener = listener;
    }

    @Override
    public Health health() {
        if (listener == null) {
            return Health.unknown()
                .withDetail("reason", "MLLP listener not configured")
                .build();
        }

        if (listener.isRunning()) {
            return Health.up()
                .withDetail("port", listener.getPort())
                .withDetail("status", "accepting connections")
                .build();
        }

        return Health.down()
            .withDetail("port", listener.getPort())
            .withDetail("status", "not running")
            .build();
    }
}
