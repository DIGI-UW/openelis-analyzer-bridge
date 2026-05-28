package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M2: the bridge emits LOINC-coded FHIR inbound so OE2 resolves results by
 * LOINC (its external-order path) and never sees analyzer codes. A code that
 * doesn't map to a LOINC falls back to the raw code (still flows, flagged by
 * the absence of the LOINC system) — never dropped.
 */
@DisplayName("FhirBundleBuilder — LOINC coding (M2)")
class FhirBundleBuilderLoincTest {

    private static final FhirContext CTX = FhirContext.forR4();
    private static final String LOINC_SYSTEM = "http://loinc.org";

    private List<Observation> observations(String json) {
        Bundle b = CTX.newJsonParser().parseResource(Bundle.class, json);
        List<Observation> obs = new ArrayList<>();
        b.getEntry().forEach(e -> {
            if (e.getResource() instanceof Observation o) {
                obs.add(o);
            }
        });
        return obs;
    }

    private Coding firstCoding(Observation o) {
        return o.getCode().getCodingFirstRep();
    }

    @Test
    @DisplayName("analyzer code -> LOINC coding (system http://loinc.org), not the raw code")
    void emitsLoinc() {
        Function<String, String> resolver = Map.of("WBC", "6690-2", "MTB-RIF", "85362-2")::get;
        List<AnalyzerResult> results = List.of(
                AnalyzerResult.numeric("WBC", "White Blood Cell", "7.5", "10*3/uL"),
                AnalyzerResult.text("MTB-RIF", "Xpert MTB/RIF", "NOT DETECTED"));

        String json = FhirBundleBuilder.buildBundle("ACC-1", "AN-1", results, null, resolver);
        List<Observation> obs = observations(json);

        assertEquals(2, obs.size());
        for (Observation o : obs) {
            assertEquals(LOINC_SYSTEM, firstCoding(o).getSystem(), "coding system must be LOINC");
        }
        List<String> codes = obs.stream().map(o -> firstCoding(o).getCode()).toList();
        assertTrue(codes.contains("6690-2"), "WBC must map to LOINC 6690-2");
        assertTrue(codes.contains("85362-2"), "MTB-RIF must map to LOINC 85362-2");
        assertFalse(codes.contains("WBC"), "raw analyzer code must NOT be the coding code");
    }

    @Test
    @DisplayName("unmapped code falls back to raw code, not dropped, no LOINC system")
    void unmappedFallsBack() {
        Function<String, String> resolver = code -> null; // nothing maps
        List<AnalyzerResult> results = List.of(AnalyzerResult.text("XYZ", "Custom Assay", "POS"));

        String json = FhirBundleBuilder.buildBundle("ACC-2", "AN-1", results, null, resolver);
        List<Observation> obs = observations(json);

        assertEquals(1, obs.size(), "unmapped result must still flow");
        Coding c = firstCoding(obs.get(0));
        assertEquals("XYZ", c.getCode(), "unmapped result keeps its raw code");
        assertNotEquals(LOINC_SYSTEM, c.getSystem(), "unmapped must not claim the LOINC system");
    }

    @Test
    @DisplayName("null resolver preserves legacy raw-code behavior (back-compat)")
    void nullResolverRawCode() {
        List<AnalyzerResult> results = List.of(AnalyzerResult.numeric("WBC", "WBC", "7.5", "x"));
        String json = FhirBundleBuilder.buildBundle("ACC-3", "AN-1", results, null, null);
        assertEquals("WBC", firstCoding(observations(json).get(0)).getCode());
    }
}
