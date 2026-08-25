package org.itech.ahb.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.itech.ahb.serial.SerialPortListener;
import org.itech.ahb.serial.SerialPortListener.PortStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class SerialHealthIndicatorTest {

  @Test
  void noConfiguredSerialConnectionsIsHealthy() {
    SerialPortListener listener = mock(SerialPortListener.class);
    when(listener.getPortStatuses()).thenReturn(List.of());

    Health health = new SerialHealthIndicator(listener).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
      .containsEntry("configuredConnections", 0)
      .containsEntry("openPorts", 0);
  }

  @Test
  void mixedSerialHealthReportsAccurateCountsAndEveryConnection() {
    SerialPortListener listener = mock(SerialPortListener.class);
    when(listener.getPortStatuses()).thenReturn(List.of(
      new PortStatus("/dev/ttyUSB0", true, false, 0),
      new PortStatus("/dev/ttyUSB1", false, true, 2)
    ));

    Health health = new SerialHealthIndicator(listener).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
      .containsEntry("configuredConnections", 2)
      .containsEntry("openPorts", 1);
    assertThat(health.getDetails().get("port./dev/ttyUSB0"))
      .isEqualTo(Map.of("open", true, "pendingReconnect", false, "reconnectAttempts", 0));
    assertThat(health.getDetails().get("port./dev/ttyUSB1"))
      .isEqualTo(Map.of("open", false, "pendingReconnect", true, "reconnectAttempts", 2));
  }
}
