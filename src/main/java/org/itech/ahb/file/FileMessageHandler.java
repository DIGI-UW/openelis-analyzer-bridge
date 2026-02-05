package org.itech.ahb.file;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.util.ProtocolDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles file-based analyzer messages.
 * <p>
 * Reads file content, detects protocol format, and forwards to appropriate
 * OpenELIS endpoint based on protocol type (ASTM, HL7, CSV).
 * </p>
 */
@Component
@Slf4j
public class FileMessageHandler {

    private final CSVParser csvParser;
    private final RestTemplate restTemplate;
    private final FileConfig fileConfig;

    @Value("${bridge.openelis.url:http://localhost:8443}")
    private String openelisBaseUrl;

    public FileMessageHandler(CSVParser csvParser, FileConfig fileConfig) {
        this.csvParser = csvParser;
        this.fileConfig = fileConfig;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Process a file and forward to OpenELIS.
     *
     * @param filePath   the file path to process
     * @param analyzerId optional analyzer identifier (for CSV mapping lookup)
     * @return MessageEnvelope with processing metadata
     * @throws IOException              if file reading fails
     * @throws FileProcessingException if protocol detection or forwarding fails
     */
    public MessageEnvelope processFile(Path filePath, String analyzerId) throws IOException, FileProcessingException {
        log.info("Processing file: {} for analyzer: {}", filePath, analyzerId);

        // Read file content
        String content = Files.readString(filePath);
        if (content == null || content.trim().isEmpty()) {
            throw new FileProcessingException("File is empty: " + filePath);
        }

        // Detect protocol
        Protocol protocol = ProtocolDetector.detect(content);
        log.debug("Detected protocol: {} for file: {}", protocol, filePath.getFileName());

        if (protocol == Protocol.UNKNOWN) {
            throw new FileProcessingException("Unable to detect protocol for file: " + filePath);
        }

        // Create envelope
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(protocol)
                .transport(Transport.FILE)
                .sourceId(filePath.toString())
                .rawMessage(content)
                .analyzerId(analyzerId)
                .build();

        // Forward to appropriate endpoint
        forwardToOpenELIS(envelope);

        return envelope;
    }

    /**
     * Forward message envelope to OpenELIS endpoint based on protocol.
     *
     * @param envelope the message envelope to forward
     * @throws FileProcessingException if HTTP forwarding fails
     */
    private void forwardToOpenELIS(MessageEnvelope envelope) throws FileProcessingException {
        String endpoint = getEndpointForProtocol(envelope.getProtocol());
        String fullUrl = openelisBaseUrl + endpoint;

        log.debug("Forwarding {} message to: {}", envelope.getProtocol(), fullUrl);

        try {
            // Build headers
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Source-Analyzer-IP", envelope.getSourceId());
            headers.set("X-Message-Transport", Transport.FILE.name());

            if (envelope.getAnalyzerId() != null) {
                headers.set("X-Analyzer-ID", envelope.getAnalyzerId());
            }

            // Set content type based on protocol
            if (envelope.getProtocol() == Protocol.CSV) {
                headers.setContentType(MediaType.parseMediaType("text/csv"));
            } else {
                headers.setContentType(MediaType.TEXT_PLAIN);
            }

            // Create request
            HttpEntity<String> request = new HttpEntity<>(envelope.getRawMessage(), headers);

            // Send POST request
            ResponseEntity<String> response = restTemplate.postForEntity(fullUrl, request, String.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                throw new FileProcessingException(
                        "OpenELIS returned non-OK status: " + response.getStatusCode() +
                                " for protocol: " + envelope.getProtocol());
            }

            log.info("Successfully forwarded {} message to OpenELIS", envelope.getProtocol());

        } catch (Exception e) {
            log.error("Failed to forward message to OpenELIS: {}", e.getMessage(), e);
            throw new FileProcessingException("Failed to forward to OpenELIS: " + e.getMessage(), e);
        }
    }

    /**
     * Get OpenELIS endpoint path based on protocol.
     *
     * @param protocol the message protocol
     * @return endpoint path (e.g., "/api/analyzer/csv")
     */
    private String getEndpointForProtocol(Protocol protocol) {
        return switch (protocol) {
            case CSV -> "/api/OpenELIS-Global/analyzer/csv";
            case HL7 -> "/api/OpenELIS-Global/analyzer/hl7";
            case ASTM -> "/api/OpenELIS-Global/analyzer/astm";
            default -> throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        };
    }

    /**
     * Validate file content before processing.
     *
     * @param filePath the file to validate
     * @param protocol the detected protocol
     * @return true if file content appears valid for the protocol
     * @throws IOException if file reading fails
     */
    public boolean validateFileContent(Path filePath, Protocol protocol) throws IOException {
        String content = Files.readString(filePath);

        if (content == null || content.trim().isEmpty()) {
            log.warn("File is empty: {}", filePath);
            return false;
        }

        // CSV-specific validation
        if (protocol == Protocol.CSV) {
            return csvParser.isValidCSV(content);
        }

        // Basic validation for ASTM/HL7 (has minimum structure)
        return content.length() > 10 && !content.isBlank();
    }

    /**
     * Custom exception for file processing errors.
     */
    public static class FileProcessingException extends Exception {
        public FileProcessingException(String message) {
            super(message);
        }

        public FileProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
