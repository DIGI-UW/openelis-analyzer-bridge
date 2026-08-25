package org.itech.ahb.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerConnectionException;
import org.itech.ahb.connection.SerialConnectionListeners;
import org.itech.ahb.connection.SerialConnectionSettings;
import org.itech.ahb.model.Protocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns serial ports opened for active, profile-pinned Bridge connections. */
@Component
@Slf4j
public final class SerialPortListener implements SerialConnectionListeners {

  private final SerialMessageHandler messageHandler;
  private final Function<String, SerialPort> portFactory;
  private final Map<String, ManagedSerialPort> managedPorts = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

  @Autowired
  public SerialPortListener(SerialMessageHandler messageHandler) {
    this(messageHandler, SerialPort::getCommPort);
  }

  SerialPortListener(
    SerialMessageHandler messageHandler,
    Function<String, SerialPort> portFactory
  ) {
    this.messageHandler = messageHandler;
    this.portFactory = portFactory;
  }

  @Override
  public synchronized void start(
    String connectionId,
    String sourceBindingId,
    String analyzerId,
    String portPath,
    SerialConnectionSettings settings
  ) {
    ManagedSerialPort current = managedPorts.get(connectionId);
    if (
      current != null &&
      current.matches(sourceBindingId, analyzerId, portPath, settings) &&
      current.isOpen()
    ) {
      return;
    }
    stop(connectionId);
    if (managedPorts.values().stream().anyMatch(candidate -> candidate.portPath.equals(portPath))) {
      throw new AnalyzerConnectionException("Serial port " + portPath + " is already assigned to another connection");
    }

    ManagedSerialPort managed = new ManagedSerialPort(
      connectionId,
      sourceBindingId,
      analyzerId,
      portPath,
      settings
    );
    managedPorts.put(connectionId, managed);
    try {
      open(managed);
      long timeoutCheckMs = Math.max(1, settings.messageTimeoutMs() / 2L);
      managed.timeoutFuture = scheduler.scheduleAtFixedRate(
        () -> checkTimeout(connectionId),
        settings.messageTimeoutMs(),
        timeoutCheckMs,
        TimeUnit.MILLISECONDS
      );
    } catch (RuntimeException exception) {
      managedPorts.remove(connectionId);
      close(managed);
      throw new AnalyzerConnectionException(
        "Cannot activate serial listener for Bridge connection " + connectionId,
        exception
      );
    }
  }

  @Override
  public synchronized void stop(String connectionId) {
    ManagedSerialPort managed = managedPorts.remove(connectionId);
    if (managed == null) {
      return;
    }
    if (managed.timeoutFuture != null) {
      managed.timeoutFuture.cancel(false);
    }
    if (managed.reconnectFuture != null) {
      managed.reconnectFuture.cancel(false);
    }
    close(managed);
  }

  boolean isRunning(String connectionId) {
    ManagedSerialPort managed = managedPorts.get(connectionId);
    return managed != null && managed.isOpen();
  }

  public List<String> getOpenPorts() {
    return managedPorts.values().stream()
      .filter(ManagedSerialPort::isOpen)
      .map(managed -> managed.portPath)
      .toList();
  }

  public PortStatus getPortStatus(String portPath) {
    return managedPorts.values().stream()
      .filter(managed -> managed.portPath.equals(portPath))
      .findFirst()
      .map(managed ->
        new PortStatus(
          portPath,
          managed.isOpen(),
          managed.reconnectFuture != null && !managed.reconnectFuture.isDone(),
          managed.reconnectAttempts
        )
      )
      .orElseGet(() -> new PortStatus(portPath, false, false, 0));
  }

  public List<PortStatus> getPortStatuses() {
    return managedPorts.values().stream()
      .map(managed ->
        new PortStatus(
          managed.portPath,
          managed.isOpen(),
          managed.reconnectFuture != null && !managed.reconnectFuture.isDone(),
          managed.reconnectAttempts
        )
      )
      .toList();
  }

  @PreDestroy
  public synchronized void stopAll() {
    for (String connectionId : List.copyOf(managedPorts.keySet())) {
      stop(connectionId);
    }
    scheduler.shutdownNow();
  }

  private void open(ManagedSerialPort managed) {
    SerialPort port = portFactory.apply(managed.portPath);
    SerialConnectionSettings settings = managed.settings;
    port.setBaudRate(settings.baudRate());
    port.setNumDataBits(settings.dataBits());
    port.setNumStopBits(stopBits(settings.stopBits()));
    port.setParity(parity(settings.parity()));
    port.setFlowControl(flowControl(settings.flowControl()));
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, settings.readTimeoutMs(), 0);
    if (settings.rtsEnabled()) {
      port.setRTS();
    }
    if (settings.dtrEnabled()) {
      port.setDTR();
    }
    if (!port.openPort()) {
      throw new AnalyzerConnectionException("Serial port " + managed.portPath + " could not be opened");
    }

