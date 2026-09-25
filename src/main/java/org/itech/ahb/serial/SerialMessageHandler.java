package org.itech.ahb.serial;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.springframework.stereotype.Service;

/**
 * Handles complete messages received from serial ports.
 * <p>
 * This service:
 * <ul>
   *   <li>Uses the explicit ASTM or HL7 protocol from the pinned profile</li>
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

  /** The listener cannot acknowledge serial input before these callbacks have committed it. */
  public SerialFrameBuffer frameBuffer(String sourceBindingId, String analyzerId, Protocol protocol) {
    java.util.function.Function<String, MessageEnvelope> envelope = message ->
      MessageEnvelope.builder()
        .protocol(protocol)
        .transport(Transport.SERIAL)
        .sourceId(sourceBindingId)
        .protocolAnalyzerHint(analyzerId)
        .rawMessage(message)
        .build();
    return new SerialFrameBuffer(
      protocol,
      () -> normalizer.astmReceipt(envelope.apply(null)),
      message -> normalizer.persistBeforeAcknowledgement(envelope.apply(message))
    );
  }

    /**
     * Handles a complete message received from a serial port.
     * <p>
     * Uses the pinned profile protocol, creates a MessageEnvelope, and delegates to the
     * {@link MessageNormalizer} for routing to OpenELIS.
     * </p>
     *
     * @param message the complete message content
     * @param serialPortPath stable source binding registered for this connection;
     *                       it is not required to be a physical device path
     * @param analyzerId optional corroborating protocol hint, not routing authority
     * @param protocol protocol declared by the pinned analyzer profile
     * @return the result of handling the message
     */
    public HandleResult handleMessage(
        String message,
        String serialPortPath,
        String analyzerId,
        Protocol protocol
    ) {
        if (message == null || message.isEmpty()) {
            log.warn("Received empty message from serial port {}", serialPortPath);
            return new HandleResult(false, "Empty message");
        }

        if (protocol != Protocol.ASTM && protocol != Protocol.HL7) {
            throw new IllegalArgumentException("Serial messages require an explicit ASTM or HL7 profile protocol");
        }
        log.info("Received {} message from serial port {} ({} bytes)",
            protocol, serialPortPath, message.length());

        // Create message envelope
        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(protocol)
            .transport(Transport.SERIAL)
            .sourceId(serialPortPath)
            .rawMessage(message)
            .protocolAnalyzerHint(analyzerId)
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
