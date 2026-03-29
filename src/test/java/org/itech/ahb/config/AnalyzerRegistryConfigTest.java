package org.itech.ahb.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AnalyzerRegistryConfig.
 * <p>
 * Tests glob/direct matching, registration, and the syncAll() full-state sync method.
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
    // syncAll() tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("syncAll()")
    class SyncAllTests {

        @Test
        @DisplayName("Should replace entire registry with new entries")
        void testSyncAll_replacesRegistry() {
            AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
            config.register("192.168.1.10", entry("OLD-001", "Old Analyzer", "ASTM"));

            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> newRegistry = new LinkedHashMap<>();
            newRegistry.put("10.0.0.1", entry("NEW-001", "New Analyzer", "HL7"));
            newRegistry.put("10.0.0.2", entry("NEW-002", "Another New", "ASTM"));

            config.syncAll(newRegistry);

            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> registered = config.getRegisteredAnalyzers();
            assertEquals(2, registered.size());
            assertTrue(registered.containsKey("10.0.0.1"));
            assertTrue(registered.containsKey("10.0.0.2"));
            assertFalse(registered.containsKey("192.168.1.10"), "Old entry should be gone");
        }

        @Test
        @DisplayName("Should count newly added keys correctly")
        void testSyncAll_countsAddedCorrectly() {
            AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
            config.register("192.168.1.10", entry("EXISTING-001"));

            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> newRegistry = new LinkedHashMap<>();
            newRegistry.put("192.168.1.10", entry("EXISTING-001")); // unchanged
            newRegistry.put("10.0.0.1", entry("NEW-001"));          // added
            newRegistry.put("10.0.0.2", entry("NEW-002"));          // added

            AnalyzerRegistryConfig.SyncResult result = config.syncAll(newRegistry);

            assertEquals(2, result.added());
            assertEquals(3, result.total());
        }

        @Test
        @DisplayName("Should count updated keys (changed values) correctly")
        void testSyncAll_countsUpdatedCorrectly() {
            AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
            config.register("192.168.1.10", entry("ANALYZER-001", "Old Name", "ASTM"));

            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> newRegistry = new LinkedHashMap<>();
            newRegistry.put("192.168.1.10", entry("ANALYZER-001", "Updated Name", "HL7")); // changed

            AnalyzerRegistryConfig.SyncResult result = config.syncAll(newRegistry);

            assertEquals(1, result.updated());
            assertEquals(0, result.added());
            assertEquals(0, result.removed());
        }

        @Test
        @DisplayName("Should not count unchanged keys as updated")
        void testSyncAll_countsUnchangedCorrectly() {
            AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
            AnalyzerRegistryConfig.AnalyzerEntry original = entry("ANALYZER-001", "Same Name", "ASTM");
            config.register("192.168.1.10", original);

            // Create an identical entry (same field values)
            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> newRegistry = new LinkedHashMap<>();
            newRegistry.put("192.168.1.10", entry("ANALYZER-001", "Same Name", "ASTM"));

            AnalyzerRegistryConfig.SyncResult result = config.syncAll(newRegistry);

            assertEquals(0, result.updated(), "Unchanged entry should not be counted as updated");
            assertEquals(0, result.added());
            assertEquals(0, result.removed());
            assertEquals(1, result.total());
        }

        @Test
        @DisplayName("Should count removed keys correctly")
        void testSyncAll_countsRemovedCorrectly() {
            AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
            config.register("192.168.1.10", entry("ANALYZER-001"));
            config.register("192.168.1.11", entry("ANALYZER-002"));
            config.register("192.168.1.12", entry("ANALYZER-003"));

            // New registry only keeps one of the three
            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> newRegistry = new LinkedHashMap<>();
            newRegistry.put("192.168.1.10", entry("ANALYZER-001"));

            AnalyzerRegistryConfig.SyncResult result = config.syncAll(newRegistry);

            assertEquals(2, result.removed());
            assertEquals(1, result.total());
        }

        @Test
        @DisplayName("Should clear registry when syncing with empty map")
        void testSyncAll_emptySync() {
            AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
            config.register("192.168.1.10", entry("ANALYZER-001"));
            config.register("192.168.1.11", entry("ANALYZER-002"));

            AnalyzerRegistryConfig.SyncResult result = config.syncAll(new LinkedHashMap<>());

            assertEquals(0, result.total());
            assertEquals(0, result.added());
            assertEquals(0, result.updated());
            assertEquals(2, result.removed());
            assertTrue(config.getRegisteredAnalyzers().isEmpty());
        }

        @Test
        @DisplayName("Should not throw ConcurrentModificationException under concurrent access")
        void testSyncAll_threadSafety() throws Exception {
            AnalyzerRegistryConfig config = new AnalyzerRegistryConfig();
            // Seed with initial data
            for (int i = 0; i < 50; i++) {
                config.register("key-" + i, entry("ANALYZER-" + i));
            }

            int threadCount = 8;
            int iterationsPerThread = 100;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    for (int i = 0; i < iterationsPerThread; i++) {
                        if (threadId % 2 == 0) {
                            // Writer: syncAll with varying data
                            Map<String, AnalyzerRegistryConfig.AnalyzerEntry> newData = new LinkedHashMap<>();
                            for (int k = 0; k < 10; k++) {
                                newData.put("sync-" + threadId + "-" + k,
                                        entry("ID-" + threadId + "-" + k));
                            }
                            config.syncAll(newData);
                        } else {
                            // Reader: findAnalyzerId
                            config.findAnalyzerId("sync-0-" + (i % 10));
                            config.findAnalyzerId("key-" + (i % 50));
                        }
                    }
                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
                    "Executor did not terminate in time");

            // Verify no exceptions were thrown
            for (Future<?> future : futures) {
                assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS),
                        "Thread threw an exception (possible ConcurrentModificationException)");
            }
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

    private AnalyzerRegistryConfig.AnalyzerEntry entry(String id, String name, String protocol) {
        AnalyzerRegistryConfig.AnalyzerEntry entry = new AnalyzerRegistryConfig.AnalyzerEntry();
        entry.setId(id);
        entry.setName(name);
        entry.setExpectedProtocol(protocol);
        return entry;
    }
}
