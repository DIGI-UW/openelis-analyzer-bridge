package org.itech.ahb.file;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.util.ProtocolDetector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles file-based analyzer messages.
 * <p>
 * Reads file content, detects protocol format, and delegates to
 * {@link MessageNormalizer} for routing to OpenELIS.
 * </p>
 * <p>
 * Part of M7: Message Normalizer milestone — all transport handlers delegate to
 * the normalizer for unified routing logic, retry/backoff, and audit logging.
 * </p>
 *
 * @see MessageNormalizer
 * @see MessageEnvelope
 */
@Component
@ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true")
@Slf4j
public class FileMessageHandler {

    private final CSVParser csvParser;
    private final MessageNormalizer normalizer;
    private final FileConfig fileConfig;

    // Maximum file size to process (10MB default)
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

    /**
     * Creates a new FileMessageHandler.
     *
     * @param csvParser the CSV parser for validation
     * @param fileConfig the file watcher configuration
     * @param normalizer the message normalizer for routing
     */
    public FileMessageHandler(
            CSVParser csvParser,
            FileConfig fileConfig,
            MessageNormalizer normalizer) {
        this.csvParser = csvParser;
        this.fileConfig = fileConfig;
        this.normalizer = normalizer;
    }

    /**
     * Process a file and forward to OpenELIS via MessageNormalizer.
     * <p>
     * Reads the file, detects protocol, validates content, creates a MessageEnvelope,
     * and delegates to the {@link MessageNormalizer} for routing to OpenELIS.
     * </p>
     *
     * @param filePath   the file path to process
     * @param analyzerId optional analyzer identifier (for CSV mapping lookup)
     * @return MessageEnvelope with processing metadata
     * @throws IOException              if file reading fails
     * @throws FileProcessingException if protocol detection or forwarding fails
     */
    public MessageEnvelope processFile(Path filePath, String analyzerId) throws IOException, FileProcessingException {
        log.info("Processing file: {} for analyzer: {}", filePath, analyzerId);

        // Check file size before reading
        long fileSize = Files.size(filePath);
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            log.warn("File size ({} bytes) exceeds maximum ({}), processing anyway with caution",
                    fileSize, MAX_FILE_SIZE_BYTES);
        }
        if (fileSize == 0) {
            throw new FileProcessingException("File is empty: " + filePath);
        }

        // Read file content
        String content = Files.readString(filePath);
        if (content == null || content.trim().isEmpty()) {
            throw new FileProcessingException("File is empty: " + filePath);
        }

        // Detect protocol
        Protocol protocol = ProtocolDetector.detect(content);
        log.debug("Detected protocol: {} for file: {}", protocol, filePath.getFileName());

        if (protocol == Protocol.UNKNOWN) {
            log.warn("Unable to detect protocol for file {}, routing as raw", filePath.getFileName());
        } else if (!validateFileContent(filePath, protocol)) {
            throw new FileProcessingException("Invalid " + protocol + " content in file: " + filePath);
        }

        // Create envelope
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(protocol)
                .transport(Transport.FILE)
                .sourceId(filePath.toString())
                .rawMessage(content)
                .analyzerId(analyzerId)
                .build();

        // Delegate to normalizer for routing
        boolean success = normalizer.process(envelope);
        if (!success) {
            throw new FileProcessingException("Failed to route message for file: " + filePath);
        }

        return envelope;
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
