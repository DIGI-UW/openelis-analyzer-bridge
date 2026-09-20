package org.itech.ahb.mllp;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import ca.uhn.hl7v2.util.Terser;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.routing.MessageRouter;

/**
 * HAPI ReceivingApplication that bridges incoming HL7 messages to the MessageRouter.
 * <p>
 * This component:
 * <ul>
 *   <li>Receives parsed HL7 messages from HAPI SimpleServer</li>
 *   <li>Extracts source IP from HAPI connection metadata</li>
 *   <li>Uses the saved listener binding as source authority; MSH-3/MSH-4 are diagnostic hints only</li>
 *   <li>Creates a MessageEnvelope for internal routing</li>
 *   <li>Delegates to MessageRouter for HTTP forwarding</li>
 *   <li>Returns HAPI-generated ACK/NAK responses</li>
 * </ul>
 * </p>
 * <p>
 * HAPI metadata keys used:
 * <ul>
 *   <li>{@code SENDING_IP} - Source IP address (String)</li>
 *   <li>{@code SENDING_PORT} - Source port (Integer)</li>
 *   <li>{@code raw-message} - Original HL7 message text (String)</li>
 *   <li>{@code /MSH-10} - Message Control ID (String)</li>
 * </ul>
 * </p>
 */
@Slf4j
public class HapiReceivingApplication implements ReceivingApplication<Message> {

    /** HAPI metadata key for source IP address */
    static final String META_SENDING_IP = "SENDING_IP";

    /** HAPI metadata key for source port */
    static final String META_SENDING_PORT = "SENDING_PORT";

    /** HAPI metadata key for raw message */
    static final String META_RAW_MESSAGE = "raw-message";

    private final MessageRouter router;
    private final PipeParser pipeParser;
    private final String sourceBindingId;
    private final Object lifecycle = new Object();
    private boolean accepting = true;
    private int inFlight;

    /**
     * Constructs a new HapiReceivingApplication with the specified router.
     *
     * @param router the message router for forwarding messages
     * @param sourceBindingId the saved connection binding owned by this listener
     */
    public HapiReceivingApplication(MessageRouter router, String sourceBindingId) {
        if (sourceBindingId == null || sourceBindingId.isBlank()) {
            throw new IllegalArgumentException("A saved-connection source binding is required");
        }
        this.router = router;
        this.sourceBindingId = sourceBindingId;
        this.pipeParser = new PipeParser();
    }

    /**
     * Processes an incoming HL7 message from HAPI SimpleServer.
     * <p>
     * Creates a MessageEnvelope from the HAPI message and metadata,
     * routes it via the MessageRouter, and returns a HAPI-generated ACK or NAK.
     * </p>
     *
     * @param message the parsed HL7 message from HAPI
     * @param metadata connection metadata from HAPI (contains SENDING_IP, etc.)
     * @return ACK message on success
     * @throws ReceivingApplicationException on routing failure (HAPI generates NAK)
     */
    @Override
    public Message processMessage(Message message, Map<String, Object> metadata)
            throws ReceivingApplicationException {
        synchronized (lifecycle) {
            if (!accepting) throw new ReceivingApplicationException("HL7 connection is stopping");
            inFlight++;
        }
        try {
            // Extract connection metadata
            String sourceIp = extractSourceIp(metadata);
            String rawMessage = extractRawMessage(message, metadata);
            String analyzerId = extractAnalyzerId(message);

            log.debug("Processing HL7 message from {} (analyzer: {}), {} bytes",
                sourceIp, analyzerId, rawMessage != null ? rawMessage.length() : 0);
            log.trace("HL7 message received ({} bytes)", rawMessage != null ? rawMessage.length() : 0);

            // Extract source port from HAPI metadata
            Integer sourcePort = extractSourcePort(metadata);

            // Create MessageEnvelope for routing
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId(sourceBindingId)
                .sourcePort(sourcePort)
                .rawMessage(rawMessage)
                .receivedAt(Instant.now())
                .protocolAnalyzerHint(analyzerId)
                .build();

            // Route the message via MessageRouter
            boolean success = router.route(envelope);

            if (!success) {
                log.error("Failed to route HL7 message from {} (analyzer: {})", sourceIp, analyzerId);
                throw new ReceivingApplicationException("Message routing failed");
            }

            log.info("Accepted HL7 message from {} (analyzer: {}); it is durably held and delivery continues in the background", sourceIp, analyzerId);

            // HAPI generates properly-formed ACK with all required MSH fields
            return message.generateACK();

        } catch (ReceivingApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing HL7 message", e);
            throw new ReceivingApplicationException(e);
        } finally {
            synchronized (lifecycle) {
                inFlight--;
                lifecycle.notifyAll();
            }
        }
    }

