package org.itech.ahb.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FileWatcher}.
 * <p>
 * Focus areas:
 * <ul>
 *   <li>File filtering ({@code shouldProcessFile})</li>
 *   <li>Content hashing determinism</li>
 *   <li>Analyzer ID derivation from file path</li>
 *   <li>Non-destructive retry / success / failure contract backed by
 *       {@link SqliteFileStateStore} — the bridge must NEVER delete or move
 *       files from the watched directory under any outcome</li>
 * </ul>
 */
class FileWatcherTest {

    @TempDir
    Path tempDir;

    private Path watchDir;
    private Path stateStorePath;

    private FileConfig fileConfig;
    private FileMessageHandler mockMessageHandler;
    private FileWatcher fileWatcher;
    private SqliteFileStateStore stateStore;

    private static final String CSV_CONTENT = """
            SampleID,TestCode,Result,Units
            12345,GLU,95,mg/dL
            12346,HBA1C,6.5,%
            """;

    private static final String HL7_CONTENT = """
            MSH|^~\\&|SendingApp|SendingFac|||20260205||ORU^R01|MSG001|P|2.5
            PID|1||12345||Doe^John||19800101|M
            OBR|1||ORD001|GLU^Glucose
            OBX|1|NM|GLU^Glucose||95|mg/dL||||F
            """;

    @BeforeEach
    void setUp() throws IOException {
        watchDir = tempDir.resolve("watch");
        stateStorePath = tempDir.resolve("state.db");
        Files.createDirectories(watchDir);

        fileConfig = new FileConfig();
        fileConfig.setEnabled(true);
        fileConfig.setWatchDirectories(List.of(watchDir.toString()));
        fileConfig.setStateStorePath(stateStorePath.toString());
        fileConfig.setFileStabilityTimeoutMs(100);
        fileConfig.setPollIntervalMs(100);
        fileConfig.setMaxRetryAttempts(3);
        fileConfig.setRetryDelayMs(100);
        fileConfig.setFilePatterns(List.of("*.csv", "*.hl7", "*.txt"));

        mockMessageHandler = mock(FileMessageHandler.class);
        fileWatcher = new FileWatcher(fileConfig, mockMessageHandler);

        // Inject a state store so the unit tests can call processFileWithRetry
        // directly without going through the full FileWatcher lifecycle.
        stateStore = new SqliteFileStateStore(stateStorePath);
        ReflectionTestUtils.setField(fileWatcher, "stateStore", stateStore);
    }

    @AfterEach
    void tearDown() {
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        if (stateStore != null) {
            stateStore.close();
        }
    }

    // ------------------------------------------------------------------
    // File filtering
    // ------------------------------------------------------------------

    @Test
    void shouldProcessFile_validCsv() throws IOException {
        Path csvFile = watchDir.resolve("test.csv");
        Files.writeString(csvFile, CSV_CONTENT);
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", csvFile);
        assertTrue(result);
    }

    @Test
    void shouldProcessFile_validHl7() throws IOException {
        Path hl7File = watchDir.resolve("test.hl7");
        Files.writeString(hl7File, HL7_CONTENT);
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", hl7File);
        assertTrue(result);
    }

    @Test
    void shouldProcessFile_invalidExtension() {
        Path invalidFile = watchDir.resolve("test.pdf");
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", invalidFile);
        assertFalse(result);
    }

    @Test
    void shouldProcessFile_hiddenFile() {
        Path hiddenFile = watchDir.resolve(".hidden.csv");
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", hiddenFile);
        assertFalse(result);
    }

    @Test
    void shouldProcessFile_legacyErrorSidecar_stillSkipped() {
        // The bridge no longer writes .error sidecars, but legacy ones from
        // a previous destructive bridge version may still exist in the mount.
        // shouldProcessFile must continue to skip them for backward compat.
        Path errorFile = watchDir.resolve("test.csv.error");
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", errorFile);
        assertFalse(result);
    }

    // ------------------------------------------------------------------
    // Hashing
    // ------------------------------------------------------------------

