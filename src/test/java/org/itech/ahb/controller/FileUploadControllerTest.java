package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.FileNameSelfDeclarationScanner;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.file.FileMessageHandler.FileProcessingException;
import org.itech.ahb.file.FileStateStore;
import org.itech.ahb.file.FileWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Unit tests for {@link FileUploadController}'s state-store interactions.
 *
 * <p>Uses hand-wired Mockito mocks rather than {@link
 * org.springframework.boot.test.context.SpringBootTest}: the full web context
 * isn't needed to verify the state-machine transitions, and the direct-call
 * style makes the ordering assertions (via {@link InOrder}) more precise.
 *
 * <p><b>Gap analysis:</b> before these tests landed, {@code
 * FileUploadController} had zero controller-level test coverage. The bug
 * Copilot flagged (pre-marking a row as {@code PROCESSED} before writing the
 * file, so that a {@code processFile} failure leaves the row permanently
 * PROCESSED and FileWatcher skips the file forever) was invisible because no
 * test ever exercised the failure paths. This class fills that gap.
 */
@DisplayName("FileUploadController state-store contract")
class FileUploadControllerTest {

    private static final String ANALYZER_ID = "qs5-arbo";
    private static final String TEST_CODE = "ARBO";
    private static final String FILENAME = "results.xlsx";

    @TempDir
    Path tempDir;

    private AnalyzerRuntimeRegistry registry;
    private FileMessageHandler fileMessageHandler;
    private FileNameSelfDeclarationScanner scanner;
    private FileWatcher fileWatcher;
    private FileStateStore stateStore;

    private FileUploadController controller;

    @BeforeEach
    void setUp() {
        registry = org.mockito.Mockito.mock(AnalyzerRuntimeRegistry.class);
        fileMessageHandler = org.mockito.Mockito.mock(FileMessageHandler.class);
        scanner = org.mockito.Mockito.mock(FileNameSelfDeclarationScanner.class);
        fileWatcher = org.mockito.Mockito.mock(FileWatcher.class);
        stateStore = org.mockito.Mockito.mock(FileStateStore.class);

        // Seed a minimal registry entry so path resolution succeeds.
        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setId(ANALYZER_ID);
        entry.setName("QuantStudio 5 Arbo");
        entry.setExpectedProtocol("FILE");
        entry.setMappedTestCodes(Set.of(TEST_CODE));

        Map<String, AnalyzerEntry> registered = new LinkedHashMap<>();
        registered.put(tempDir.toString(), entry);
        when(registry.getRegisteredAnalyzers()).thenReturn(registered);
        when(fileWatcher.getStateStore()).thenReturn(stateStore);

        controller = new FileUploadController(registry, fileMessageHandler, scanner, fileWatcher);
    }

    private MockMultipartFile multipart(String content) {
        return new MockMultipartFile("file", FILENAME,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content.getBytes());
    }

    @Nested
    @DisplayName("Happy path — successful upload + process")
    class HappyPath {

