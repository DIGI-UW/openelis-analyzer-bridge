package org.itech.ahb.normalizer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unit tests for AnalyzerIdentifier.
 * <p>
 * Tests multi-strategy analyzer identification using registry lookups
 * (direct match, pattern match, fallback to null).
 * </p>
 */
class AnalyzerIdentifierTest {

    private AnalyzerIdentifier identifier;
    private AnalyzerRegistryConfig mockRegistry;

    @Nested
    @DisplayName("Protocol Hint Compatibility Tests")
    class ProtocolHintCompatibilityTests {

        @BeforeEach
        void setUp() {
            mockRegistry = mock(AnalyzerRegistryConfig.class);
            identifier = new AnalyzerIdentifier(mockRegistry);
        }

        @Test
        @DisplayName("Should prefer registry analyzer ID over envelope analyzerId")
        void shouldPreferRegistryOverEnvelopeAnalyzerId() {
            when(mockRegistry.findAnalyzerId("/dev/ttyUSB0")).thenReturn(Optional.of("HORIBA-001"));

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .analyzerId("MINDRAY-001")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("HORIBA-001", result);
            verify(mockRegistry).findAnalyzerId("/dev/ttyUSB0");
        }

        @Test
        @DisplayName("Should still lookup registry when envelope has analyzer ID")
        void shouldLookupRegistryWhenEnvelopeHasAnalyzerId() {
            when(mockRegistry.findAnalyzerId("192.168.1.10")).thenReturn(Optional.of("SYSMEX-001"));

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.MLLP)
                .sourceId("192.168.1.10")
                .rawMessage("MSH|^~\\&|||")
                .analyzerId("SYSMEX-001")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("SYSMEX-001", result);
            verify(mockRegistry).findAnalyzerId("192.168.1.10");
        }
    }

    @Nested
    @DisplayName("Registry Lookup Strategy Tests")
    class RegistryLookupTests {

        @BeforeEach
        void setUp() {
            mockRegistry = mock(AnalyzerRegistryConfig.class);
            identifier = new AnalyzerIdentifier(mockRegistry);
        }

        @Test
        @DisplayName("Should return analyzer ID from direct IP match")
        void shouldReturnAnalyzerIdFromIPMatch() {
            when(mockRegistry.findAnalyzerId("192.168.1.10")).thenReturn(Optional.of("MINDRAY-001"));

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.10")
                .rawMessage("H|\\^&|||TEST")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("MINDRAY-001", result);
        }

        @Test
        @DisplayName("Should return analyzer ID from serial port match")
        void shouldReturnAnalyzerIdFromSerialPortMatch() {
            when(mockRegistry.findAnalyzerId("/dev/ttyUSB0")).thenReturn(Optional.of("HORIBA-001"));

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("HORIBA-001", result);
        }

        @Test
        @DisplayName("Should return analyzer ID from file path pattern match")
        void shouldReturnAnalyzerIdFromFilePathPattern() {
            when(mockRegistry.findAnalyzerId("/mnt/analyzer/quantstudio-20260205.csv"))
                .thenReturn(Optional.of("QUANTSTUDIO-001"));

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId("/mnt/analyzer/quantstudio-20260205.csv")
                .rawMessage("SampleID,TestCode,Result")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("QUANTSTUDIO-001", result);
        }

        @Test
        @DisplayName("Should return null when registry returns empty")
        void shouldReturnNullWhenRegistryReturnsEmpty() {
            when(mockRegistry.findAnalyzerId("192.168.1.99")).thenReturn(Optional.empty());

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.TCP)
                .sourceId("192.168.1.99")
                .rawMessage("MSH|^~\\&|||")
                .build();

            String result = identifier.identify(envelope);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Null Registry Tests")
    class NullRegistryTests {

        @BeforeEach
        void setUp() {
            // Create identifier with null registry (not configured)
            identifier = new AnalyzerIdentifier(null);
        }

        @Test
        @DisplayName("Should return null when registry is not configured")
        void shouldReturnNullWhenRegistryIsNull() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.10")
                .rawMessage("H|\\^&|||TEST")
                .build();

            String result = identifier.identify(envelope);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null with null registry even if envelope has analyzerId")
        void shouldReturnNullWhenRegistryIsNullEvenWithEnvelopeAnalyzerId() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .analyzerId("MINDRAY-001")
                .build();

            String result = identifier.identify(envelope);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @BeforeEach
        void setUp() {
            mockRegistry = mock(AnalyzerRegistryConfig.class);
            identifier = new AnalyzerIdentifier(mockRegistry);
        }

        @Test
        @DisplayName("Should return null when sourceId is null")
        void shouldReturnNullWhenSourceIdIsNull() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId(null)
                .rawMessage("H|\\^&|||TEST")
                .build();

            String result = identifier.identify(envelope);

            assertNull(result);
            verify(mockRegistry, never()).findAnalyzerId(anyString());
        }

        @Test
        @DisplayName("Should return null when sourceId is empty")
        void shouldReturnNullWhenSourceIdIsEmpty() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("")
                .rawMessage("H|\\^&|||TEST")
                .build();

            String result = identifier.identify(envelope);

            assertNull(result);
            verify(mockRegistry, never()).findAnalyzerId(anyString());
        }

        @Test
        @DisplayName("Should handle empty analyzer ID in envelope as not pre-identified")
        void shouldHandleEmptyAnalyzerIdAsNotPreIdentified() {
            when(mockRegistry.findAnalyzerId("192.168.1.10")).thenReturn(Optional.of("MINDRAY-001"));

            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.10")
                .rawMessage("H|\\^&|||TEST")
                .analyzerId("")  // Empty string, not null
                .build();

            String result = identifier.identify(envelope);

            // Empty string should trigger registry lookup
            assertEquals("MINDRAY-001", result);
            verify(mockRegistry).findAnalyzerId("192.168.1.10");
        }
    }

    @Nested
    @DisplayName("Integration with Real Registry Tests")
    class RealRegistryTests {

        private AnalyzerRegistryConfig realRegistry;

        @BeforeEach
        void setUp() {
            realRegistry = new AnalyzerRegistryConfig();
            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> analyzers = new LinkedHashMap<>();

            // Add test entries
            AnalyzerRegistryConfig.AnalyzerEntry mindray = new AnalyzerRegistryConfig.AnalyzerEntry();
            mindray.setId("MINDRAY-001");
            mindray.setName("Mindray BC-5380");
            mindray.setExpectedProtocol("ASTM");
            analyzers.put("192.168.1.10", mindray);

            AnalyzerRegistryConfig.AnalyzerEntry horiba = new AnalyzerRegistryConfig.AnalyzerEntry();
            horiba.setId("HORIBA-001");
            horiba.setName("Horiba Pentra 60");
            horiba.setExpectedProtocol("ASTM");
            analyzers.put("/dev/ttyUSB0", horiba);

            AnalyzerRegistryConfig.AnalyzerEntry quantstudio = new AnalyzerRegistryConfig.AnalyzerEntry();
            quantstudio.setId("QUANTSTUDIO-001");
            quantstudio.setName("QuantStudio 7 Flex");
            quantstudio.setExpectedProtocol("CSV");
            quantstudio.setFilePattern(".*/quantstudio-.*\\.csv");
            analyzers.put("quantstudio-*", quantstudio);

            realRegistry.setAnalyzers(analyzers);
            identifier = new AnalyzerIdentifier(realRegistry);
        }

        @Test
        @DisplayName("Should identify analyzer by IP using real registry")
        void shouldIdentifyByIPWithRealRegistry() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.TCP)
                .sourceId("192.168.1.10")
                .rawMessage("H|\\^&|||TEST")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("MINDRAY-001", result);
        }

        @Test
        @DisplayName("Should identify analyzer by serial port using real registry")
        void shouldIdentifyBySerialPortWithRealRegistry() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.ASTM)
                .transport(Transport.SERIAL)
                .sourceId("/dev/ttyUSB0")
                .rawMessage("H|\\^&|||TEST")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("HORIBA-001", result);
        }

        @Test
        @DisplayName("Should identify analyzer by glob pattern using real registry")
        void shouldIdentifyByGlobPatternWithRealRegistry() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.CSV)
                .transport(Transport.FILE)
                .sourceId("quantstudio-20260205.csv")
                .rawMessage("SampleID,TestCode,Result")
                .build();

            String result = identifier.identify(envelope);

            assertEquals("QUANTSTUDIO-001", result);
        }

        @Test
        @DisplayName("Should return null for unregistered analyzer using real registry")
        void shouldReturnNullForUnregisteredAnalyzerWithRealRegistry() {
            MessageEnvelope envelope = MessageEnvelope.builder()
                .protocol(Protocol.HL7)
                .transport(Transport.TCP)
                .sourceId("192.168.1.99")
                .rawMessage("MSH|^~\\&|||")
                .build();

            String result = identifier.identify(envelope);

            assertNull(result);
        }
    }
}
