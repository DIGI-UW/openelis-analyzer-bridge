package org.itech.ahb.health;

import java.util.List;
import java.util.Map;
import org.itech.ahb.serial.SerialPortListener;
import org.itech.ahb.serial.SerialPortListener.PortStatus;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Health.Builder;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("serial")
@ConditionalOnEnabledHealthIndicator("serial")
public class SerialHealthIndicator implements HealthIndicator {

    private final SerialPortListener listener;

    public SerialHealthIndicator(SerialPortListener listener) {
        this.listener = listener;
    }

    @Override
    public Health health() {
        List<PortStatus> statuses = listener.getPortStatuses();
        List<String> openPorts = statuses.stream()
            .filter(PortStatus::isOpen)
            .map(PortStatus::path)
            .toList();
        Builder builder;

        if (statuses.isEmpty()) {
            builder = Health.up();
        } else if (openPorts.size() != statuses.size()) {
            builder = Health.down()
                .withDetail("status", "one or more configured serial connections are unavailable");
        } else {
            builder = Health.up();
        }

        builder.withDetail("configuredConnections", statuses.size())
            .withDetail("openPorts", openPorts.size());
        if (!statuses.isEmpty()) {
            builder.withDetail("ports", openPorts);
        }
        for (PortStatus status : statuses) {
            builder.withDetail("port." + status.path(), Map.of(
                "open", status.isOpen(),
                "pendingReconnect", status.isPendingReconnect(),
                "reconnectAttempts", status.reconnectAttempts()
            ));
        }

        return builder.build();
    }
}