    managed.port = port;
    managed.frameBuffer = new SerialFrameBuffer(protocol(settings.protocol()));
    port.addDataListener(new SerialPortDataListener() {
      @Override
      public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
      }

      @Override
      public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
          handleDataAvailable(managed.connectionId);
        }
      }
    });
    managed.reconnectAttempts = 0;
    managed.reconnectFuture = null;
    log.info("Serial port {} opened for Bridge connection {}", managed.portPath, managed.connectionId);
  }

  private synchronized void handleDataAvailable(String connectionId) {
    ManagedSerialPort managed = managedPorts.get(connectionId);
    if (managed == null || !managed.isOpen() || managed.frameBuffer == null) {
      return;
    }
    try {
      int available = managed.port.bytesAvailable();
      if (available <= 0) {
        return;
      }
      byte[] data = new byte[available];
      int bytesRead = managed.port.readBytes(data, available);
      if (bytesRead <= 0) {
        return;
      }
      for (byte[] response : managed.frameBuffer.appendData(data)) {
        managed.port.writeBytes(response, response.length);
      }
      for (String message : managed.frameBuffer.getCompletedMessages()) {
        SerialMessageHandler.HandleResult result = messageHandler.handleMessage(
          message,
          managed.sourceBindingId,
          managed.analyzerId,
          protocol(managed.settings.protocol())
        );
        if (!result.success()) {
          log.warn("Serial message routing failed for Bridge connection {}: {}", connectionId, result.message());
        }
      }
    } catch (RuntimeException exception) {
      log.error("Serial read failed for Bridge connection {}", connectionId, exception);
      close(managed);
      scheduleReconnect(managed);
    }
  }

  private synchronized void checkTimeout(String connectionId) {
    ManagedSerialPort managed = managedPorts.get(connectionId);
    if (
      managed == null ||
      managed.frameBuffer == null ||
      managed.frameBuffer.getBufferSize() == 0
    ) {
      return;
    }
    Duration timeout = Duration.ofMillis(managed.settings.messageTimeoutMs());
    if (managed.frameBuffer.timeSinceLastData().compareTo(timeout) > 0) {
      managed.frameBuffer.reset();
    }
  }

  private void scheduleReconnect(ManagedSerialPort managed) {
    int maximum = managed.settings.maxReconnectAttempts();
    if (maximum >= 0 && managed.reconnectAttempts >= maximum) {
      log.error("Serial reconnect limit reached for Bridge connection {}", managed.connectionId);
      return;
    }
    managed.reconnectAttempts++;
    managed.reconnectFuture = scheduler.schedule(
      () -> reconnect(managed.connectionId),
      managed.settings.reconnectIntervalMs(),
      TimeUnit.MILLISECONDS
    );
  }

  private synchronized void reconnect(String connectionId) {
    ManagedSerialPort managed = managedPorts.get(connectionId);
    if (managed == null) {
      return;
    }
    try {
      open(managed);
    } catch (RuntimeException exception) {
      log.warn("Serial reconnect failed for Bridge connection {}", connectionId);
      scheduleReconnect(managed);
    }
  }

  private static void close(ManagedSerialPort managed) {
    if (managed.port == null) {
      return;
    }
    try {
      managed.port.removeDataListener();
      managed.port.closePort();
    } finally {
      managed.port = null;
      managed.frameBuffer = null;
    }
  }

  private static Protocol protocol(String protocol) {
    try {
      return Protocol.valueOf(protocol);
    } catch (IllegalArgumentException exception) {
      throw new AnalyzerConnectionException("Serial framing is unsupported for profile protocol " + protocol, exception);
    }
  }

  private static int parity(String value) {
    return switch (value) {
      case "NONE" -> SerialPort.NO_PARITY;
      case "ODD" -> SerialPort.ODD_PARITY;
      case "EVEN" -> SerialPort.EVEN_PARITY;
      case "MARK" -> SerialPort.MARK_PARITY;
      case "SPACE" -> SerialPort.SPACE_PARITY;
      default -> throw new AnalyzerConnectionException("Unsupported serial parity " + value);
    };
  }

  private static int stopBits(int value) {
    return switch (value) {
      case 1 -> SerialPort.ONE_STOP_BIT;
      case 2 -> SerialPort.TWO_STOP_BITS;
      case 3 -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
      default -> throw new AnalyzerConnectionException("Unsupported serial stop bits " + value);
    };
  }

  private static int flowControl(String value) {
    return switch (value) {
      case "NONE" -> SerialPort.FLOW_CONTROL_DISABLED;
      case "RTS_CTS" -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
      case "XON_XOFF" -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
      default -> throw new AnalyzerConnectionException("Unsupported serial flow control " + value);
    };
  }

  private static final class ManagedSerialPort {

    private final String connectionId;
    private final String sourceBindingId;
    private final String analyzerId;
    private final String portPath;
    private final SerialConnectionSettings settings;
    private SerialPort port;
    private SerialFrameBuffer frameBuffer;
    private int reconnectAttempts;
    private ScheduledFuture<?> timeoutFuture;
    private ScheduledFuture<?> reconnectFuture;

    private ManagedSerialPort(
      String connectionId,
      String sourceBindingId,
      String analyzerId,
      String portPath,
      SerialConnectionSettings settings
    ) {
      this.connectionId = connectionId;
      this.sourceBindingId = sourceBindingId;
      this.analyzerId = analyzerId;
      this.portPath = portPath;
      this.settings = settings;
    }

    private boolean matches(
      String expectedSourceBindingId,
      String expectedAnalyzerId,
      String expectedPortPath,
      SerialConnectionSettings expectedSettings
    ) {
      return sourceBindingId.equals(expectedSourceBindingId) &&
      analyzerId.equals(expectedAnalyzerId) &&
      portPath.equals(expectedPortPath) &&
      settings.equals(expectedSettings);
    }

    private boolean isOpen() {
      return port != null && port.isOpen();
    }
  }

  public record PortStatus(
    String path,
    boolean isOpen,
    boolean isPendingReconnect,
    int reconnectAttempts
  ) {}
}
