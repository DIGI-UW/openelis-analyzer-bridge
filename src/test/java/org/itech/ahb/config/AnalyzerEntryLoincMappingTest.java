package org.itech.ahb.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M1: the bridge owns analyzer-code ↔ LOINC translation. The mapping is pushed
 * from OE2 (profile default_test_mappings) and stored per-analyzer on the
 * registry entry. These pin the bidirectional lookup that inbound (code→LOINC)
 * and outbound (LOINC→code) translation depend on.
 */
@DisplayName("AnalyzerEntry code<->LOINC mapping")
class AnalyzerEntryLoincMappingTest {

    private AnalyzerEntry entryWithMappings() {
        AnalyzerEntry e = new AnalyzerEntry();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("MTB-RIF", "85362-2");
        m.put("HIV-VL", "20447-9");
        e.setCodeToLoinc(m);
        return e;
    }

    @Test
    @DisplayName("getLoincForCode resolves analyzer code -> LOINC (inbound)")
    void codeToLoinc() {
        AnalyzerEntry e = entryWithMappings();
        assertEquals("85362-2", e.getLoincForCode("MTB-RIF"));
        assertEquals("20447-9", e.getLoincForCode("HIV-VL"));
    }

    @Test
    @DisplayName("getCodeForLoinc resolves LOINC -> analyzer code (outbound, reverse)")
    void loincToCode() {
        AnalyzerEntry e = entryWithMappings();
        assertEquals("MTB-RIF", e.getCodeForLoinc("85362-2"));
        assertEquals("HIV-VL", e.getCodeForLoinc("20447-9"));
    }

    @Test
    @DisplayName("unknown code/LOINC returns null so the caller can apply a fallback")
    void unknownReturnsNull() {
        AnalyzerEntry e = entryWithMappings();
        assertNull(e.getLoincForCode("BOGUS"));
        assertNull(e.getCodeForLoinc("99999-9"));
    }

    @Test
    @DisplayName("unset mapping is null-safe (no NPE)")
    void emptyMappingSafe() {
        AnalyzerEntry e = new AnalyzerEntry();
        assertNull(e.getLoincForCode("MTB-RIF"));
        assertNull(e.getCodeForLoinc("85362-2"));
    }
}
