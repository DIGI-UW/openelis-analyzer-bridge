package org.itech.ahb.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AnalyzerRegistryConfig.
 * <p>
 * Tests analyzer source lookup and identifier-pattern compilation.
 * </p>
 */
@DisplayName("Analyzer Registry Config Tests")
class AnalyzerRegistryConfigTest {

    // -------------------------------------------------------------------------
    // Existing tests: glob/direct matching
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // identifierPattern: compile-once + validate-on-set (Copilot #42)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AnalyzerEntry.identifierPattern compiles once on set")
    class IdentifierPatternCompilation {

        @Test
        @DisplayName("a valid regex is compiled and cached (CASE_INSENSITIVE), reused per message")
        void validPatternIsCompiledAndCached() {
            AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
            entry.setIdentifierPattern("GENEXPERT|CEPHEID");

            assertNotNull(entry.getCompiledIdentifierPattern(),
                    "valid regex must compile to a cached Pattern");
            // Same instance returned each call — proves it is not recompiled per access.
            assertSame(entry.getCompiledIdentifierPattern(), entry.getCompiledIdentifierPattern());
            // CASE_INSENSITIVE: a lowercase ASTM sender still matches.
            assertTrue(entry.getCompiledIdentifierPattern().matcher("genexpert^GeneXpert^4.6.0").find());
        }

        @Test
        @DisplayName("an invalid regex leaves the compiled form null (no throw) so callers can reject/ignore")
        void invalidPatternLeavesCompiledNull() {
            AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
            // Unbalanced bracket — a PatternSyntaxException source.
            assertDoesNotThrow(() -> entry.setIdentifierPattern("MINDRAY["));
            assertEquals("MINDRAY[", entry.getIdentifierPattern(),
                    "the raw string is still retained for diagnostics");
            assertNull(entry.getCompiledIdentifierPattern(),
                    "an invalid regex must not produce a Pattern");
        }

        @Test
        @DisplayName("null / blank pattern compiles to null")
        void nullOrBlankPatternCompilesToNull() {
            AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
            entry.setIdentifierPattern(null);
            assertNull(entry.getCompiledIdentifierPattern());
            entry.setIdentifierPattern("   ");
            assertNull(entry.getCompiledIdentifierPattern());
        }

        @Test
        @DisplayName("re-setting to a new pattern replaces the cached compiled form")
        void resettingPatternReplacesCompiledForm() {
            AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
            entry.setIdentifierPattern("AAA");
            assertTrue(entry.getCompiledIdentifierPattern().matcher("aaa").find());
            entry.setIdentifierPattern("BBB");
            assertFalse(entry.getCompiledIdentifierPattern().matcher("aaa").find());
            assertTrue(entry.getCompiledIdentifierPattern().matcher("bbb").find());
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private AnalyzerRegistryConfig.AnalyzerEntry entry(String id) {
        AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
        entry.setId(id);
        return entry;
    }

}
