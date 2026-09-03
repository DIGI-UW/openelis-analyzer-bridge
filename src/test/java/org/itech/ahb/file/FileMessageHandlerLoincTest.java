package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ca.uhn.fhir.context.FhirContext;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G1: the FILE inbound path must apply the analyzer's code→LOINC mapping (parity
 * with the ASTM/HL7 path) so OE2 resolves results by LOINC. The live E2E proof
 * showed the gap: a FILE result for "DENV" imported with a blank test_id because
 * the bundle carried the raw code. This pins the fix.
 */
@DisplayName("FileMessageHandler — FILE inbound applies code→LOINC (G1)")
class FileMessageHandlerLoincTest {

    private static final FhirContext CTX = FhirContext.forR4();
    private static final String LOINC_SYSTEM = "http://loinc.org";

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
    @DisplayName("mapped analyzer code -> LOINC coding (system http://loinc.org), not the raw code")
    void fileInboundEmitsLoinc() {
        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setId("43");
        entry.setName("QuantStudio 5");
        entry.setCodeToLoinc(Map.of("DENV", "7855-0"));
        ParsedResults parsed = new ParsedResults(
                "DEV01260000000000008",
                List.of(AnalyzerResult.numeric("DENV", "DENV", "5000", "")));

        Observation o = firstObservation(
                FileMessageHandler.buildFileFhirBundle(entry, parsed, "43"));

        assertEquals(LOINC_SYSTEM, o.getCode().getCodingFirstRep().getSystem(),
                "FILE result must be LOINC-coded, like the ASTM/HL7 path");
        assertEquals("7855-0", o.getCode().getCodingFirstRep().getCode(),
                "DENV must map to LOINC 7855-0");
        assertNotEquals("DENV", o.getCode().getCodingFirstRep().getCode(),
                "raw analyzer code must NOT be the coding code");
    }

    @Test
    @DisplayName("null entry preserves raw-code fallback (back-compat / inversion)")
    void nullEntryFallsBackToRawCode() {
        ParsedResults parsed = new ParsedResults(
                "ACC", List.of(AnalyzerResult.numeric("DENV", "DENV", "5000", "")));

        Observation o = firstObservation(
                FileMessageHandler.buildFileFhirBundle(null, parsed, "43"));

        assertEquals("DENV", o.getCode().getCodingFirstRep().getCode(),
                "with no analyzer entry the raw code is preserved (not dropped)");
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
