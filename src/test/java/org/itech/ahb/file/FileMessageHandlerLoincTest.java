package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FILE transport uses the same normalized contract as listener traffic. Raw
 * analyzer identity remains authoritative and LOINC is only an optional hint.
 */
@DisplayName("FileMessageHandler normalized FILE traffic")
class FileMessageHandlerLoincTest {

    private static final FhirContext CTX = FhirContext.forR4();
    private Observation firstObservation(String json) {
        Bundle b = CTX.newJsonParser().parseResource(Bundle.class, json);
        return b.getEntry().stream()
                .map(org.hl7.fhir.r4.model.Bundle.BundleEntryComponent::getResource)
                .filter(r -> r instanceof Observation)
                .map(r -> (Observation) r)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("FILE traffic carries exact connection context, raw code, and optional LOINC")
    void fileInboundUsesNormalizedContract() {
        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setId("43");
        entry.setName("QuantStudio 5");
        entry.setBridgeConnectionId("bridge-file-7f3c");
        entry.setProfileId("site.mock-file");
        entry.setProfileRevision(2);
        entry.setControlResultRecognition(ControlResultRecognition.none());
        entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
        entry.setCodeToLoinc(Map.of("DENV", "7855-0"));
        ParsedResults parsed = new ParsedResults(
                "DEV01260000000000008",
                List.of(AnalyzerResult.numeric("DENV", "DENV", "5000", "")));

        String json = FileMessageHandler.buildFileFhirBundle(entry, parsed, "result-001.csv");
        Bundle bundle = CTX.newJsonParser().parseResource(Bundle.class, json);
        Observation observation = firstObservation(json);
        Device device = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(Device.class::isInstance)
                .map(Device.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("bridge-file-7f3c", device.getIdentifier().stream()
                .filter(value -> "https://openelis-global.org/fhir/analyzer-connection-id"
                        .equals(value.getSystem()))
                .findFirst()
                .orElseThrow()
                .getValue());
        assertTrue(observation.getCode().getCoding().stream().anyMatch(value ->
                "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code".equals(value.getSystem())
                        && "DENV".equals(value.getCode())));
        assertTrue(observation.getCode().getCoding().stream().anyMatch(value ->
                "http://loinc.org".equals(value.getSystem()) && "7855-0".equals(value.getCode())));
    }

    @Test
    @DisplayName("FILE bundle construction rejects an analyzer without a pinned profile registration")
    void nullEntryIsRejected() {
        ParsedResults parsed = new ParsedResults(
                "ACC", List.of(AnalyzerResult.numeric("DENV", "DENV", "5000", "")));

        NullPointerException error = assertThrows(NullPointerException.class,
                () -> FileMessageHandler.buildFileFhirBundle(null, parsed, "43"));

        assertEquals("analyzerEntry is required", error.getMessage());
    }

    @Test
    @DisplayName("watched FILE uses the pinned profile code while an explicit upload choice wins")
    void resolvesFileWideTestCodeWithoutGuessing() {
        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setFileTestCode("VIH-1");

        assertEquals("VIH-1", FileMessageHandler.resolveFileTestCode(entry, null));
        assertEquals("VIH-1", FileMessageHandler.resolveFileTestCode(entry, "  "));
        assertEquals("CHIKV", FileMessageHandler.resolveFileTestCode(entry, " CHIKV "));
    }
}
