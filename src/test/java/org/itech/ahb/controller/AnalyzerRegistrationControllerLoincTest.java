package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.controller.AnalyzerRegistrationController.RegistrationRequest;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

/**
 * M1 wiring: a registration push carrying testCodeLoinc populates the registry
 * entry's code↔LOINC map, so the bridge can translate for that analyzer.
 */
@DisplayName("AnalyzerRegistrationController — testCodeLoinc wiring")
class AnalyzerRegistrationControllerLoincTest {

    private AnalyzerRegistryConfig registry;
    private AnalyzerRegistrationController controller;

    @BeforeEach
    void setUp() {
        registry = new AnalyzerRegistryConfig();
        FileWatcher fileWatcher = Mockito.mock(FileWatcher.class);
        FileConfig fileConfig = Mockito.mock(FileConfig.class);
        controller = new AnalyzerRegistrationController(registry, fileWatcher, fileConfig);
    }

    @Test
    @DisplayName("register() stores the pushed code→LOINC map on the entry")
    void registerStoresCodeToLoinc() {
        RegistrationRequest req = new RegistrationRequest();
        req.oeAnalyzerId = "GENEXPERT-001";
        req.sourceId = "10.0.0.5";
        req.name = "Mock GeneXpert";
        req.protocol = "ASTM";
        Map<String, String> codeLoinc = new LinkedHashMap<>();
        codeLoinc.put("MTB-RIF", "85362-2");
        codeLoinc.put("HIV-VL", "20447-9");
        req.testCodeLoinc = codeLoinc;

        ResponseEntity<Map<String, Object>> resp = controller.register(req);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("registered"));

        AnalyzerEntry entry = registry.getRegisteredAnalyzers().get("10.0.0.5");
        assertNotNull(entry, "entry should be registered under its sourceId");
        assertEquals("85362-2", entry.getLoincForCode("MTB-RIF"));
        assertEquals("MTB-RIF", entry.getCodeForLoinc("85362-2"));
        assertEquals("20447-9", entry.getLoincForCode("HIV-VL"));
    }

    @Test
    @DisplayName("register() without testCodeLoinc leaves an empty, null-safe map")
    void registerWithoutMappingIsSafe() {
        RegistrationRequest req = new RegistrationRequest();
        req.oeAnalyzerId = "GENEXPERT-002";
        req.sourceId = "10.0.0.6";
        req.protocol = "ASTM";

        controller.register(req);

        AnalyzerEntry entry = registry.getRegisteredAnalyzers().get("10.0.0.6");
        assertNotNull(entry);
        assertNull(entry.getLoincForCode("MTB-RIF"));
    }
}
