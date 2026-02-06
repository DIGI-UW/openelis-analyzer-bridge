package org.itech.ahb.serial;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.util.ProtocolDetector;
import org.springframework.stereotype.Service;

/**
 * Handles complete messages received from serial ports.
 * <p>
 * This service:
 * <ul>
 *   <li>Detects the message protocol (ASTM, HL7, CSV)</li>
 *   <li>Creates a MessageEnvelope with serial transport metadata</li>
 *   <li>Delegates to {@link MessageNormalizer} for routing to OpenELIS</li>
 * </ul>
 * </p>
 * <p>
 * Part of M7: Message Normalizer milestone — all transport handlers delegate to
 * the normalizer for unified routing logic, retry/backoff, and audit logging.
 * </p>
 *
 * @see MessageNormalizer
 * @see MessageEnvelope
 */
@Slf4j
@Service
public class SerialMessageHandler {

    private final MessageNormalizer normalizer;

    /**
     * Creates a new SerialMessageHandler.
     *
     * @param normalizer the message normalizer for routing
     */
    public SerialMessageHandler(MessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Handles a complete message received from a serial port.
     * <p>
     * Detects the protocol, creates a MessageEnvelope, and delegates to the
     * {@link MessageNormalizer} for routing to OpenELIS.
     * </p>
     *
     * @param message the complete message content
     * @param serialPortPath the serial port path (e.g., /dev/ttyUSB0)
     * @param analyzerId optional analyzer ID from configuration
     * @return the result of handling the message
     */
    public HandleResult handleMessage(String message, String serialPortPath, String analyzerId) {
        if (message == null || message.isEmpty()) {
            log.warn("Received empty message from serial port {}", serialPortPath);
            return new HandleResult(false, "Empty message");
        }

        // Detect protocol
        Protocol protocol = ProtocolDetector.detect(message);
        log.info("Received {} message from serial port {} ({} bytes)",
            protocol, serialPortPath, message.length());

        // Create message envelope
        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(protocol)
            .transport(Transport.SERIAL)
            .sourceId(serialPortPath)
            .rawMessage(message)
            .analyzerId(analyzerId)
            .build();

        // Delegate to normalizer for routing
        boolean success = normalizer.process(envelope);
        return new HandleResult(success, success ? "Routed via normalizer" : "Routing failed");
    }

    /**
     * Result of handling a message.
     */
    public record HandleResult(boolean success, String message) {
    }
}
