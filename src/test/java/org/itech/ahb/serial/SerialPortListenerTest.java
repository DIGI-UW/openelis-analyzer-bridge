package org.itech.ahb.serial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fazecast.jSerialComm.SerialPort;
import org.itech.ahb.connection.AnalyzerConnectionException;
import org.itech.ahb.connection.SerialConnectionSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SerialPortListenerTest {

  private final SerialMessageHandler handler = mock(SerialMessageHandler.class);
  private SerialPortListener listeners;

  @AfterEach
  void stopListeners() {
    if (listeners != null) {
      listeners.stopAll();
    }
  }

  @Test
  void startsAndStopsTheExactSavedConnectionUsingProfileSettings() {
    SerialPort port = mock(SerialPort.class);
    when(port.openPort()).thenReturn(true);
    when(port.isOpen()).thenReturn(true);
    listeners = new SerialPortListener(handler, path -> port);
    SerialConnectionSettings settings = settings();

    listeners.start("bridge-42", "connection:bridge-42", "oe-42", "/dev/ttyUSB7", settings);

    verify(port).setBaudRate(9600);
    verify(port).setNumDataBits(8);
    verify(port).setNumStopBits(SerialPort.ONE_STOP_BIT);
    verify(port).setParity(SerialPort.NO_PARITY);
    verify(port).setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
    assertThat(listeners.isRunning("bridge-42")).isTrue();

    listeners.stop("bridge-42");

    verify(port).removeDataListener();
    verify(port).closePort();
    assertThat(listeners.isRunning("bridge-42")).isFalse();
  }

  @Test
  void rejectsActivationWhenTheSavedSerialPortCannotBeOpened() {
    SerialPort port = mock(SerialPort.class);
    when(port.openPort()).thenReturn(false);
    listeners = new SerialPortListener(handler, path -> port);

    assertThatThrownBy(() ->
      listeners.start("bridge-42", "connection:bridge-42", "oe-42", "/dev/ttyUSB7", settings())
    )
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("Cannot activate serial listener");
    assertThat(listeners.isRunning("bridge-42")).isFalse();
  }

  private static SerialConnectionSettings settings() {
    return new SerialConnectionSettings(
      "ASTM",
      9600,
      8,
      1,
      "NONE",
      "NONE",
      1000,
      30000,
      5000,
      -1,
      true,
      true
    );
  }
}
