package org.itech.ahb.util;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Writes unroutable messages to a filesystem dead-letter directory.
 * Each message produces two files: payload and metadata sidecar.
 */
@Component
@Slf4j
public class DeadLetterWriter {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss.SSS")
            .withZone(ZoneOffset.UTC);

    @Value("${bridge.deadLetter.directory:${java.io.tmpdir}/openelis-analyzer-bridge/dead-letters}")
    private String deadLetterDirectory;

    private Path dlqPath;

    @PostConstruct
    void init() throws IOException {
        dlqPath = Path.of(deadLetterDirectory);
        Files.createDirectories(dlqPath);
    }

    /**
     * Write a message to the dead-letter directory with a metadata sidecar.
     *
     * @return true if written successfully, false on failure
     */
    public boolean write(MessageEnvelope envelope, String reason) {
        try {
            String timestamp = TS_FORMAT.format(
                    envelope.getReceivedAt() != null ? envelope.getReceivedAt() : Instant.now());
            String sourceSlug = sanitize(envelope.getSourceId());
            String baseName = String.format("%s_%s_%s_%d",
                    timestamp, envelope.getProtocol(), sourceSlug, System.nanoTime() % 100000);

            // Payload — CREATE_NEW to detect collisions
            Path payloadFile = dlqPath.resolve(baseName + ".msg");
            try {
                Files.writeString(payloadFile, envelope.getRawMessage(), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException e) {
                baseName = baseName + "_" + System.nanoTime();
                payloadFile = dlqPath.resolve(baseName + ".msg");
                Files.writeString(payloadFile, envelope.getRawMessage());
            }

            // Metadata sidecar
            Path metaFile = dlqPath.resolve(baseName + ".meta");
            String meta = String.join("\n",
                    "sourceId=" + envelope.getSourceId(),
                    "protocol=" + envelope.getProtocol(),
                    "transport=" + envelope.getTransport(),
                    "protocolHint=" + firstNonNull(envelope.getProtocolAnalyzerHint(), ""),
                    "sourcePort=" + (envelope.getSourcePort() != null ? envelope.getSourcePort() : ""),
                    "receivedAt=" + timestamp,
                    "reason=" + reason);
            Files.writeString(metaFile, meta);

            log.info("Dead-lettered message: {} (reason: {})", payloadFile, reason);
            return true;
        } catch (IOException e) {
            log.error("Failed to write dead letter for source '{}': {}",
                    envelope.getSourceId(), e.getMessage());
            return false;
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
