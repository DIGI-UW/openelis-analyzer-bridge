package org.itech.ahb.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class DeadLetterWriterTest {

    @TempDir
    Path tempDir;

    private DeadLetterWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        writer = new DeadLetterWriter();
        ReflectionTestUtils.setField(writer, "deadLetterDirectory", tempDir.toString());
        writer.init();
    }

    @Test
    void shouldWritePayloadAndMetadataFiles() throws IOException {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("10.0.0.50")
                .rawMessage("H|\\^&|||TEST-DATA")
                .receivedAt(Instant.parse("2026-04-02T10:00:00Z"))
                .build();

        boolean result = writer.write(envelope, "UNREGISTERED_SOURCE");

        assertTrue(result);

        // Collect once — closes the directory stream properly
        List<Path> files;
        try (var stream = Files.list(tempDir)) {
            files = stream.toList();
        }

        assertEquals(2, files.size(), "Should produce payload + metadata files");

        Path msgFile = files.stream()
                .filter(p -> p.toString().endsWith(".msg"))
                .findFirst()
                .orElseThrow();
        assertEquals("H|\\^&|||TEST-DATA", Files.readString(msgFile));

        Path metaFile = files.stream()
                .filter(p -> p.toString().endsWith(".meta"))
                .findFirst()
                .orElseThrow();
        String meta = Files.readString(metaFile);
        assertTrue(meta.contains("sourceId=10.0.0.50"));
        assertTrue(meta.contains("protocol=ASTM"));
        assertTrue(meta.contains("transport=TCP"));
        assertTrue(meta.contains("reason=UNREGISTERED_SOURCE"));
    }

    @Test
    void shouldSanitizeSourceIdInFilename() throws IOException {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId("/data/analyzer-imports/incoming")
                .rawMessage("test data")
                .receivedAt(Instant.now())
                .build();

        boolean result = writer.write(envelope, "TEST_REASON");

        assertTrue(result);

        List<Path> files;
        try (var stream = Files.list(tempDir)) {
            files = stream.toList();
        }
        files.forEach(p -> assertFalse(
                p.getFileName().toString().contains("/"),
                "Filename should not contain path separators"));
    }
}
