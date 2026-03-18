package org.itech.ahb.normalizer;

import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.ASTMRecord;
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

        // Reconstruct the raw ASTM message text by joining individual segment records
        // with the ASTM LIS1-A record separator (CR, \r).
        //
        // Why not message.getMessage()?
        //   DefaultASTMMessage.getMessage() joins records with an EMPTY delimiter (""),
        //   producing a single concatenated line like "H|...P|...O|...R|1|...L|1|N".
        //   Java's BufferedReader.readLine() (used by OE's ASTMAnalyzerReader) then
        //   reads the entire message as ONE line and cannot identify individual segments,
        //   so no results are parsed and stored.
        //
        // Why \r?
        //   ASTM LIS1-A standard uses CR (0x0D) as the record separator within a
        //   multi-record frame. BufferedReader.readLine() splits on \r, \n, or \r\n,
        //   so OE's reader will correctly see each segment as a separate line.
        String rawMessage = reconstructRawMessage(message);
        log.debug("Reconstructed ASTM message: {} records, {} chars", message.getMessageLength(), rawMessage.length());

        // Create MessageEnvelope
        MessageEnvelope envelope = MessageEnvelope.builder()
            .protocol(Protocol.ASTM)
            .transport(Transport.TCP)
            .sourceId(sourceIp != null ? sourceIp : "unknown")
            .rawMessage(rawMessage)
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
     * Reconstructs the raw ASTM message text from individual segment records.
     * <p>
     * The ASTM library's {@code ASTMMessage.getMessage()} concatenates records with an
     * empty delimiter, producing a single unparseable line. This method instead joins
     * records with the ASTM LIS1-A record separator (CR, {@code \r}), which
     * {@link java.io.BufferedReader#readLine()} in OE's {@code ASTMAnalyzerReader} will
     * correctly split back into individual segments (H, P, O, R, L).
     * </p>
     *
     * @param message the ASTM message whose records to reconstruct
     * @return the ASTM text with records joined by {@code \r}, or the raw message string
     *         as a fallback if no records are available
     */
    private String reconstructRawMessage(ASTMMessage message) {
        List<ASTMRecord> records = message.getRecords();
        if (records == null || records.isEmpty()) {
            log.warn("ASTMMessage has no parsed records; falling back to getMessage()");
            return message.getMessage();
        }
        return records.stream()
            .map(ASTMRecord::getRecord)
            .collect(Collectors.joining("\r")) + "\r";
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
    @Override
    public boolean matches(ASTMMessage message) {
        return message instanceof DefaultASTMMessage;
    }
}
