package org.itech.ahb.normalizer;

import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NormalizedMessage DTO.
 */
class NormalizedMessageTest {

    @Test
    void testBuilderCreatesValidNormalizedMessage() {
        Instant timestamp = Instant.now();
        String rawMessage = "H|\\^&|||MINDRAY|||...";

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("MINDRAY-BC5380-001")
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .message(rawMessage)
                .sourceId("192.168.1.10")
                .timestamp(timestamp)
                .messageId("MSG-12345")
                .build();

        assertNotNull(message);
        assertEquals("MINDRAY-BC5380-001", message.getAnalyzerId());
        assertEquals(Protocol.ASTM, message.getProtocol());
        assertEquals(Transport.TCP, message.getTransport());
        assertEquals(rawMessage, message.getMessage());
        assertEquals("192.168.1.10", message.getSourceId());
        assertEquals(timestamp, message.getTimestamp());
        assertEquals("MSG-12345", message.getMessageId());
        assertNull(message.getError());
    }

    @Test
    void testNormalizedMessageWithHL7AndMLLP() {
        Instant timestamp = Instant.now();
        String hl7Message = "MSH|^~\\&|SYSMEX|||20260205120000||ORU^R01|MSG001|P|2.5.1";

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("SYSMEX-XN-001")
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .message(hl7Message)
                .sourceId("192.168.1.11")
                .timestamp(timestamp)
                .messageId("MSG001")
                .build();

        assertEquals(Protocol.HL7, message.getProtocol());
        assertEquals(Transport.MLLP, message.getTransport());
        assertEquals("MSG001", message.getMessageId());
    }

    @Test
    void testNormalizedMessageWithSerialTransport() {
        Instant timestamp = Instant.now();

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("HORIBA-PENTRA60-001")
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .message("H|\\^&|||HORIBA|||...")
                .sourceId("/dev/ttyUSB0")
                .timestamp(timestamp)
                .build();

        assertEquals(Transport.SERIAL, message.getTransport());
        assertEquals("/dev/ttyUSB0", message.getSourceId());
        assertNull(message.getMessageId()); // Not set
    }

    @Test
    void testNormalizedMessageWithFileTransport() {
        Instant timestamp = Instant.now();
        String csvContent = "SampleID,TestCode,Result,Units\n12345,GLU,95,mg/dL";

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("QUANTSTUDIO-001")
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .message(csvContent)
                .sourceId("/mnt/analyzer-import/quantstudio/results-20260205.csv")
                .timestamp(timestamp)
                .build();

        assertEquals(Protocol.CSV, message.getProtocol());
        assertEquals(Transport.FILE, message.getTransport());
        assertTrue(message.getSourceId().endsWith(".csv"));
    }

    @Test
    void testNormalizedMessageWithHTTPTransport() {
        Instant timestamp = Instant.now();

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("ANALYZER-HTTP-001")
                .protocol(Protocol.HL7)
                .transport(Transport.HTTP)
                .message("MSH|^~\\&|ANALYZER|||...")
                .sourceId("192.168.1.20")
                .timestamp(timestamp)
                .build();

        assertEquals(Transport.HTTP, message.getTransport());
    }

    @Test
    void testNormalizedMessageWithError() {
        Instant timestamp = Instant.now();

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("UNKNOWN-001")
                .protocol(Protocol.UNKNOWN)
                .transport(Transport.TCP)
                .message("Invalid message content")
                .sourceId("192.168.1.99")
                .timestamp(timestamp)
                .error("Protocol detection failed: unknown format")
                .build();

        assertEquals(Protocol.UNKNOWN, message.getProtocol());
        assertNotNull(message.getError());
        assertTrue(message.getError().contains("Protocol detection failed"));
    }

    @Test
    void testNormalizedMessageWithoutOptionalFields() {
        Instant timestamp = Instant.now();

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("TEST-001")
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .message("H|\\^&|||TEST|||...")
                .sourceId("192.168.1.10")
                .timestamp(timestamp)
                .build();

        assertNotNull(message.getAnalyzerId());
        assertNotNull(message.getMessage());
        assertNull(message.getMessageId());
        assertNull(message.getError());
    }

    @Test
    void testToStringDoesNotThrowException() {
        Instant timestamp = Instant.now();

        NormalizedMessage message = NormalizedMessage.builder()
                .analyzerId("TEST-001")
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .message("H|\\^&|||TEST|||...")
                .sourceId("192.168.1.10")
                .timestamp(timestamp)
                .messageId("MSG-001")
                .build();

        String str = message.toString();
        assertNotNull(str);
        assertTrue(str.contains("TEST-001"));
        assertTrue(str.contains("ASTM"));
        assertTrue(str.contains("TCP"));
    }

    @Test
    void testAllProtocolsAndTransportsCombinations() {
        Instant timestamp = Instant.now();

        // Test all valid protocol/transport combinations
        Protocol[] protocols = {Protocol.ASTM, Protocol.HL7, Protocol.CSV};
        Transport[] transports = {Transport.TCP, Transport.MLLP, Transport.SERIAL, Transport.FILE, Transport.HTTP};

        for (Protocol protocol : protocols) {
            for (Transport transport : transports) {
                NormalizedMessage message = NormalizedMessage.builder()
                        .analyzerId("TEST-001")
                        .protocol(protocol)
                        .transport(transport)
                        .message("test message")
                        .sourceId("test-source")
                        .timestamp(timestamp)
                        .build();

                assertEquals(protocol, message.getProtocol());
                assertEquals(transport, message.getTransport());
            }
        }
    }
}
