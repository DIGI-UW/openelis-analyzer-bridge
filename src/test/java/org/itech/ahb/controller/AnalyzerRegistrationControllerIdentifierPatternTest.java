package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.controller.AnalyzerRegistrationController.RegistrationRequest;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Validation + caching of {@code identifierPattern} at registration time
 * (Copilot review on PR #42). An invalid regex must be caught once, here —
 * rejected on {@code /register} (400) and ignored on a bulk {@code /sync} —
 * rather than persisted and re-warned on every inbound message in
 * {@link org.itech.ahb.normalizer.MessageNormalizer}. A valid pattern is
 * compiled once and cached on the {@link AnalyzerEntry} for per-message reuse.
 *
 * <p>Plain JUnit + Mockito (no Spring context): the validation lives in the
 * controller method and the registry is exercised for real.
 */
@DisplayName("AnalyzerRegistrationController identifierPattern validation")
class AnalyzerRegistrationControllerIdentifierPatternTest {

    private AnalyzerRegistryConfig registry;
    private AnalyzerRegistrationController controller;

    @BeforeEach
    void setUp() {
        registry = new AnalyzerRegistryConfig();
        FileWatcher fileWatcher = mock(FileWatcher.class);
        FileConfig fileConfig = mock(FileConfig.class);
        controller = new AnalyzerRegistrationController(registry, fileWatcher, fileConfig);
    }

    private RegistrationRequest req(String sourceId, String pattern) {
        RegistrationRequest r = new RegistrationRequest();
        r.oeAnalyzerId = "OE-" + sourceId;
        r.sourceId = sourceId;
        r.name = "Analyzer " + sourceId;
        r.protocol = "HL7"; // non-FILE: skips the file-watcher branch
        r.identifierPattern = pattern;
        return r;
    }

    @Test
    @DisplayName("/register rejects an invalid identifierPattern with 400 and does not register it")
    void registerRejectsInvalidPattern() {
        ResponseEntity<Map<String, Object>> response = controller.register(req("10.0.0.1", "MINDRAY["));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("registered"));
        assertTrue(registry.getRegisteredAnalyzers().isEmpty(),
                "an analyzer with an invalid pattern must not be registered");
    }

    @Test
    @DisplayName("/register accepts a valid identifierPattern and caches the compiled Pattern")
    void registerAcceptsValidPatternAndCaches() {
        ResponseEntity<Map<String, Object>> response =
                controller.register(req("10.0.0.2", "GENEXPERT|CEPHEID"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("registered"));

        AnalyzerEntry entry = registry.getRegisteredAnalyzers().get("10.0.0.2");
        assertNotNull(entry, "the analyzer must be registered");
        assertNotNull(entry.getCompiledIdentifierPattern(),
                "a valid pattern must be compiled and cached on the entry");
        assertTrue(entry.getCompiledIdentifierPattern().matcher("genexpert^GeneXpert^4.6.0").find());
    }

    @Test
    @DisplayName("/sync ignores an invalid pattern (registers without it) but keeps valid ones")
    void syncIgnoresInvalidButKeepsValid() {
        ResponseEntity<Map<String, Object>> response = controller.sync(List.of(
                req("10.0.0.3", "BAD[REGEX"),
                req("10.0.0.4", "MINDRAY.*BC.?5380")));

        assertEquals(200, response.getStatusCode().value());

        AnalyzerEntry bad = registry.getRegisteredAnalyzers().get("10.0.0.3");
        assertNotNull(bad, "a bad pattern must not drop the whole analyzer from a bulk sync");
        assertNull(bad.getIdentifierPattern(),
                "the invalid pattern is cleared so the entry falls back to name/id matching");
        assertNull(bad.getCompiledIdentifierPattern());

        AnalyzerEntry good = registry.getRegisteredAnalyzers().get("10.0.0.4");
        assertNotNull(good);
        assertNotNull(good.getCompiledIdentifierPattern(),
                "the valid pattern in the same sync batch is still compiled and cached");
    }
}
