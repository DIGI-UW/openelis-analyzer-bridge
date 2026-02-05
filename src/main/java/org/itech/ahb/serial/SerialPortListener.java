package org.itech.ahb.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.SerialConfigurationProperties;
import org.itech.ahb.config.properties.SerialConfigurationProperties.FlowControl;
import org.itech.ahb.config.properties.SerialConfigurationProperties.Parity;
import org.itech.ahb.config.properties.SerialConfigurationProperties.SerialPortConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Listens for data on configured serial ports and processes incoming messages.
 * <p>
 * This component:
 * <ul>
 *   <li>Opens and manages serial port connections using jSerialComm</li>
 *   <li>Buffers incoming data and detects complete messages</li>
 *   <li>Sends ACK/NAK responses for ASTM and HL7 protocols</li>
 *   <li>Handles disconnection and automatic reconnection</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "org.itech.ahb.serial", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SerialConfigurationProperties.class)
public class SerialPortListener {

    private final SerialConfigurationProperties config;
    private final SerialMessageHandler messageHandler;
    private final Map<String, ManagedSerialPort> managedPorts;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a new SerialPortListener.
     *
     * @param config the serial port configuration
     * @param messageHandler the handler for complete messages
     */
    public SerialPortListener(
        SerialConfigurationProperties config,
        SerialMessageHandler messageHandler
    ) {
        this.config = config;
        this.messageHandler = messageHandler;
        this.managedPorts = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    /**
     * Initializes and opens all configured serial ports.
     */
    @PostConstruct
    public void start() {
        if (!config.isEnabled()) {
            log.info("Serial port listener is disabled");
            return;
        }

        List<SerialPortConfig> ports = config.getPorts();
        if (ports == null || ports.isEmpty()) {
            log.warn("Serial port listener enabled but no ports configured");
            return;
        }

        log.info("Starting serial port listener with {} configured port(s)", ports.size());

        for (SerialPortConfig portConfig : ports) {
            openPort(portConfig);
        }

        // Schedule timeout checker
        scheduler.scheduleAtFixedRate(
            this::checkTimeouts,
            config.getMessageTimeoutMs(),
            config.getMessageTimeoutMs() / 2,
            TimeUnit.MILLISECONDS
        );

        // Schedule reconnection checker
        scheduler.scheduleAtFixedRate(
            this::checkReconnections,
            config.getReconnectIntervalMs(),
            config.getReconnectIntervalMs(),
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Opens a serial port with the specified configuration.
     */
    private void openPort(SerialPortConfig portConfig) {
        String portPath = portConfig.getPath();
        String portName = portConfig.getName() != null ? portConfig.getName() : portPath;

        log.info("Opening serial port {} ({})", portName, portPath);

        try {
            SerialPort serialPort = SerialPort.getCommPort(portPath);

            // Configure port parameters
            serialPort.setBaudRate(portConfig.getBaudRate());
            serialPort.setNumDataBits(portConfig.getDataBits());
            serialPort.setNumStopBits(mapStopBits(portConfig.getStopBits()));
            serialPort.setParity(mapParity(portConfig.getParity()));
            serialPort.setFlowControl(mapFlowControl(portConfig.getFlowControl()));

            // Set timeouts
            serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                portConfig.getReadTimeoutMs(),
                0
            );

            // Set RTS/DTR signals
            if (portConfig.isRtsEnabled()) {
                serialPort.setRTS();
            }
            if (portConfig.isDtrEnabled()) {
                serialPort.setDTR();
            }

            // Open the port
            if (!serialPort.openPort()) {
                log.error("Failed to open serial port {}", portPath);
                scheduleReconnect(portConfig);
                return;
            }

            log.info("Serial port {} opened successfully: {}baud, {}{}{}",
                portPath,
                portConfig.getBaudRate(),
                portConfig.getDataBits(),
                portConfig.getParity().name().charAt(0),
                portConfig.getStopBits()
            );

            // Create frame buffer
            SerialFrameBuffer frameBuffer = new SerialFrameBuffer(portConfig.getProtocol());

            // Create managed port
            ManagedSerialPort managedPort = new ManagedSerialPort(
                serialPort,
                portConfig,
                frameBuffer,
                0
            );
            managedPorts.put(portPath, managedPort);

            // Add data listener
            serialPort.addDataListener(new SerialPortDataListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                        handleDataAvailable(portPath);
                    }
                }
            });

        } catch (Exception e) {
            log.error("Error opening serial port {}: {}", portPath, e.getMessage(), e);
            scheduleReconnect(portConfig);
        }
    }

    /**
     * Handles incoming data on a serial port.
     */
    private void handleDataAvailable(String portPath) {
        ManagedSerialPort managedPort = managedPorts.get(portPath);
        if (managedPort == null || managedPort.serialPort == null || managedPort.frameBuffer == null) {
            log.trace("Port {} not ready for data (null port or buffer)", portPath);
            return;
        }

        SerialPort serialPort = managedPort.serialPort;
        SerialFrameBuffer buffer = managedPort.frameBuffer;
        SerialPortConfig portConfig = managedPort.portConfig;

        try {
            int available = serialPort.bytesAvailable();
            if (available <= 0) {
                return;
            }

            byte[] data = new byte[available];
            int bytesRead = serialPort.readBytes(data, available);

            if (bytesRead > 0) {
                log.trace("Read {} bytes from {}", bytesRead, portPath);

                // Append data and get responses to send
                List<byte[]> responses = buffer.appendData(data);

                // Send any ACK/NAK responses
                for (byte[] response : responses) {
                    serialPort.writeBytes(response, response.length);
                    log.trace("Sent {} byte response to {}", response.length, portPath);
                }

                // Process any completed messages
                List<String> messages = buffer.getCompletedMessages();
                for (String message : messages) {
                    processMessage(message, portConfig);
                }
            }
        } catch (Exception e) {
            log.error("Error reading from serial port {}: {}", portPath, e.getMessage(), e);
            handlePortError(portPath);
        }
    }

    /**
     * Processes a complete message.
     */
    private void processMessage(String message, SerialPortConfig portConfig) {
        try {
            SerialMessageHandler.HandleResult result = messageHandler.handleMessage(
                message,
                portConfig.getPath(),
                portConfig.getAnalyzerId()
            );

            if (result.success()) {
                log.info("Successfully processed message from {}", portConfig.getPath());
            } else {
                log.warn("Failed to process message from {}: {}",
                    portConfig.getPath(), result.message());
            }
        } catch (Exception e) {
            log.error("Error processing message from {}: {}",
                portConfig.getPath(), e.getMessage(), e);
        }
    }

    /**
     * Handles a port error by closing and scheduling reconnection.
     */
    private void handlePortError(String portPath) {
        ManagedSerialPort managedPort = managedPorts.remove(portPath);
        if (managedPort != null) {
            try {
                managedPort.serialPort.closePort();
            } catch (Exception e) {
                log.warn("Error closing port {}: {}", portPath, e.getMessage());
            }
            scheduleReconnect(managedPort.portConfig);
        }
    }

    /**
     * Schedules a reconnection attempt for a port.
     */
    private void scheduleReconnect(SerialPortConfig portConfig) {
        String portPath = portConfig.getPath();
        ManagedSerialPort existing = managedPorts.get(portPath);
        int attempts = existing != null ? existing.reconnectAttempts : 0;

        if (config.getMaxReconnectAttempts() >= 0 &&
            attempts >= config.getMaxReconnectAttempts()) {
            log.error("Max reconnection attempts reached for port {}", portPath);
            return;
        }

        log.info("Scheduling reconnection for port {} (attempt {})", portPath, attempts + 1);

        // Store reconnection state
        managedPorts.put(portPath, new ManagedSerialPort(
            null,
            portConfig,
            null,
            attempts + 1
        ));
    }

    /**
     * Checks for message timeouts and clears stale buffers.
     */
    private void checkTimeouts() {
        Duration timeout = Duration.ofMillis(config.getMessageTimeoutMs());

        for (ManagedSerialPort managedPort : managedPorts.values()) {
            if (managedPort.frameBuffer != null) {
                if (managedPort.frameBuffer.timeSinceLastData().compareTo(timeout) > 0 &&
                    managedPort.frameBuffer.getBufferSize() > 0) {
                    log.warn("Message timeout on port {}, clearing buffer",
                        managedPort.portConfig.getPath());
                    managedPort.frameBuffer.reset();
                }
            }
        }
    }

    /**
     * Checks for ports needing reconnection.
     */
    private void checkReconnections() {
        // Collect ports needing reconnection to avoid ConcurrentModificationException
        List<String> portsToReconnect = managedPorts.entrySet().stream()
            .filter(e -> e.getValue().serialPort == null)
            .map(Map.Entry::getKey)
            .toList();

        for (String portPath : portsToReconnect) {
            log.debug("Attempting reconnection for port {}", portPath);
            ManagedSerialPort managedPort = managedPorts.remove(portPath);
            if (managedPort != null) {
                openPort(managedPort.portConfig);
            }
        }
    }

    /**
     * Closes all serial ports and shuts down the listener.
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping serial port listener");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (ManagedSerialPort managedPort : managedPorts.values()) {
            if (managedPort.serialPort != null && managedPort.serialPort.isOpen()) {
                try {
                    managedPort.serialPort.closePort();
                    log.info("Closed serial port {}", managedPort.portConfig.getPath());
                } catch (Exception e) {
                    log.warn("Error closing port {}: {}",
                        managedPort.portConfig.getPath(), e.getMessage());
                }
            }
        }
        managedPorts.clear();
    }

    /**
     * Gets the list of currently open port paths.
     */
    public List<String> getOpenPorts() {
        return managedPorts.entrySet().stream()
            .filter(e -> e.getValue().serialPort != null && e.getValue().serialPort.isOpen())
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * Gets the status of a specific port.
     */
    public PortStatus getPortStatus(String portPath) {
        ManagedSerialPort managedPort = managedPorts.get(portPath);
        if (managedPort == null) {
            return new PortStatus(portPath, false, false, 0);
        }
        boolean isOpen = managedPort.serialPort != null && managedPort.serialPort.isOpen();
        boolean isPending = managedPort.serialPort == null;
        return new PortStatus(portPath, isOpen, isPending, managedPort.reconnectAttempts);
    }

    /**
     * Maps our Parity enum to jSerialComm values.
     */
    private int mapParity(Parity parity) {
        return switch (parity) {
            case NONE -> SerialPort.NO_PARITY;
            case ODD -> SerialPort.ODD_PARITY;
            case EVEN -> SerialPort.EVEN_PARITY;
            case MARK -> SerialPort.MARK_PARITY;
            case SPACE -> SerialPort.SPACE_PARITY;
        };
    }

    /**
     * Maps stop bits to jSerialComm values.
     */
    private int mapStopBits(int stopBits) {
        return switch (stopBits) {
            case 1 -> SerialPort.ONE_STOP_BIT;
            case 2 -> SerialPort.TWO_STOP_BITS;
            case 3 -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            default -> SerialPort.ONE_STOP_BIT;
        };
    }

    /**
     * Maps FlowControl enum to jSerialComm values.
     */
    private int mapFlowControl(FlowControl flowControl) {
        return switch (flowControl) {
            case NONE -> SerialPort.FLOW_CONTROL_DISABLED;
            case RTS_CTS -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case XON_XOFF -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
        };
    }

    /**
     * Internal record for managing serial port state.
     */
    private record ManagedSerialPort(
        SerialPort serialPort,
        SerialPortConfig portConfig,
        SerialFrameBuffer frameBuffer,
        int reconnectAttempts
    ) {}

    /**
     * Status information for a serial port.
     */
    public record PortStatus(
        String path,
        boolean isOpen,
        boolean isPendingReconnect,
        int reconnectAttempts
    ) {}
}
