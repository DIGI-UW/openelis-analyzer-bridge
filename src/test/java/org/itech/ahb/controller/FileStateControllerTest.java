package org.itech.ahb.controller;

import org.itech.ahb.file.FileProcessingState;
import org.itech.ahb.file.FileStateStore;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.file.SqliteFileStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link FileStateController}.
 * <p>
 * Verifies both the auth gating (admin endpoints require HTTP Basic) and
 * the JSON shape of the list + single-row responses. Uses a real
 * {@link SqliteFileStateStore} seeded with known rows so we exercise the
 * actual SQL path rather than a Mockito stub.
 * </p>
 */
@SpringBootTest(properties = {
    "bridge.security.enabled=true",
    "bridge.security.username=testuser",
    "bridge.security.password=testpass",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false"
})
@AutoConfigureMockMvc
class FileStateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileWatcher fileWatcher;

    @TempDir
    Path tempDir;

    private SqliteFileStateStore store;

    @BeforeEach
    void setUp() {
        store = new SqliteFileStateStore(tempDir.resolve("state.db"));
        when(fileWatcher.getStateStore()).thenReturn((FileStateStore) store);

        // Seed a couple of rows of known shape
        store.markProcessed("qs5-arbo", "hash-processed-1", tempDir.resolve("a.xls"));
        store.markProcessed("qs5-arbo", "hash-processed-2", tempDir.resolve("b.xls"));
        store.upsertRetrying("qs5-arbo", "hash-retrying", tempDir.resolve("c.xls"));
        store.incrementAttempts("qs5-arbo", "hash-retrying", "transient network blip");
        store.upsertRetrying("qs7-hiv", "hash-failed", tempDir.resolve("d.xls"));
        store.markFailedNeedsHandling("qs7-hiv", "hash-failed", tempDir.resolve("d.xls"),
                "EmptyResults: 0 rows matched testCodeFilter=VIH-1");
    }

    @AfterEach
    void tearDown() {
        // Release the SQLite JDBC connection + file handle so each test cleans
        // up deterministically. Without this, platforms that lock SQLite files
        // (Windows in particular) can see intermittent failures when @TempDir
        // tries to delete the db file while the connection is still open.
        if (store != null) {
            store.close();
        }
    }

    @Nested
    @DisplayName("Authentication gating")
    class AuthTests {

        @Test
        void unauthenticated_list_returns401() throws Exception {
            mockMvc.perform(get("/admin/file-state").param("status", "PROCESSED"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void unauthenticated_single_returns401() throws Exception {
            mockMvc.perform(get("/admin/file-state/qs5-arbo/hash-processed-1"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("List by status")
    class ListTests {

        @Test
        void list_processed_returnsBothProcessedRows() throws Exception {
            mockMvc.perform(get("/admin/file-state")
                            .param("status", "PROCESSED")
                            .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PROCESSED"))
                    .andExpect(jsonPath("$.count").value(2))
                    .andExpect(jsonPath("$.rows[0].status").value("PROCESSED"));
        }

        @Test
        void list_failed_returnsOneRowWithErrorMessage() throws Exception {
            mockMvc.perform(get("/admin/file-state")
                            .param("status", "FAILED_NEEDS_HANDLING")
                            .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(1))
                    .andExpect(jsonPath("$.rows[0].analyzerId").value("qs7-hiv"))
                    .andExpect(jsonPath("$.rows[0].contentHash").value("hash-failed"))
                    .andExpect(jsonPath("$.rows[0].status").value("FAILED_NEEDS_HANDLING"))
                    .andExpect(jsonPath("$.rows[0].lastError").value(
                            "EmptyResults: 0 rows matched testCodeFilter=VIH-1"));
        }

        @Test
        void list_retrying_returnsRowWithAttemptCount() throws Exception {
            mockMvc.perform(get("/admin/file-state")
                            .param("status", "RETRYING")
                            .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(1))
                    .andExpect(jsonPath("$.rows[0].attempts").value(1))
                    .andExpect(jsonPath("$.rows[0].lastError").value("transient network blip"));
        }

        @Test
        void list_invalidStatus_returns400() throws Exception {
            mockMvc.perform(get("/admin/file-state")
                            .param("status", "BOGUS")
                            .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_status"));
        }

        @Test
        void list_respectsLimit() throws Exception {
            mockMvc.perform(get("/admin/file-state")
                            .param("status", "PROCESSED")
                            .param("limit", "1")
                            .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(1))
                    .andExpect(jsonPath("$.limit").value(1));
        }
    }

    @Nested
    @DisplayName("Single-row lookup")
    class SingleTests {

        @Test
        void getOne_present_returnsRow() throws Exception {
            mockMvc.perform(get("/admin/file-state/qs5-arbo/hash-processed-1")
                            .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analyzerId").value("qs5-arbo"))
                    .andExpect(jsonPath("$.contentHash").value("hash-processed-1"))
                    .andExpect(jsonPath("$.status").value("PROCESSED"));
        }

        @Test
        void getOne_missing_returns404() throws Exception {
            mockMvc.perform(get("/admin/file-state/qs5-arbo/nonexistent-hash")
                            .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not_found"));
        }
    }
}
