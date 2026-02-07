package org.itech.ahb.health;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.serial.SerialPortListener;
import org.itech.ahb.serial.SerialPortListener.PortStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Health.Builder;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("serial")
@ConditionalOnEnabledHealthIndicator("serial")
@Slf4j
public class SerialHealthIndicator implements HealthIndicator {

    private final SerialPortListener listener;

    public SerialHealthIndicator(@Autowired(required = false) SerialPortListener listener) {
        this.listener = listener;
    }

    @Override
    public Health health() {
        if (listener == null) {
            return Health.unknown()
                .withDetail("reason", "Serial listener not configured")
                .build();
        }

        List<String> openPorts = listener.getOpenPorts();
        Builder builder;

        if (openPorts.isEmpty()) {
            builder = Health.down()
                .withDetail("openPorts", 0)
                .withDetail("status", "no ports open");
        } else {
            builder = Health.up()
                .withDetail("openPorts", openPorts.size())
                .withDetail("ports", openPorts);

            for (String port : openPorts) {
                PortStatus status = listener.getPortStatus(port);
                builder.withDetail("port." + port, Map.of(
                    "open", status.isOpen(),
                    "pendingReconnect", status.isPendingReconnect(),
                    "reconnectAttempts", status.reconnectAttempts()
                ));
            }
        }

        return builder.build();
    }
}
