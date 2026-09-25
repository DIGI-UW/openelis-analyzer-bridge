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

  private final SerialMessageHandler handler = new SerialMessageHandler(mock(org.itech.ahb.normalizer.MessageNormalizer.class));
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
    when(port.addDataListener(org.mockito.ArgumentMatchers.any())).thenReturn(true);
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

    assertThatThrownBy(() -> listeners.start("bridge-42", "connection:bridge-42", "oe-42", "/dev/ttyUSB7", settings()))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("Cannot activate serial listener");
    assertThat(listeners.isRunning("bridge-42")).isFalse();
  }

  /** Native-device availability is controlled here; saved catalog and real framing have separate integration guards. */
  @Test
  void restoredAbsentDeviceRetainsAssignmentAndReconnectsUsingPinnedSettings() {
    SerialPort port = mock(SerialPort.class);
    java.util.concurrent.atomic.AtomicBoolean present = new java.util.concurrent.atomic.AtomicBoolean();
    when(port.openPort()).thenAnswer(call -> present.get());
    when(port.isOpen()).thenAnswer(call -> present.get());
    when(port.addDataListener(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    listeners = new SerialPortListener(handler, path -> port);
    listeners.restore("restored", "connection:restored", "oe", "/dev/absent", quickSettings(-1));
    assertThat(listeners.getPortStatus("/dev/absent").isOpen()).isFalse();
    assertThat(listeners.getPortStatus("/dev/absent").isPendingReconnect()).isTrue();
    assertThatThrownBy(() -> listeners.start("other", "other", "other", "/dev/absent", quickSettings(-1)))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("already assigned");
    present.set(true);
    org.awaitility.Awaitility.await()
      .atMost(java.time.Duration.ofSeconds(3))
      .untilAsserted(() -> assertThat(listeners.isRunning("restored")).isTrue());
    verify(port, org.mockito.Mockito.atLeast(2)).setBaudRate(9600);
    assertThat(listeners.getPortStatus("/dev/absent").isPendingReconnect()).isFalse();
  }

  @Test
  void disconnectReconnectsAndAnOldDeviceCallbackCannotCloseItsReplacement() {
    SerialPort first = mock(SerialPort.class), second = mock(SerialPort.class);
    when(first.openPort()).thenReturn(true);
    when(first.isOpen()).thenReturn(true);
    when(second.openPort()).thenReturn(true);
    when(second.isOpen()).thenReturn(true);
    var firstCallback = new java.util.concurrent.atomic.AtomicReference<
      com.fazecast.jSerialComm.SerialPortDataListener
    >();
    when(first.addDataListener(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      firstCallback.set(call.getArgument(0));
      return true;
    });
    when(second.addDataListener(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    listeners = new SerialPortListener(handler, path -> opens.getAndIncrement() == 0 ? first : second);
    listeners.start("restored", "connection:restored", "oe", "/dev/port", quickSettings(-1));
    var callback = firstCallback.get();
    callback.serialEvent(
      new com.fazecast.jSerialComm.SerialPortEvent(first, SerialPort.LISTENING_EVENT_PORT_DISCONNECTED)
    );
    org.awaitility.Awaitility.await()
      .atMost(java.time.Duration.ofSeconds(3))
      .untilAsserted(() -> assertThat(opens.get()).isEqualTo(2));
    org.awaitility.Awaitility.await()
      .atMost(java.time.Duration.ofSeconds(3))
      .untilAsserted(() -> assertThat(listeners.isRunning("restored")).isTrue());
    callback.serialEvent(
      new com.fazecast.jSerialComm.SerialPortEvent(first, SerialPort.LISTENING_EVENT_PORT_DISCONNECTED)
    );
    verify(second, org.mockito.Mockito.never()).closePort();
    listeners.stop("restored");
    callback.serialEvent(
      new com.fazecast.jSerialComm.SerialPortEvent(first, SerialPort.LISTENING_EVENT_PORT_DISCONNECTED)
    );
    assertThat(listeners.getPortStatuses()).isEmpty();
  }

  @Test
  void exhaustedRestoreRemainsVisibleAndStopRemovesItsAssignment() {
    SerialPort port = mock(SerialPort.class);
    when(port.openPort()).thenReturn(false);
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    listeners = new SerialPortListener(handler, path -> {
      attempts.incrementAndGet();
      return port;
    });
    listeners.restore("restored", "connection:restored", "oe", "/dev/absent", quickSettings(1));
    org.awaitility.Awaitility.await()
      .atMost(java.time.Duration.ofSeconds(3))
      .untilAsserted(() -> {
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(listeners.getPortStatus("/dev/absent").isPendingReconnect()).isFalse();
      });
    assertThat(listeners.getPortStatus("/dev/absent").isOpen()).isFalse();
    assertThat(listeners.getPortStatuses()).hasSize(1);
    listeners.stop("restored");
    assertThat(listeners.getPortStatuses()).isEmpty();
  }

  @Test
  void failedListenerInstallationCannotReportAnOperationalPort() {
    SerialPort port = mock(SerialPort.class);
    when(port.openPort()).thenReturn(true);
    when(port.isOpen()).thenReturn(true);
    when(port.addDataListener(org.mockito.ArgumentMatchers.any())).thenReturn(false);
    listeners = new SerialPortListener(handler, path -> port);
    assertThatThrownBy(() -> listeners.start("new", "new", "oe", "/dev/port", quickSettings(-1))).isInstanceOf(
      AnalyzerConnectionException.class
    );
    assertThat(listeners.isRunning("new")).isFalse();
    verify(port).closePort();
  }

  @Test
  void explicitStartDoesNotSilentlyAcceptAnAlreadyPendingMissingDevice() {
    SerialPort port = mock(SerialPort.class);
    when(port.openPort()).thenReturn(false);
    listeners = new SerialPortListener(handler, path -> port);
    listeners.restore("restored", "source", "oe", "/dev/missing", quickSettings(-1));
    assertThatThrownBy(
      () -> listeners.start("restored", "source", "oe", "/dev/missing", quickSettings(-1))
    ).isInstanceOf(AnalyzerConnectionException.class);
    assertThat(listeners.getPortStatuses()).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = { "available", "read", "closed" })
  void negativeIoAndSilentClosureReconnectWithoutADisconnectEvent(String failure) {
    SerialPort port = mock(SerialPort.class);
    var open = new java.util.concurrent.atomic.AtomicBoolean(true);
    when(port.openPort()).thenAnswer(call -> {
      open.set(true);
      return true;
    });
    when(port.isOpen()).thenAnswer(call -> open.get());
    when(port.bytesAvailable()).thenReturn(failure.equals("available") ? -1 : 1);
    when(
      port.readBytes(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.anyInt())
    ).thenReturn(-1);
    var callback = new java.util.concurrent.atomic.AtomicReference<com.fazecast.jSerialComm.SerialPortDataListener>();
    when(port.addDataListener(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      callback.set(call.getArgument(0));
      return true;
    });
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    listeners = new SerialPortListener(handler, path -> {
      attempts.incrementAndGet();
      return port;
    });
    listeners.start("active", "source", "oe", "/dev/port", quickSettings(-1));
    if (failure.equals("closed")) open.set(false);
    else callback
      .get()
      .serialEvent(new com.fazecast.jSerialComm.SerialPortEvent(port, SerialPort.LISTENING_EVENT_DATA_AVAILABLE));
    org.awaitility.Awaitility.await()
      .atMost(java.time.Duration.ofSeconds(3))
      .untilAsserted(() -> {
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        assertThat(listeners.getPortStatus("/dev/port").isOpen()).isTrue();
      });
    verify(port).closePort();
  }

  private static SerialConnectionSettings quickSettings(int retries) {
    return new SerialConnectionSettings("ASTM", 9600, 8, 1, "NONE", "NONE", 100, 100, 50, retries, false, false);
  }

  private static SerialConnectionSettings settings() {
    return new SerialConnectionSettings("ASTM", 9600, 8, 1, "NONE", "NONE", 1000, 30000, 5000, -1, true, true);
  }
}
