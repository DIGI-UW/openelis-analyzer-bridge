package org.itech.ahb.normalizer;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.lib.astm.handling.ASTMHandler;
import org.itech.ahb.lib.astm.handling.ASTMHandlerResponse;
import org.itech.ahb.lib.common.handling.HandleStatus;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;

/**
 * ASTM bridge adapter that implements the ASTM library's {@link ASTMHandler} interface
 * and delegates to {@link MessageNormalizer} for routing to OpenELIS.
 * <p>
 * This adapter replaces {@code DefaultForwardingASTMToHTTPHandler} in the M7 milestone,
 * ensuring all transport listeners (including ASTM TCP) route through the unified normalizer
 * for consistent retry/backoff, audit logging, and analyzer identification.
 * </p>
 * <p>
 * The adapter:
 * <ol>
 *   <li>Receives ASTM messages from the ASTM servlet (TCP listener)</li>
 *   <li>Creates a {@link MessageEnvelope} with Protocol.ASTM and Transport.TCP</li>
 *   <li>Delegates to {@link MessageNormalizer} for routing to OpenELIS</li>
 *   <li>Returns {@link ASTMHandlerResponse} with appropriate {@link HandleStatus}</li>
 * </ol>
 * </p>
 * <p>
 * NOTE: This class lives in the main application (not astm-http-lib) and uses the adapter
 * pattern to avoid modifying the library code.
 * </p>
 *
 * @see MessageNormalizer
 * @see MessageEnvelope
 * @see ASTMHandler
 */
@Slf4j
public class ASTMBridgeAdapter implements ASTMHandler {

    private final MessageNormalizer normalizer;

    /**
     * Constructs a new ASTMBridgeAdapter.
     *
     * @param normalizer the message normalizer for routing
     */
    public ASTMBridgeAdapter(MessageNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Returns the name of this handler for logging purposes.
     *
     * @return "ASTM Bridge Adapter"
     */
    @Override
    public String getName() {
        return "ASTM Bridge Adapter";
    }

    /**
     * Handles an ASTM message by creating a MessageEnvelope and routing via the normalizer.
     * <p>
     * The adapter:
     * <ul>
     *   <li>Extracts the raw message string from {@link ASTMMessage}</li>
     *   <li>Uses the provided sourceIp (or "unknown" if null)</li>
     *   <li>Creates a MessageEnvelope with Protocol.ASTM and Transport.TCP</li>
     *   <li>Delegates to {@link MessageNormalizer#process(MessageEnvelope)}</li>
     *   <li>Returns SUCCESS or FORWARD_FAIL_ERROR status</li>
     * </ul>
     * </p>
     *
     * @param message the ASTM message from the TCP listener
     * @param sourceIp the IP address of the analyzer (may be null)
     * @return ASTMHandlerResponse with appropriate status
     */
    @Override
    public ASTMHandlerResponse handle(ASTMMessage message, String sourceIp) {
        log.debug("ASTMBridgeAdapter handling message from {}", sourceIp);

        // Extract sender identifier from ASTM H-record field 4 (e.g., "GENEXPERT^GeneXpert^4.6.0")
        String analyzerId = extractSenderFromHRecord(message.getMessage());

        // Create MessageEnvelope
        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(Protocol.ASTM)
            .transport(Transport.TCP)
            .sourceId(sourceIp != null ? sourceIp : "unknown")
            .rawMessage(message.getMessage())
            .protocolAnalyzerHint(analyzerId)
            .build();

        // Route via normalizer
        boolean success = normalizer.process(envelope);

        // Return appropriate response
        HandleStatus status = success ? HandleStatus.SUCCESS : HandleStatus.FORWARD_FAIL_ERROR;

        return new ASTMHandlerResponse(
            "",  // Empty response string (not used by ASTM protocol ACK)
            status,
            false,  // Don't communicate response back to analyzer
            this
        );
    }

    /**
     * Checks if this handler matches the given ASTM message.
     * <p>
     * This adapter matches all {@link DefaultASTMMessage} instances (the standard ASTM message type).
     * </p>
     *
     * @param message the ASTM message to check
     * @return true if message is a DefaultASTMMessage, false otherwise
     */
    /**
     * Extract sender identification from ASTM H-record field 4.
     * H-record format: H|\^&|msgId|senderInfo|...
     * Field 4 may contain: "GENEXPERT^GeneXpert^4.6.0" or "MINDRAY"
     * Returns the first component (before ^) as the identifier.
     */
    private String extractSenderFromHRecord(String rawMessage) {
        if (rawMessage == null) return null;
        for (String line : rawMessage.split("\r")) {
            if (line.startsWith("H|")) {
                String[] fields = line.split("\\|", -1);
                if (fields.length > 4 && fields[4] != null && !fields[4].isBlank()) {
                    String sender = fields[4].split("\\^")[0].trim();
                    if (!sender.isEmpty()) {
                        log.debug("Extracted ASTM sender identifier: {}", sender);
                        return sender;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean matches(ASTMMessage message) {
        return message instanceof DefaultASTMMessage;
    }
}
