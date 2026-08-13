package org.itech.ahb.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.controller.AnalyzerRegistrationController;
import org.itech.ahb.controller.AnalyzerRegistrationController.RegistrationRequest;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("OGC-1054 legacy registration compatibility")
class AnalyzerRegistrationCompatibilityContractTest {

  private static final Path FIXTURE = Path.of("contracts", "analyzer", "v1", "fixtures", "legacy-registration.json");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private AnalyzerRegistryConfig registry;
  private AnalyzerRegistrationController controller;

  @BeforeEach
  void setUp() {
    registry = new AnalyzerRegistryConfig();
    controller = new AnalyzerRegistrationController(registry, mock(FileWatcher.class), mock(FileConfig.class));
  }

  @Test
  @DisplayName("replaying the same legacy full-state payload is idempotent")
  void legacyFullStateSyncIsIdempotent() throws Exception {
    List<RegistrationRequest> registrations = objectMapper.readValue(
      FIXTURE.toFile(),
      new TypeReference<List<RegistrationRequest>>() {}
    );

    ResponseEntity<Map<String, Object>> first = controller.sync(registrations);
    ResponseEntity<Map<String, Object>> replay = controller.sync(registrations);

    assertCounts(first, 1, 0, 0);
    assertCounts(replay, 0, 0, 0);

    AnalyzerEntry entry = registry.getRegisteredAnalyzers().get("10.20.30.42");
    assertNotNull(entry);
    assertEquals("6690-2", entry.getLoincForCode("WBC"));
    assertEquals(List.of(), entry.getQcRules());
    assertEquals(List.of(), entry.getControlLots());
  }

  private static void assertCounts(ResponseEntity<Map<String, Object>> response, int added, int updated, int removed) {
    assertEquals(200, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().get("synced"));
    assertEquals(added, response.getBody().get("added"));
    assertEquals(updated, response.getBody().get("updated"));
    assertEquals(removed, response.getBody().get("removed"));
  }
}
