package org.itech.ahb.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Analyzer Registry Config Tests")
class AnalyzerRegistryConfigTest {

    @Test
    @DisplayName("Glob pattern should match against file name")
    void globMatchesFileName() {
        AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
        Map<String, AnalyzerRegistryConfig.AnalyzerEntry> analyzers = new LinkedHashMap<>();
        analyzers.put("quantstudio-*", entry("QUANTSTUDIO-001"));
        config.setAnalyzers(analyzers);

        Optional<String> result = config.findAnalyzerId(
            "/mnt/analyzer-import/quantstudio/quantstudio-20260205.csv");

        assertTrue(result.isPresent());
        assertEquals("QUANTSTUDIO-001", result.get());
    }

    @Test
    @DisplayName("Glob pattern should match against full path")
    void globMatchesFullPath() {
        AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
        Map<String, AnalyzerRegistryConfig.AnalyzerEntry> analyzers = new LinkedHashMap<>();
        analyzers.put("/mnt/analyzer-import/quantstudio/*.csv", entry("QUANTSTUDIO-002"));
        config.setAnalyzers(analyzers);

        Optional<String> result = config.findAnalyzerId(
            "/mnt/analyzer-import/quantstudio/quantstudio-20260205.csv");

        assertTrue(result.isPresent());
        assertEquals("QUANTSTUDIO-002", result.get());
    }

    @Test
    @DisplayName("Direct match should take precedence over glob")
    void directMatchPreferredOverGlob() {
        AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
        Map<String, AnalyzerRegistryConfig.AnalyzerEntry> analyzers = new LinkedHashMap<>();
        analyzers.put("192.168.1.10", entry("MINDRAY-001"));
        analyzers.put("192.168.*", entry("GENERIC-001"));
        config.setAnalyzers(analyzers);

        Optional<String> result = config.findAnalyzerId("192.168.1.10");

        assertTrue(result.isPresent());
        assertEquals("MINDRAY-001", result.get());
    }

    private AnalyzerRegistryConfig.AnalyzerEntry entry(String id) {
        AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
        entry.setId(id);
        return entry;
    }
}
