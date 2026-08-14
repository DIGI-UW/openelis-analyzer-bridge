package org.itech.ahb.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.profile.PortableProfileCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("OGC-1054 versioned registration reconciliation")
class RegistrationReconciliationServiceTest {

  private static final Path FIXTURES = Path.of("contracts", "analyzer", "v1", "fixtures");
  private static final Path QUANTSTUDIO = Path.of(
    "src",
    "main",
    "resources",
    "analyzer-profiles",
    "file",
    "quantstudio.json"
  );

  @TempDir
  Path catalogDirectory;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private AnalyzerRegistryConfig registry;
  private RegistrationReconciliationService service;

  @BeforeEach
  void setUp() throws Exception {
    PortableProfileCatalog catalog = new PortableProfileCatalog(
      catalogDirectory,
      List.of(),
      objectMapper,
      Clock.systemUTC()
    );
    catalog.createSite(read("portable-profile.json"), "profile-admin");

    ObjectNode quantstudio = (ObjectNode) objectMapper.readTree(QUANTSTUDIO.toFile());
    quantstudio.put("profileId", "site.quantstudio-hiv");
    quantstudio.put("displayName", "Site QuantStudio HIV");
    catalog.createSite(quantstudio, "profile-admin");
    catalog.revise("site.quantstudio-hiv", quantstudio, "profile-admin");

    registry = new AnalyzerRegistryConfig();
    service = new RegistrationReconciliationService(
      catalog,
      registry,
      mock(FileWatcher.class),
      mock(FileConfig.class),
      objectMapper
    );
  }

  @Test
  @DisplayName("exact profile revisions derive runtime configuration and replay unchanged")
  void exactProfileRevisionDrivesIdempotentRuntimeConfiguration() throws Exception {
    RegistrationSyncResult first = service.reconcile(read("registration-initial.json"));

    assertEquals("sha256:registration-initial", first.appliedStateRevision());
    assertEquals(new RegistrationSyncResult.Counts(1, 1, 0, 0, 0, 0), first.counts());
    assertEquals(RegistrationSyncResult.Status.APPLIED, first.registrations().get(0).status());
    assertEquals(List.of(), first.errors());

    AnalyzerEntry entry = registry.getRegisteredAnalyzers().get("10.20.30.40");
    assertEquals("site.mock-hematology", entry.getProfileId());
    assertEquals(1, entry.getProfileRevision());
    assertEquals("binding:42:3", entry.getSiteBindingRevision());
    assertEquals("qc:42:0", entry.getOperationalQcContextRevision());
    assertFalse(entry.isOperationalQcReady());
    assertTrue(entry.isActive());
    assertEquals("OPENELIS.*MOCK.?HEMATOLOGY", entry.getIdentifierPattern());
    assertEquals(Set.of("WBC", "WBC#", "HIV-INTERP"), entry.getMappedTestCodes());
    assertEquals("6690-2", entry.getLoincForCode("WBC"));
    assertEquals("6690-2", entry.getLoincForCode("WBC#"));
    assertEquals("SPECIMEN_ID_PREFIX", entry.getQcRules().get(0).ruleType());

    RegistrationSyncResult replay = service.reconcile(read("registration-initial.json"));
    assertEquals(new RegistrationSyncResult.Counts(1, 0, 0, 0, 1, 0), replay.counts());
    assertEquals(RegistrationSyncResult.Status.UNCHANGED, replay.registrations().get(0).status());
  }

  @Test
  @DisplayName("new desired state updates QC context and retains inactive registrations without routing them")
  void nextDesiredStateUpdatesAndAddsInactiveRegistration() throws Exception {
    service.reconcile(read("registration-initial.json"));
    RegistrationSyncResult next = service.reconcile(read("registration-next.json"));

    assertEquals(new RegistrationSyncResult.Counts(2, 1, 1, 0, 0, 0), next.counts());
    assertEquals(
      List.of(RegistrationSyncResult.Status.APPLIED, RegistrationSyncResult.Status.APPLIED),
      next.registrations().stream().map(RegistrationSyncResult.Registration::status).toList()
    );

    AnalyzerEntry hematology = registry.getRegisteredAnalyzers().get("10.20.30.40");
    assertEquals("qc:42:2", hematology.getOperationalQcContextRevision());
    assertTrue(hematology.isOperationalQcReady());
    assertEquals(Set.of("oe-qc-rule-7"), hematology.getActiveOperationalQcRuleIds());
    assertEquals("LOT-WBC-2026-08", hematology.getControlLots().get(0).lotNumber());
    assertEquals("WBC", hematology.getControlLots().get(0).analyzerCode());

    AnalyzerEntry quantstudio = registry.getRegisteredAnalyzers().get("/mnt/analyzer-import/quantstudio-43");
    assertFalse(quantstudio.isActive());
    assertEquals("site.quantstudio-hiv", quantstudio.getProfileId());
    assertEquals(2, quantstudio.getProfileRevision());
    assertEquals("20447-9", quantstudio.getLoincForCode("VIH-1"));
    assertTrue(registry.findAnalyzerId("/mnt/analyzer-import/quantstudio-43").isEmpty());

    RegistrationSyncResult replay = service.reconcile(read("registration-next.json"));
    assertEquals(new RegistrationSyncResult.Counts(2, 0, 0, 0, 2, 0), replay.counts());
  }

  @Test
  @DisplayName("unknown or incompatible profile references are rejected explicitly")
  void invalidProfileReferencesAreRejected() throws Exception {
    ObjectNode missing = (ObjectNode) read("registration-initial.json");
    ((ObjectNode) missing.path("analyzers").get(0).path("profileRef")).put("profileId", "missing.profile");

    RegistrationSyncResult missingResult = service.reconcile(missing);
    assertEquals(new RegistrationSyncResult.Counts(1, 0, 0, 0, 0, 1), missingResult.counts());
    assertEquals(RegistrationSyncResult.Status.REJECTED, missingResult.registrations().get(0).status());
    assertTrue(missingResult.registrations().get(0).message().contains("not found"));
    assertEquals(List.of(), registry.getRegisteredAnalyzers().values().stream().toList());

    ObjectNode mismatch = (ObjectNode) read("registration-initial.json");
    ((ObjectNode) mismatch.path("analyzers").get(0)).put("protocol", "HL7");
    RegistrationSyncResult mismatchResult = service.reconcile(mismatch);
    assertEquals(1, mismatchResult.counts().rejected());
    assertTrue(mismatchResult.registrations().get(0).message().contains("protocol"));
  }

  @Test
  @DisplayName("schema-invalid desired state is rejected before registry mutation")
  void invalidEnvelopeDoesNotMutateRegistry() throws Exception {
    service.reconcile(read("registration-initial.json"));
    ObjectNode invalid = (ObjectNode) read("registration-next.json");
    invalid.remove("desiredStateRevision");

    assertThrows(RegistrationSyncException.class, () -> service.reconcile(invalid));
    assertEquals(Set.of("10.20.30.40"), registry.getRegisteredAnalyzers().keySet());
  }

  private JsonNode read(String fixture) throws Exception {
    return objectMapper.readTree(FIXTURES.resolve(fixture).toFile());
  }
}