    @Test
    void calculateFileHash_isDeterministic() throws IOException {
        Path testFile = watchDir.resolve("test.csv");
        Files.writeString(testFile, CSV_CONTENT);
        String hash1 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);
        String hash2 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);
        assertNotNull(hash1);
        assertEquals(64, hash1.length());  // SHA-256 hex
        assertEquals(hash1, hash2);
    }

    @Test
    void calculateFileHash_differentContentDifferentHash() throws IOException {
        Path file1 = watchDir.resolve("test1.csv");
        Path file2 = watchDir.resolve("test2.csv");
        Files.writeString(file1, CSV_CONTENT);
        Files.writeString(file2, HL7_CONTENT);
        String hash1 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", file1);
        String hash2 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", file2);
        assertNotEquals(hash1, hash2);
    }

    // ------------------------------------------------------------------
    // Analyzer ID derivation
    // ------------------------------------------------------------------

    @Test
    void determineAnalyzerId_fromParentDirName() {
        Path fileInSubdir = tempDir.resolve("quantstudio/results.csv");
        String analyzerId = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", fileInSubdir);
        assertEquals("QUANTSTUDIO", analyzerId);
    }

    @Test
    void determineAnalyzerId_noParent() {
        Path rootFile = Path.of("test.csv");
        String analyzerId = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", rootFile);
        assertNull(analyzerId);
    }

    // ------------------------------------------------------------------
    // Non-destructive success / retry / failure contract
    // ------------------------------------------------------------------

    @Test
    void processFileWithRetry_successfulParse_fileStaysInPlaceAndStateIsProcessed() throws Exception {
        Path testFile = tempDir.resolve("quantstudio/results.csv");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, CSV_CONTENT);

        when(mockMessageHandler.processFile(any(), any())).thenReturn(null);

        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);

        verify(mockMessageHandler, times(1)).processFile(any(), any());
        // CORE INVARIANT: the file remains in the watched directory
        assertTrue(Files.exists(testFile),
                "File must remain in the watched directory after successful processing — bridge is read-only");
        // No legacy sidecars were written
        assertFalse(Files.exists(testFile.resolveSibling("results.csv.error")));
        assertFalse(Files.exists(testFile.resolveSibling("results.csv.failed")));

        // State store has a PROCESSED row for this content
        String hash = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);
        Optional<FileProcessingState> row = stateStore.get("QUANTSTUDIO", hash);
        assertTrue(row.isPresent());
        assertEquals(FileProcessingState.Status.PROCESSED, row.get().status());
    }

    @Test
    void processFileWithRetry_reObservation_isIdempotentAndSkipsReprocessing() throws Exception {
        Path testFile = tempDir.resolve("quantstudio/results.csv");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, CSV_CONTENT);

        when(mockMessageHandler.processFile(any(), any())).thenReturn(null);

        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);

        // processFile called exactly ONCE despite three observations
        verify(mockMessageHandler, times(1)).processFile(any(), any());
        assertTrue(Files.exists(testFile));
    }

    @Test
    void processFileWithRetry_correctedContent_processesAsNewRow() throws Exception {
        Path testFile = tempDir.resolve("quantstudio/results.csv");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, CSV_CONTENT);

        when(mockMessageHandler.processFile(any(), any())).thenReturn(null);

        // First observation — hash A
        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        String hashA = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);

        // Overwrite with different content — hash B
        Files.writeString(testFile, HL7_CONTENT);
        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        String hashB = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);

        assertNotEquals(hashA, hashB);
        verify(mockMessageHandler, times(2)).processFile(any(), any());
        // Both rows exist in the state store
        assertTrue(stateStore.get("QUANTSTUDIO", hashA).isPresent());
        assertTrue(stateStore.get("QUANTSTUDIO", hashB).isPresent());
        assertEquals(FileProcessingState.Status.PROCESSED,
                stateStore.get("QUANTSTUDIO", hashA).orElseThrow().status());
        assertEquals(FileProcessingState.Status.PROCESSED,
                stateStore.get("QUANTSTUDIO", hashB).orElseThrow().status());
        assertTrue(Files.exists(testFile));
    }

    @Test
    void processFileWithRetry_eventualSuccessAcrossRetries_fileStaysInPlace() throws Exception {
        Path testFile = tempDir.resolve("quantstudio/flaky.csv");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, CSV_CONTENT);

        when(mockMessageHandler.processFile(any(), any()))
                .thenThrow(new FileMessageHandler.FileProcessingException("Attempt 1 failed"))
                .thenThrow(new FileMessageHandler.FileProcessingException("Attempt 2 failed"))
                .thenReturn(null);

        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        // Retries are scheduled on stabilityChecker; wait for them
        TimeUnit.MILLISECONDS.sleep(800);

        verify(mockMessageHandler, times(3)).processFile(any(), any());
        // CORE INVARIANT
        assertTrue(Files.exists(testFile), "file must remain in watched dir after eventual success");
        assertFalse(Files.exists(testFile.resolveSibling("flaky.csv.error")));
        assertFalse(Files.exists(testFile.resolveSibling("flaky.csv.failed")));

        String hash = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);
        Optional<FileProcessingState> row = stateStore.get("QUANTSTUDIO", hash);
        assertTrue(row.isPresent());
        assertEquals(FileProcessingState.Status.PROCESSED, row.get().status());
    }

    @Test
    void processFileWithRetry_maxRetriesExceeded_fileStaysInPlaceAndStateIsFailedNeedsHandling() throws Exception {
        Path testFile = tempDir.resolve("quantstudio/doomed.csv");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, CSV_CONTENT);

        when(mockMessageHandler.processFile(any(), any()))
                .thenThrow(new FileMessageHandler.FileProcessingException("Processing failed"));

        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        // Retry at 100ms + 200ms backoff — wait long enough for all 3 attempts
        TimeUnit.MILLISECONDS.sleep(1000);

        verify(mockMessageHandler, times(3)).processFile(any(), any());
        // CORE INVARIANT: file stays in place even after exhausted retries
        assertTrue(Files.exists(testFile),
                "file must remain in watched dir even after FAILED_NEEDS_HANDLING");
        // No legacy sidecars
        assertFalse(Files.exists(testFile.resolveSibling("doomed.csv.error")));
        assertFalse(Files.exists(testFile.resolveSibling("doomed.csv.failed")));

        String hash = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);
        Optional<FileProcessingState> row = stateStore.get("QUANTSTUDIO", hash);
        assertTrue(row.isPresent());
        assertEquals(FileProcessingState.Status.FAILED_NEEDS_HANDLING, row.get().status());
        assertNotNull(row.get().lastError());
        assertTrue(row.get().lastError().contains("Processing failed"));
    }

    // ------------------------------------------------------------------
    // Runtime directory registration
    // ------------------------------------------------------------------

    @Test
    void runtimeDirectoryRegistrationLifecycle() throws Exception {
        fileWatcher.start();
        Path dynamicDir = tempDir.resolve("dynamic-watch");
        Files.createDirectories(dynamicDir);

        fileWatcher.addWatchDirectory(dynamicDir, "*.csv", "ANALYZER-123");
        int removed = fileWatcher.removeWatchDirectoriesByAnalyzerId("ANALYZER-123");

        assertEquals(1, removed);
    }

    @Test
    void determineAnalyzerId_usesRuntimeDirectoryMapping() throws Exception {
        fileWatcher.start();
        Path dynamicDir = tempDir.resolve("mapped-watch");
        Files.createDirectories(dynamicDir);
        Path payload = dynamicDir.resolve("input.csv");
        Files.writeString(payload, CSV_CONTENT);

        fileWatcher.addWatchDirectory(dynamicDir, "*.csv", "ANALYZER-MAPPED");

        String analyzerId = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", payload);
        assertEquals("ANALYZER-MAPPED", analyzerId);
    }

    // ------------------------------------------------------------------
    // Multi-observer routing (Madagascar Fluorocycler XT fix)
    //
    // These tests exercise the invariant that multiple analyzer instances
    // can watch the same physical directory as long as their filePattern
    // values distinguish the files they each claim. Without the A.3.7
    // refactor, the second addWatchDirectory call silently replaces the
    // first, breaking Herbert's one-directory-two-assays workflow.
    // ------------------------------------------------------------------

    @Test
    void multipleObservers_oneDirectory_routeByGlob() throws Exception {
        fileWatcher.start();
        Path sharedDir = tempDir.resolve("fluorocycler-shared");
        Files.createDirectories(sharedDir);

        fileWatcher.addWatchDirectory(sharedDir, "HIV*.csv", "FLUOROCYCLER-HIV");
        fileWatcher.addWatchDirectory(sharedDir, "ARBO*.csv", "FLUOROCYCLER-ARBO");

        Path hivFile = sharedDir.resolve("HIV-result.csv");
        Path arboFile = sharedDir.resolve("ARBO-result.csv");
        Path unrelatedFile = sharedDir.resolve("noise.csv");
        Files.writeString(hivFile, CSV_CONTENT);
        Files.writeString(arboFile, CSV_CONTENT);
        Files.writeString(unrelatedFile, CSV_CONTENT);

        assertEquals("FLUOROCYCLER-HIV",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", hivFile));
        assertEquals("FLUOROCYCLER-ARBO",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", arboFile));
        // A file matching neither registered glob falls back to the
        // parent-directory-name heuristic (last resort in determineAnalyzerId).
        assertEquals("FLUOROCYCLER-SHARED",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", unrelatedFile));
    }

    @Test
    void multipleObservers_shouldProcessFile_returnsTrueForAnyMatchingGlob() throws Exception {
        fileWatcher.start();
        Path sharedDir = tempDir.resolve("fluorocycler-shared");
        Files.createDirectories(sharedDir);

        fileWatcher.addWatchDirectory(sharedDir, "HIV*.csv", "FLUOROCYCLER-HIV");
        fileWatcher.addWatchDirectory(sharedDir, "ARBO*.csv", "FLUOROCYCLER-ARBO");

        Path hivFile = sharedDir.resolve("HIV-result.csv");
        Path arboFile = sharedDir.resolve("ARBO-result.csv");
        Path mismatchFile = sharedDir.resolve("mismatch.txt");
        Files.writeString(hivFile, CSV_CONTENT);
        Files.writeString(arboFile, CSV_CONTENT);
        Files.writeString(mismatchFile, "ignored");

        assertTrue((boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", hivFile));
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", arboFile));
        // A directory with per-registration globs is opinionated: files
        // matching no glob are rejected even if they match fileConfig's
        // legacy filePatterns list.
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", mismatchFile));
    }

    @Test
    void removeWatchRegistration_leavesOtherAnalyzersAtSameDirAlive() throws Exception {
        fileWatcher.start();
        Path sharedDir = tempDir.resolve("fluorocycler-shared");
        Files.createDirectories(sharedDir);

        fileWatcher.addWatchDirectory(sharedDir, "HIV*.csv", "FLUOROCYCLER-HIV");
        fileWatcher.addWatchDirectory(sharedDir, "ARBO*.csv", "FLUOROCYCLER-ARBO");

        boolean removed = fileWatcher.removeWatchRegistration(sharedDir, "FLUOROCYCLER-HIV");
        assertTrue(removed);

        // Arbo registration survives:
        Path arboFile = sharedDir.resolve("ARBO-result.csv");
        Files.writeString(arboFile, CSV_CONTENT);
        assertEquals("FLUOROCYCLER-ARBO",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", arboFile));

        // HIV registration is gone — HIV file falls through to the
        // directory-name fallback since no glob matches.
        Path hivFile = sharedDir.resolve("HIV-result.csv");
        Files.writeString(hivFile, CSV_CONTENT);
        // HIV*.csv no longer matches, ARBO*.csv doesn't match HIV-result.csv
        // either, so determineAnalyzerId falls back to the dir name.
        assertEquals("FLUOROCYCLER-SHARED",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", hivFile));
    }

    @Test
    void removeWatchRegistration_returnsFalseForUnknownAnalyzer() throws Exception {
        fileWatcher.start();
        Path sharedDir = tempDir.resolve("fluorocycler-shared");
        Files.createDirectories(sharedDir);

        fileWatcher.addWatchDirectory(sharedDir, "HIV*.csv", "FLUOROCYCLER-HIV");

        assertFalse(fileWatcher.removeWatchRegistration(sharedDir, "NEVER-REGISTERED"));
        // The HIV registration is still alive:
        Path hivFile = sharedDir.resolve("HIV-result.csv");
        Files.writeString(hivFile, CSV_CONTENT);
        assertEquals("FLUOROCYCLER-HIV",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", hivFile));
    }

    @Test
    void reregisterSameAnalyzer_replacesRegistrationNotAppends() throws Exception {
        fileWatcher.start();
        Path sharedDir = tempDir.resolve("fluorocycler-shared");
        Files.createDirectories(sharedDir);

        // Register initially with HIV*.csv
        fileWatcher.addWatchDirectory(sharedDir, "HIV*.csv", "FLUOROCYCLER-HIV");

        // Re-register the SAME analyzer with a different pattern — should
        // replace, not append. Without this behavior we'd double-process
        // files under one analyzer id.
        fileWatcher.addWatchDirectory(sharedDir, "HIVVL*.csv", "FLUOROCYCLER-HIV");

        // New pattern matches:
        Path newPatternFile = sharedDir.resolve("HIVVL-2026.csv");
        Files.writeString(newPatternFile, CSV_CONTENT);
        assertEquals("FLUOROCYCLER-HIV",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", newPatternFile));

        // Old-pattern-only file no longer matches (the registration was
        // replaced, not augmented):
        Path oldPatternFile = sharedDir.resolve("HIV-only-legacy.csv");
        Files.writeString(oldPatternFile, CSV_CONTENT);
        // HIV*.csv no longer registered, HIVVL*.csv doesn't match
        // HIV-only-legacy.csv, so it falls back to the dir name.
        assertEquals("FLUOROCYCLER-SHARED",
                ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", oldPatternFile));

        // Only ONE registration exists (the replacement, not an additional one)
        int removed = fileWatcher.removeWatchDirectoriesByAnalyzerId("FLUOROCYCLER-HIV");
        assertEquals(1, removed);
    }
}