        @Test
        @DisplayName("Pre-registers RETRYING + lease, then calls processFile, then marks PROCESSED")
        void successfulUpload_transitionsRetryingThenProcessed() throws Exception {
            MockMultipartFile file = multipart("accession,result\nA1,5.0\n");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // processFile returns normally (no exception)

            controller.uploadFile(ANALYZER_ID, TEST_CODE, file, response);

            InOrder order = inOrder(stateStore, fileMessageHandler);
            // 1. Pre-mark: RETRYING with a future next_attempt_at (the upload lease)
            order.verify(stateStore).upsertRetrying(eq(ANALYZER_ID), anyString(), any(Path.class));
            ArgumentCaptor<Instant> leaseCap = ArgumentCaptor.forClass(Instant.class);
            order.verify(stateStore).setNextAttemptAt(eq(ANALYZER_ID), anyString(), leaseCap.capture());
            assertNotNull(leaseCap.getValue(),
                    "upload lease must be a concrete future Instant, not null");
            assertTrue(leaseCap.getValue().isAfter(Instant.now().minusSeconds(5)),
                    "lease must be at or after now");
            assertTrue(leaseCap.getValue().isBefore(Instant.now().plusSeconds(3600)),
                    "lease must be bounded (< 1h) to avoid indefinite ownership");

            // 2. processFile with the admin's declared test code
            order.verify(fileMessageHandler).processFile(any(Path.class), eq(ANALYZER_ID),
                    eq(TEST_CODE), any(FileMessageHandler.ProgressCallback.class));

            // 3. markProcessed — clears the lease and promotes to PROCESSED
            order.verify(stateStore).markProcessed(eq(ANALYZER_ID), anyString(), any(Path.class));

            // Never FAILED_NEEDS_HANDLING on happy path
            verify(stateStore, never()).markFailedNeedsHandling(
                    anyString(), anyString(), any(Path.class), anyString());
        }

        @Test
        @DisplayName("Null testCode is passed through to processFile (per-row test labels path)")
        void nullTestCode_passesThroughToProcessFile() throws Exception {
            MockMultipartFile file = multipart("x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            controller.uploadFile(ANALYZER_ID, null, file, response);

            verify(fileMessageHandler).processFile(any(Path.class), eq(ANALYZER_ID),
                    eq(null), any(FileMessageHandler.ProgressCallback.class));
        }
    }

    @Nested
    @DisplayName("processFile failure — hand off to FileWatcher retry loop, do not mark PROCESSED")
    class ProcessFileFailure {

