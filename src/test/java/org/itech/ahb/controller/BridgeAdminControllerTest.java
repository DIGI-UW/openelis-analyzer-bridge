package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.file.FileStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class BridgeAdminControllerTest {

    @TempDir
    Path watchDirectory;

    @Test
    void resetCleansTheActualDirectoryForAConnectionScopedFileRegistryKey() throws Exception {
        Path staleResult = Files.writeString(watchDirectory.resolve("stale-result.xlsx"), "fixture");
        AnalyzerEntry analyzer = new AnalyzerEntry();
        analyzer.setId("5");
        analyzer.setBridgeConnectionId("31a62ad1-2356-4b4d-be21-b479f57a3dbf");
        analyzer.setExpectedProtocol("FILE");

        AnalyzerRuntimeRegistry registry = mock(AnalyzerRuntimeRegistry.class);
        when(registry.getRegisteredAnalyzers())
                .thenReturn(Map.of(watchDirectory + "#31a62ad1-2356-4b4d-be21-b479f57a3dbf", analyzer));
        FileStateStore stateStore = mock(FileStateStore.class);
        when(stateStore.deleteAllForAnalyzer("5")).thenReturn(1);

        var response = new BridgeAdminController(registry, stateStore).reset("5");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().get("stateRowsRemoved"));
        assertEquals(1, response.getBody().get("filesRemoved"));
        assertEquals(List.of(watchDirectory.toString()), response.getBody().get("watchDirectories"));
        assertFalse(Files.exists(staleResult));
    }
}
