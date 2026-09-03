package org.itech.ahb.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AnalyzerRuntimeRegistry.
 * <p>
 * Tests analyzer source lookup and identifier-pattern compilation.
 * </p>
 */
@DisplayName("Analyzer Runtime Registry Tests")
class AnalyzerRuntimeRegistryTest {

    // -------------------------------------------------------------------------
    // Existing tests: glob/direct matching
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Glob pattern should match against file name")
    void globMatchesFileName() {
        AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
        registry.register("quantstudio-*", entry("QUANTSTUDIO-001"));

        Optional<String> result = registry.findAnalyzerId(
            "/mnt/analyzer-import/quantstudio/quantstudio-20260205.csv");

        assertTrue(result.isPresent());
        assertEquals("QUANTSTUDIO-001", result.get());
    }

    @Test
    @DisplayName("Glob pattern should match against full path")
    void globMatchesFullPath() {
        AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
        registry.register("/mnt/analyzer-import/quantstudio/*.csv", entry("QUANTSTUDIO-002"));

        Optional<String> result = registry.findAnalyzerId(
            "/mnt/analyzer-import/quantstudio/quantstudio-20260205.csv");

        assertTrue(result.isPresent());
        assertEquals("QUANTSTUDIO-002", result.get());
    }

    @Test
    @DisplayName("Direct match should take precedence over glob")
    void directMatchPreferredOverGlob() {
        AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
        registry.register("192.168.1.10", entry("MINDRAY-001"));
        registry.register("192.168.*", entry("GENERIC-001"));

        Optional<String> result = registry.findAnalyzerId("192.168.1.10");

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
            AnalyzerRuntimeRegistry.AnalyzerEntry entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
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
            AnalyzerRuntimeRegistry.AnalyzerEntry entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
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
            AnalyzerRuntimeRegistry.AnalyzerEntry entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
            entry.setIdentifierPattern(null);
            assertNull(entry.getCompiledIdentifierPattern());
            entry.setIdentifierPattern("   ");
            assertNull(entry.getCompiledIdentifierPattern());
        }

        @Test
        @DisplayName("re-setting to a new pattern replaces the cached compiled form")
        void resettingPatternReplacesCompiledForm() {
            AnalyzerRuntimeRegistry.AnalyzerEntry entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
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

    private AnalyzerRuntimeRegistry.AnalyzerEntry entry(String id) {
        AnalyzerRuntimeRegistry.AnalyzerEntry entry = new AnalyzerRuntimeRegistry.AnalyzerEntry();
        entry.setId(id);
        return entry;
    }

}