    void stopAccepting() {
        synchronized (lifecycle) { accepting = false; }
    }

    void awaitDrained() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        synchronized (lifecycle) {
            while (inFlight != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("HL7 connection still has active delivery work");
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
            }
        }
    }

    /**
     * Accepts all HL7 message types.
     *
     * @param message the HL7 message to check
     * @return always true (accepts all message types)
     */
    @Override
    public boolean canProcess(Message message) {
        return true;
    }

    /**
     * Extracts the source IP address from HAPI connection metadata.
     *
     * @param metadata the HAPI metadata map
     * @return the source IP address, or "unknown" if not available
     */
    String extractSourceIp(Map<String, Object> metadata) {
        Object sendingIp = metadata.get(META_SENDING_IP);
        if (sendingIp != null) {
            return sendingIp.toString();
        }

        log.warn("Could not extract source IP from HAPI metadata (keys: {})", metadata.keySet());
        return "unknown";
    }

    /**
     * Extracts the source port number from HAPI connection metadata.
     *
     * @param metadata the HAPI metadata map
     * @return the source port, or null if not available
     */
    Integer extractSourcePort(Map<String, Object> metadata) {
        Object sendingPort = metadata.get(META_SENDING_PORT);
        if (sendingPort instanceof Integer) {
            return (Integer) sendingPort;
        }
        if (sendingPort != null) {
            try {
                return Integer.parseInt(sendingPort.toString());
            } catch (NumberFormatException e) {
                log.debug("Could not parse SENDING_PORT: {}", sendingPort);
            }
        }
        return null;
    }

    /**
     * Extracts the raw HL7 message text.
     * <p>
     * Prefers the raw-message from HAPI metadata (preserves original formatting),
     * falls back to encoding from the parsed Message object.
     * </p>
     *
     * @param message the parsed HAPI message
     * @param metadata the HAPI metadata map
     * @return the raw HL7 message text
     * @throws HL7Exception if encoding fails
     */
    private String extractRawMessage(Message message, Map<String, Object> metadata)
            throws HL7Exception {
        // Prefer raw message from metadata (preserves original formatting)
        Object rawMessage = metadata.get(META_RAW_MESSAGE);
        if (rawMessage != null) {
            return rawMessage.toString();
        }

        // Fall back to encoding from parsed message
        return pipeParser.encode(message);
    }

    /**
     * Extracts the analyzer identifier from the HL7 message.
     * <p>
     * Uses HAPI Terser for safe MSH segment parsing (fixes PR review comments #2, #3).
     * Tries MSH-3 (Sending Application) and MSH-4 (Sending Facility).
     * </p>
     *
     * @param message the parsed HAPI message
     * @return the message-declared analyzer identifier, or null when absent
     */
    String extractAnalyzerId(Message message) {
        try {
            Terser terser = new Terser(message);

            // MSH-3: Sending Application
            String sendingApp = terser.get("/MSH-3");

            if (sendingApp != null && !sendingApp.isEmpty()) {
                // MSH-4: Sending Facility
                String sendingFacility = terser.get("/MSH-4");

                if (sendingFacility != null && !sendingFacility.isEmpty()) {
                    return sendingApp + "-" + sendingFacility;
                }
                return sendingApp;
            }
        } catch (HL7Exception e) {
            log.debug("Could not extract analyzer ID from MSH segment", e);
        }

        return null;
    }
}