        @Test
        @DisplayName("FileProcessingException clears the lease so FileWatcher picks up the file")
        void fileProcessingException_clearsLeaseAndDoesNotMarkProcessed() throws Exception {
            MockMultipartFile file = multipart("x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            doThrow(new FileProcessingException("parse error: missing header row"))
                    .when(fileMessageHandler).processFile(any(Path.class), anyString(),
                            any(), any(FileMessageHandler.ProgressCallback.class));

            controller.uploadFile(ANALYZER_ID, TEST_CODE, file, response);

            // Pre-mark happened (ownership claimed)
            verify(stateStore).upsertRetrying(eq(ANALYZER_ID), anyString(), any(Path.class));

            // CRITICAL: row must NOT be marked PROCESSED on failure —
            // that was the original bug. A PROCESSED row means FileWatcher
            // skips the file forever, silent data loss.
            verify(stateStore, never()).markProcessed(anyString(), anyString(), any(Path.class));

            // Lease cleared (null) so FileWatcher's next polling cycle picks
            // up the file via its existing retry infrastructure.
            ArgumentCaptor<Instant> leaseCap = ArgumentCaptor.forClass(Instant.class);
            verify(stateStore, org.mockito.Mockito.atLeastOnce())
                    .setNextAttemptAt(eq(ANALYZER_ID), anyString(), leaseCap.capture());
            // Last value passed must be null (the failure-path clear)
            assertEquals(null, leaseCap.getAllValues().get(leaseCap.getAllValues().size() - 1),
                    "last setNextAttemptAt must be null — failure must clear the lease to release "
                            + "the file back to FileWatcher");
        }

        @Test
        @DisplayName("IOException from processFile also clears the lease")
        void ioException_clearsLeaseAndDoesNotMarkProcessed() throws Exception {
            MockMultipartFile file = multipart("x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            doThrow(new IOException("disk full mid-process"))
                    .when(fileMessageHandler).processFile(any(Path.class), anyString(),
                            any(), any(FileMessageHandler.ProgressCallback.class));

            controller.uploadFile(ANALYZER_ID, TEST_CODE, file, response);

            verify(stateStore, never()).markProcessed(anyString(), anyString(), any(Path.class));

            ArgumentCaptor<Instant> leaseCap = ArgumentCaptor.forClass(Instant.class);
            verify(stateStore, org.mockito.Mockito.atLeastOnce())
                    .setNextAttemptAt(eq(ANALYZER_ID), anyString(), leaseCap.capture());
            assertEquals(null, leaseCap.getAllValues().get(leaseCap.getAllValues().size() - 1));
        }
    }

    @Nested
    @DisplayName("Pre-mark failure — refuse upload rather than risk FileWatcher race")
    class PreMarkFailure {

        @Test
        @DisplayName("upsertRetrying throws → 500, file not written, processFile not called")
        void preMarkThrows_refusesUpload() throws Exception {
            MockMultipartFile file = multipart("x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            doThrow(new IllegalStateException("state store unavailable"))
                    .when(stateStore).upsertRetrying(anyString(), anyString(), any(Path.class));

            controller.uploadFile(ANALYZER_ID, TEST_CODE, file, response);

            // Upload refused with 500 — the controller cannot claim the row,
            // so proceeding would risk FileWatcher racing concurrently.
            // uploadFile returns void and writes status + HTML directly to the response.
            assertEquals(500, response.getStatus(),
                    "pre-mark failure must write HTTP 500 to the response");

            // processFile must NOT have been called (file not claimed → don't process)
            verify(fileMessageHandler, never()).processFile(any(Path.class), anyString(),
                    any(), any(FileMessageHandler.ProgressCallback.class));

            // And we never reached markProcessed
            verify(stateStore, never()).markProcessed(anyString(), anyString(), any(Path.class));
        }
    }

    @Nested
    @DisplayName("Contract invariants independent of outcome")
    class ContractInvariants {

        @Test
        @DisplayName("Pre-mark NEVER uses markProcessed (the original bug) — always upsertRetrying")
        void preMark_isRetryingNotProcessed() throws Exception {
            // This test is the direct regression guard for the Copilot finding.
            // If someone reverts to markProcessed-as-pre-mark, this test fails
            // loudly: happy-path would then show markProcessed called TWICE
            // (once as pre-mark, once as post-success), and the pre-mark call
            // would happen before processFile instead of after.
            MockMultipartFile file = multipart("x");
            MockHttpServletResponse response = new MockHttpServletResponse();

            controller.uploadFile(ANALYZER_ID, TEST_CODE, file, response);

            InOrder order = inOrder(stateStore, fileMessageHandler);
            order.verify(stateStore).upsertRetrying(anyString(), anyString(), any(Path.class));
            order.verify(fileMessageHandler).processFile(any(Path.class), anyString(),
                    any(), any(FileMessageHandler.ProgressCallback.class));
            order.verify(stateStore).markProcessed(anyString(), anyString(), any(Path.class));

            // Exactly one markProcessed call, and it happened AFTER processFile.
            verify(stateStore, org.mockito.Mockito.times(1))
                    .markProcessed(anyString(), anyString(), any(Path.class));
        }

        @Test
        @DisplayName("Content hash passed to pre-mark and markProcessed is identical")
        void contentHash_consistentAcrossTransitions() throws Exception {
            MockMultipartFile file = multipart("distinctive content for hashing");
            MockHttpServletResponse response = new MockHttpServletResponse();

            controller.uploadFile(ANALYZER_ID, TEST_CODE, file, response);

            ArgumentCaptor<String> retryHash = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> processedHash = ArgumentCaptor.forClass(String.class);
            verify(stateStore).upsertRetrying(anyString(), retryHash.capture(), any(Path.class));
            verify(stateStore).markProcessed(anyString(), processedHash.capture(), any(Path.class));

            assertEquals(retryHash.getValue(), processedHash.getValue(),
                    "pre-mark and post-mark must hash the same bytes; any divergence means "
                            + "FileWatcher can't correlate the two rows");
            assertNotEquals("", retryHash.getValue(), "hash must be non-empty");
        }
    }
}
