package org.itech.ahb.normalizer;

import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageEnvelope DTO.
 */
class MessageEnvelopeTest {

    @Test
    void testBuilderCreatesValidEnvelope() {
        String rawMessage = "H|\\^&|||TEST|||...";
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.10")
                .rawMessage(rawMessage)
                .protocolAnalyzerHint("MINDRAY")
                .resolvedAnalyzerId("MINDRAY-BC5380-001")
                .build();

        assertNotNull(envelope);
        assertEquals(Protocol.ASTM, envelope.getProtocol());
        assertEquals(Transport.TCP, envelope.getTransport());
        assertEquals("192.168.1.10", envelope.getSourceId());
        assertEquals(rawMessage, envelope.getRawMessage());
        assertEquals("MINDRAY", envelope.getProtocolAnalyzerHint());
        assertEquals("MINDRAY-BC5380-001", envelope.getResolvedAnalyzerId());
        assertNotNull(envelope.getReceivedAt());
    }

    @Test
    void testBuilderSetsTimestampAutomatically() {
        Instant before = Instant.now();

        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId("192.168.1.11")
                .rawMessage("MSH|^~\\&|TEST|||...")
                .build();

        Instant after = Instant.now();

        assertNotNull(envelope.getReceivedAt());
        assertTrue(envelope.getReceivedAt().isAfter(before) || envelope.getReceivedAt().equals(before));
        assertTrue(envelope.getReceivedAt().isBefore(after) || envelope.getReceivedAt().equals(after));
    }

    @Test
    void testEnvelopeWithSerialTransport() {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||HORIBA|||...")
                .build();

        assertEquals(Transport.SERIAL, envelope.getTransport());
        assertEquals("/dev/ttyUSB0", envelope.getSourceId());
    }

    @Test
    void testEnvelopeWithFileTransport() {
        String filePath = "/mnt/analyzer-import/quantstudio/results-20260205.csv";
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId(filePath)
                .rawMessage("SampleID,TestCode,Result\n12345,GLU,95")
                .build();

        assertEquals(Transport.FILE, envelope.getTransport());
        assertEquals(Protocol.CSV, envelope.getProtocol());
        assertEquals(filePath, envelope.getSourceId());
    }

    @Test
    void testEnvelopeWithHTTPTransport() {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.HTTP)
                .sourceId("192.168.1.20")
                .rawMessage("MSH|^~\\&|ANALYZER|||...")
                .build();

        assertEquals(Transport.HTTP, envelope.getTransport());
        assertNull(envelope.getResolvedAnalyzerId());
    }

    @Test
    void testEnvelopeWithoutResolvedConnection() {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.99")
                .rawMessage("H|\\^&|||UNKNOWN|||...")
                .build();

        assertNull(envelope.getResolvedAnalyzerId());
    }

    @Test
    void testToStringDoesNotThrowException() {
        MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.10")
                .rawMessage("H|\\^&|||TEST|||...")
                .build();

        String str = envelope.toString();
        assertNotNull(str);
        assertTrue(str.contains("ASTM"));
        assertTrue(str.contains("TCP"));
        assertTrue(str.contains("192.168.1.10"));
    }
}
