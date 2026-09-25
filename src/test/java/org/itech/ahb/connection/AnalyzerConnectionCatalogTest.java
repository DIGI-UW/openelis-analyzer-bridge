package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.profile.ProfileCatalogProperties;
import org.itech.ahb.profile.ProfileFingerprintService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class AnalyzerConnectionCatalogTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T20:00:00Z"), ZoneOffset.UTC);

  @TempDir
  Path temporaryDirectory;

  private ObjectMapper objectMapper;
  private AnalyzerProfileCatalog profiles;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    ProfileCatalogProperties properties = new ProfileCatalogProperties();
    Resource[] shipped = new PathMatchingResourcePatternResolver().getResources(properties.getShippedPattern());
    profiles = new AnalyzerProfileCatalog(
      temporaryDirectory.resolve("profiles"),
      Arrays.stream(shipped).toList(),
      objectMapper,
      CLOCK
    );
  }

  /** Guards saved-connection readiness and immutable profile content; socket delivery is tested separately. */
  @Test
  void publishedInboundProfileDoesNotRequireAnAnalyzerListenerPort() {
    ObjectNode profile = profiles.require("genexpert-astm", 5).profile();
    ObjectNode original = profile.deepCopy();
    RecordingRuntime runtime = new RecordingRuntime();
    AnalyzerConnectionCatalog catalog = catalog(UUID::randomUUID, runtime);
    ObjectNode created = catalog.create(createRequest(profile, "no-incoming-port", "oe-no-port"));

    assertThat(created.path("readiness").path("ready").asBoolean()).isTrue();
    assertThat(field(created, "port").path("validationErrors")).isEmpty();
    assertThat(field(created, "port").path("visibleWhen").path("fieldKey").asText()).isEqualTo("connectionRole");
    assertThat(field(created, "port").path("visibleWhen").path("value").asText()).isEqualTo("CLIENT");
    catalog.applyRuntimeCommand(runtimeCommand(created, "activate-portless", "ACTIVATE"));
    assertThat(runtime.activations).containsExactly(created.path("connectionId").asText());
    assertThat(profiles.require("genexpert-astm", 5).profile()).isEqualTo(original);
    ObjectNode restored = catalog(UUID::randomUUID).require(created.path("connectionId").asText());
    assertThat(restored.path("readiness").path("ready").asBoolean()).isTrue();
    assertThat(restored.path("profileRef")).isEqualTo(created.path("profileRef"));
  }

  @Test
  void outboundClientPortIsOptionalWhenTheDeploymentCanResolveIt() {
    ObjectNode profile = profiles.require("genexpert-astm", 5).profile();
    AnalyzerConnectionCatalog catalog = catalog(UUID::randomUUID);
    ObjectNode request = createRequest(profile, "optional-outbound-port", "oe-client-default");
    request.withObject("values").put("connectionRole", "CLIENT").put("host", "192.0.2.10");

    ObjectNode created = catalog.create(request);

    assertThat(field(created, "port").path("required").asBoolean()).isFalse();
    assertThat(created.path("readiness").path("ready").asBoolean()).isTrue();
  }

  @Test
  void changingServerToClientDoesNotInheritAnOldIncomingPort() {
    ObjectNode profile = profiles.require("genexpert-astm", 5).profile();
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      mock(AstmConnectionListeners.class),
      mock(SerialConnectionListeners.class)
    );
    AnalyzerConnectionCatalog catalog = catalog(UUID::randomUUID, runtime);
    ObjectNode request = createRequest(profile, "old-listener-role-change", "oe-role-change");
    request.withObject("values").put("connectionRole", "SERVER").put("host", "192.0.2.10").put("port", 1200);
    ObjectNode created = catalog.create(request);
    // The unchanged OE2 form initializes from these values before the user edits the role.
    assertThat(field(created, "port").has("currentValue")).isFalse();
    ObjectNode update = objectMapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("requestId", "switch-to-client")
      .put("connectionId", created.path("connectionId").asText())
      .put("expectedConfigRevision", 1)
      .put("displayName", created.path("displayName").asText());
    update.set("profileRef", created.path("profileRef").deepCopy());
    update.putObject("values").put("connectionRole", "CLIENT");
    ObjectNode changed = catalog.update(update);
    catalog.applyRuntimeCommand(runtimeCommand(changed, "activate-client-default", "ACTIVATE"));

    var entry = registry.findAnalyzerEntryByConnectionId(created.path("connectionId").asText()).orElseThrow();
    assertThat(entry.getOutboundPort()).isEqualTo(12001);
    assertThat(changed.path("profileRef")).isEqualTo(created.path("profileRef"));
    assertThat(field(changed, "port").has("currentValue")).isFalse();
    AnalyzerRuntimeRegistry restoredRegistry = new AnalyzerRuntimeRegistry();
    catalog(
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(
        restoredRegistry,
        null,
        mock(AstmConnectionListeners.class),
        mock(SerialConnectionListeners.class)
      )
    );
    assertThat(
      restoredRegistry
        .findAnalyzerEntryByConnectionId(created.path("connectionId").asText())
        .orElseThrow()
        .getOutboundPort()
    ).isEqualTo(12001);
  }

  /** Exercises real profile publication, persisted configuration and restored runtime; no instrument socket here. */
  @ParameterizedTest
  @CsvSource({ "SERVER,6001", "CLIENT,6001", "SERVER,0", "CLIENT,0" })
  void publishedProfileCanResetOutboundOverrideAndRetainItsPinAcrossRestart(String role, int profilePort) {
    ObjectNode original = profiles.require("genexpert-astm", 5).profile().deepCopy();
    var draft = profiles.duplicateDraft("genexpert-astm", 5, "Outbound default fixture", "profile-editor");
    ObjectNode candidate = draft.profile();
    candidate.withObject("configDefaults").put("outboundPortMode", "DEFAULT");
    if (profilePort > 0) candidate.withObject("transport_config").withObject("TCP/IP").put("default_port", profilePort);
    ArrayNode descriptors = (ArrayNode) candidate.path("connectionFields");
    ObjectNode mode = descriptors
      .addObject()
      .put("key", "outboundPortMode")
      .put("labelKey", "analyzer.connection.field.port")
      .put("inputKind", "SELECT")
      .put("required", false);
    mode
      .putArray("choices")
      .addObject()
      .put("value", "DEFAULT")
      .put("labelKey", "analyzer.qualitativeMapping.isDefault");
    ((ArrayNode) mode.path("choices")).addObject()
      .put("value", "OVERRIDE")
      .put("labelKey", "microbiology.whonet.period.custom");
    ObjectNode port = descriptors
      .addObject()
      .put("key", "outboundPort")
      .put("labelKey", "analyzer.connection.field.port")
      .put("inputKind", "NUMBER")
      .put("required", false);
    port.putArray("choices");
    port
      .putObject("visibleWhen")
      .put("fieldKey", "outboundPortMode")
      .put("operator", "EQUALS")
      .put("value", "OVERRIDE");
    assertThat(profiles.updateDraft(draft.draftId(), candidate, "profile-editor").validationIssues()).isEmpty();
    ObjectNode published = profiles.publishDraft(draft.draftId(), "profile-publisher").profile();

    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerConnectionCatalog catalog = catalog(
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(
        registry,
        null,
        mock(AstmConnectionListeners.class),
        mock(SerialConnectionListeners.class)
      )
    );
    ObjectNode request = createRequest(published, "create-reset-port", "oe-reset-port");
    request.withObject("values").put("connectionRole", role).put("dataFlow", "TWO_WAY").put("host", "192.0.2.10");
    ObjectNode connection = catalog.create(request);
    assertThat(currentValue(connection, "outboundPortMode").asText()).isEqualTo("DEFAULT");
    assertThat(connection.path("readiness").path("ready").asBoolean()).isTrue();
    catalog.applyRuntimeCommand(runtimeCommand(connection, "activate-default-port", "ACTIVATE"));
    String id = connection.path("connectionId").asText();
    int expectedDefault = profilePort > 0 ? profilePort : 12001;
    assertThat(registry.findAnalyzerEntryByConnectionId(id).orElseThrow().getOutboundPort()).isEqualTo(expectedDefault);

    ObjectNode update = updateRequest(connection, "set-outbound-override");
    update.putObject("values").put("outboundPortMode", "OVERRIDE").put("outboundPort", 7001);
    connection = catalog.update(update);
    catalog.applyRuntimeCommand(runtimeCommand(connection, "activate-override-port", "ACTIVATE"));
    assertThat(registry.findAnalyzerEntryByConnectionId(id).orElseThrow().getOutboundPort()).isEqualTo(7001);

    update = updateRequest(connection, "reset-outbound-default");
    // The existing OE2 serializer omits the now-hidden numeric override; other omitted fields remain patches.
    update.putObject("values").put("outboundPortMode", "DEFAULT");
    connection = catalog.update(update);
    catalog.applyRuntimeCommand(runtimeCommand(connection, "activate-reset-port", "ACTIVATE"));
    assertThat(registry.findAnalyzerEntryByConnectionId(id).orElseThrow().getOutboundPort()).isEqualTo(expectedDefault);
    AnalyzerRuntimeRegistry restoredRegistry = new AnalyzerRuntimeRegistry();
    ObjectNode restored = catalog(
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(
        restoredRegistry,
        null,
        mock(AstmConnectionListeners.class),
        mock(SerialConnectionListeners.class)
      )
    ).require(id);
    assertThat(restored.path("profileRef")).isEqualTo(connection.path("profileRef"));
    assertThat(restored.path("profileRef").path("revision").asInt()).isEqualTo(1);
    assertThat(currentValue(restored, "outboundPortMode").asText()).isEqualTo("DEFAULT");
    assertThat(currentValue(restored, "host").asText()).isEqualTo("192.0.2.10");
    assertThat(restoredRegistry.findAnalyzerEntryByConnectionId(id).orElseThrow().getOutboundPort()).isEqualTo(
      expectedDefault
    );
    assertThat(profiles.require("genexpert-astm", 5).profile()).isEqualTo(original);
  }

  private ObjectNode updateRequest(ObjectNode connection, String requestId) {
    ObjectNode request = objectMapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("requestId", requestId)
      .put("connectionId", connection.path("connectionId").asText())
      .put("expectedConfigRevision", connection.path("configRevision").asInt())
      .put("displayName", connection.path("displayName").asText());
    request.set("profileRef", connection.path("profileRef").deepCopy());
    return request;
  }

  @Test
  void createAppliesPinnedProfileDefaultsOnceAndSurvivesRestart() {
    ObjectNode profile = profiles.require("fluorocycler-xt", 1).profile();
    ObjectNode request = createRequest(profile, "create-fluoro-1", "oe-42");
    request.withObject("values").put("directory", "/data/instruments/fluoro-1");

    AnalyzerConnectionCatalog catalog = catalog(() -> UUID.fromString("00000000-0000-0000-0000-000000000042"));
    ObjectNode created = catalog.create(request);
    ObjectNode repeated = catalog.create(request);

    assertThat(repeated).isEqualTo(created);
    assertThat(created.path("connectionId").asText()).isEqualTo("00000000-0000-0000-0000-000000000042");
    assertThat(created.path("clientAnalyzerId").asText()).isEqualTo("oe-42");
    assertThat(created.path("profileRef")).isEqualTo(request.path("profileRef"));
    assertThat(created.path("configRevision").asInt()).isEqualTo(1);
    assertThat(currentValue(created, "filePattern").asText()).isEqualTo("*.{ods,ODS,xlsx,XLSX,xls,XLS}");
    assertThat(currentValue(created, "directory").asText()).isEqualTo("/data/instruments/fluoro-1");

    AnalyzerConnectionCatalog reopened = catalog(UUID::randomUUID);
    assertThat(reopened.require(created.path("connectionId").asText())).isEqualTo(created);
  }

  @Test
  void inactiveFileOwnershipSurvivesCatalogRestartAndDeactivation() {
    ObjectNode profile = profiles.require("fluorocycler-xt", 1).profile();
    AnalyzerConnectionCatalog catalog = catalog(UUID::randomUUID);
    Path shared = temporaryDirectory.resolve("shared");
    ObjectNode firstRequest = createRequest(profile, "first-file", "first");
    firstRequest.withObject("values").put("directory", shared.toString());
    ObjectNode secondRequest = createRequest(profile, "second-file", "second");
    secondRequest.withObject("values").put("directory", shared.toString());
    ObjectNode first = catalog.create(firstRequest);
    catalog.create(secondRequest);
    catalog.applyRuntimeCommand(runtimeCommand(first, "activate-first", "ACTIVATE"));
    catalog.applyRuntimeCommand(runtimeCommand(first, "deactivate-first", "DEACTIVATE"));
    assertThat(catalog.fileDirectoryClaims())
      .extracting(AnalyzerConnectionCatalog.FileDirectoryClaim::analyzerId)
      .containsExactlyInAnyOrder("first", "second");
    assertThat(catalog(UUID::randomUUID).fileDirectoryClaims()).containsExactlyInAnyOrderElementsOf(
      catalog.fileDirectoryClaims()
    );
  }

  @Test
  void updateRequiresTheCurrentRevisionAndKeepsTheExactProfilePin() {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    AnalyzerConnectionCatalog catalog = catalog(() -> UUID.fromString("00000000-0000-0000-0000-000000000099"));
    ObjectNode create = createRequest(profile, "create-genexpert-1", "oe-99");
    create.withObject("values").put("host", "192.0.2.10").put("port", 5000);
    ObjectNode created = catalog.create(create);

    ObjectNode update = objectMapper.createObjectNode();
    update.put("schemaVersion", "1.0");
    update.put("requestId", "update-genexpert-1");
    update.put("connectionId", created.path("connectionId").asText());
    update.put("expectedConfigRevision", 1);
    update.set("profileRef", create.path("profileRef").deepCopy());
    update.put("displayName", "GeneXpert bench 1");
    update.putObject("values").put("host", "192.0.2.11").put("port", 5001);

    ObjectNode changed = catalog.update(update);
    assertThat(changed.path("configRevision").asInt()).isEqualTo(2);
    assertThat(changed.path("profileRef")).isEqualTo(create.path("profileRef"));
    assertThat(currentValue(changed, "host").asText()).isEqualTo("192.0.2.11");

    assertThatThrownBy(() -> catalog.update(update))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("configuration revision");
  }

  @Test
  void updateDoesNotCreateANewRevisionWhenTheEffectiveConfigurationIsUnchanged() {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    AnalyzerConnectionCatalog catalog = catalog(() -> UUID.fromString("00000000-0000-0000-0000-000000000097"));
    ObjectNode create = createRequest(profile, "create-genexpert-noop", "oe-97");
    create.withObject("values").put("port", 5000);
    ObjectNode created = catalog.create(create);

    ObjectNode update = objectMapper.createObjectNode();
    update.put("schemaVersion", "1.0");
    update.put("requestId", "update-genexpert-noop");
    update.put("connectionId", created.path("connectionId").asText());
    update.put("expectedConfigRevision", 1);
    update.set("profileRef", create.path("profileRef").deepCopy());
    update.put("displayName", created.path("displayName").asText());
    update.putObject("values").put("port", 5000);

    ObjectNode unchanged = catalog.update(update);

    assertThat(unchanged.path("configRevision").asInt()).isEqualTo(1);
    assertThat(unchanged.path("configFingerprint").asText()).isEqualTo(created.path("configFingerprint").asText());
  }

  @Test
  void updateChangesOnlySuppliedValuesAndRetainsTheDurableBridgeConfiguration() {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    AnalyzerConnectionCatalog catalog = catalog(() -> UUID.fromString("00000000-0000-0000-0000-000000000098"));
    ObjectNode create = createRequest(profile, "create-genexpert-patch", "oe-98");
    create.withObject("values").put("connectionRole", "CLIENT").put("host", "192.0.2.10").put("port", 5000);
    ObjectNode created = catalog.create(create);

    ObjectNode update = objectMapper.createObjectNode();
    update.put("schemaVersion", "1.0");
    update.put("requestId", "update-genexpert-port-only");
    update.put("connectionId", created.path("connectionId").asText());
    update.put("expectedConfigRevision", 1);
    update.set("profileRef", create.path("profileRef").deepCopy());
    update.put("displayName", created.path("displayName").asText());
    update.putObject("values").put("port", 5001);

    ObjectNode changed = catalog.update(update);

    assertThat(currentValue(changed, "host").asText()).isEqualTo("192.0.2.10");
    assertThat(currentValue(changed, "port").asInt()).isEqualTo(5001);
  }

  @Test
  void createRejectsAProfileFingerprintThatDoesNotIdentifyThePinnedRevision() {
    ObjectNode profile = profiles.require("quantstudio", 1).profile();
    ObjectNode request = createRequest(profile, "create-quantstudio-1", "oe-100");
    ((ObjectNode) request.path("profileRef")).put("fingerprint", "sha256:" + "0".repeat(64));

    assertThatThrownBy(() -> catalog(UUID::randomUUID).create(request))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("fingerprint");
  }

  @Test
  void fileProfileDeclaresRequiredDirectoryAndProfileDefaults() {
    ObjectNode profile = profiles.require("fluorocycler-xt", 1).profile();
    ObjectNode created = catalog(() -> UUID.fromString("00000000-0000-0000-0000-000000000077")).create(
      createRequest(profile, "create-fluoro-empty", "oe-77")
    );

    assertThat(field(created, "directory").path("inputKind").asText()).isEqualTo("FILE_PATH");
    assertThat(field(created, "directory").path("required").asBoolean()).isTrue();
    assertThat(field(created, "directory").path("validationErrors").get(0).asText()).isEqualTo(
      "analyzer.connection.validation.required"
    );
    assertThat(field(created, "filePattern").path("currentValue").asText()).isEqualTo("*.{ods,ODS,xlsx,XLSX,xls,XLS}");
    assertThat(created.path("readiness").path("ready").asBoolean()).isFalse();
    assertThat(created.path("readiness").path("blockers").get(0).path("fieldKeys").get(0).asText()).isEqualTo(
      "directory"
    );
  }

  @Test
  void priorityAstmProfilePreservesBothTransportsAndRendersItsDeclaredFields() {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    ObjectNode created = catalog(() -> UUID.fromString("00000000-0000-0000-0000-000000000088")).create(
      createRequest(profile, "create-genexpert-empty", "oe-88")
    );

    assertThat(textValues(profile.path("transport"))).containsExactly("RS-232", "TCP/IP");
    assertThat(fieldKeys(created)).containsExactly("transport", "connectionRole", "host", "port", "serialPort");
    assertThat(currentValue(created, "transport").asText()).isEqualTo("TCP/IP");
    assertThat(currentValue(created, "connectionRole").asText()).isEqualTo("SERVER");
    assertThat(field(created, "host").path("validationErrors")).isEmpty();
    assertThat(field(created, "port").path("required").asBoolean()).isFalse();
    assertThat(field(created, "port").path("validationErrors")).isEmpty();
    assertThat(field(created, "serialPort").path("validationErrors")).isEmpty();
    assertThat(created.path("readiness").path("ready").asBoolean()).isTrue();
    assertThat(created.path("readiness").path("blockers")).isEmpty();
  }

  @Test
  void hidesDependentFieldsWhenTheirControllingFieldIsHidden() {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    ObjectNode request = createRequest(profile, "create-genexpert-serial", "oe-serial");
    request
      .withObject("values")
      .put("transport", "RS-232")
      .put("connectionRole", "CLIENT")
      .put("serialPort", "/dev/ttyUSB0");

    ObjectNode created = catalog(() -> UUID.fromString("00000000-0000-0000-0000-000000000089")).create(request);

    assertThat(field(created, "host").path("validationErrors")).isEmpty();
    assertThat(field(created, "port").path("validationErrors")).isEmpty();
    assertThat(field(created, "serialPort").path("validationErrors")).isEmpty();
    assertThat(created.path("readiness").path("ready").asBoolean()).isTrue();
    assertThat(created.path("readiness").path("blockers")).isEmpty();
  }

  @Test
  void rendersAnUnfamiliarFieldDirectlyFromThePinnedProfile() throws Exception {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    ArrayNode fields = (ArrayNode) profile.path("connectionFields");
    ObjectNode listenerPort = fields.addObject();
    listenerPort.put("key", "listenerPort");
    listenerPort.put("labelKey", "synthetic.connection.listenerPort");
    listenerPort.put("inputKind", "NUMBER");
    listenerPort.put("required", true);
    listenerPort.putArray("choices");
    refreshFingerprint(profile);
    AnalyzerProfileCatalog syntheticProfiles = profiles(profile);
    ObjectNode request = createRequest(profile, "create-synthetic-1", "oe-synthetic");
    request.withObject("values").put("listenerPort", 6100).put("port", 5000);

    ObjectNode created = catalog(
      syntheticProfiles,
      () -> UUID.fromString("00000000-0000-0000-0000-000000000066")
    ).create(request);

    assertThat(field(created, "listenerPort").path("labelKey").asText()).isEqualTo("synthetic.connection.listenerPort");
    assertThat(field(created, "listenerPort").path("inputKind").asText()).isEqualTo("NUMBER");
    assertThat(field(created, "listenerPort").path("currentValue").asInt()).isEqualTo(6100);
  }

  @Test
  void masksProfileDeclaredSecretsAndPreservesAnOmittedSecretOnUpdate() throws Exception {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    ObjectNode secret = ((ArrayNode) profile.path("connectionFields")).addObject();
    secret.put("key", "apiToken");
    secret.put("labelKey", "synthetic.connection.apiToken");
    secret.put("inputKind", "SECRET");
    secret.put("required", true);
    secret.putArray("choices");
    refreshFingerprint(profile);
    AnalyzerProfileCatalog syntheticProfiles = profiles(profile);
    AnalyzerConnectionCatalog catalog = catalog(
      syntheticProfiles,
      () -> UUID.fromString("00000000-0000-0000-0000-000000000067")
    );
    ObjectNode create = createRequest(profile, "create-secret-1", "oe-secret");
    create.withObject("values").put("apiToken", "do-not-return").put("port", 5000);

    ObjectNode created = catalog.create(create);
    assertThat(field(created, "apiToken").has("currentValue")).isFalse();
    assertThat(field(created, "apiToken").path("isSet").asBoolean()).isTrue();
    assertThat(field(created, "apiToken").path("maskedValue").asText()).isEqualTo("********");

    ObjectNode update = objectMapper.createObjectNode();
    update.put("schemaVersion", "1.0");
    update.put("requestId", "update-secret-1");
    update.put("connectionId", created.path("connectionId").asText());
    update.put("expectedConfigRevision", 1);
    update.set("profileRef", created.path("profileRef").deepCopy());
    update.put("displayName", created.path("displayName").asText());
    update.putObject("values").put("port", 5000);

    ObjectNode changed = catalog.update(update);
    assertThat(field(changed, "apiToken").path("isSet").asBoolean()).isTrue();
    assertThat(changed.path("configFingerprint")).isEqualTo(created.path("configFingerprint"));
  }

  @Test
  void rejectsValuesThatThePinnedProfileDoesNotDeclare() {
    ObjectNode profile = profiles.require("fluorocycler-xt", 1).profile();
    ObjectNode request = createRequest(profile, "create-invented-1", "oe-invented");
    request.withObject("values").put("inventedSetting", "value");

    assertThatThrownBy(() -> catalog(UUID::randomUUID).create(request))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("not declared by the pinned profile");
  }

  @Test
  void activationIsExactIdempotentAndRestoredAfterCatalogRestart() {
    ObjectNode profile = profiles.require("fluorocycler-xt", 1).profile();
    RecordingRuntime firstRuntime = new RecordingRuntime();
    AnalyzerConnectionCatalog catalog = catalog(
      () -> UUID.fromString("00000000-0000-0000-0000-000000000055"),
      firstRuntime
    );
    ObjectNode create = createRequest(profile, "create-runtime-1", "oe-55");
    create.withObject("values").put("directory", temporaryDirectory.toString());
    ObjectNode created = catalog.create(create);
    ObjectNode activate = runtimeCommand(created, "activate-1", "ACTIVATE");

    ObjectNode applied = catalog.applyRuntimeCommand(activate);
    ObjectNode repeated = catalog.applyRuntimeCommand(activate);

    assertThat(repeated).isEqualTo(applied);
    assertThat(applied.path("outcome").asText()).isEqualTo("APPLIED");
    assertThat(applied.path("desiredRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(applied.path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(applied.path("profileRef")).isEqualTo(created.path("profileRef"));
    assertThat(applied.path("configRevision").asInt()).isEqualTo(1);
    assertThat(applied.path("configFingerprint").asText()).isEqualTo(created.path("configFingerprint").asText());
    assertThat(firstRuntime.activations).containsExactly(created.path("connectionId").asText());

    RecordingRuntime restartedRuntime = new RecordingRuntime();
    AnalyzerConnectionCatalog reopened = catalog(UUID::randomUUID, restartedRuntime);
    ObjectNode restored = reopened.require(created.path("connectionId").asText());

    assertThat(restored.path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(restartedRuntime.restorations).containsExactly(created.path("connectionId").asText());

    ObjectNode deactivate = runtimeCommand(restored, "deactivate-1", "DEACTIVATE");
    ObjectNode stopped = reopened.applyRuntimeCommand(deactivate);

    assertThat(stopped.path("outcome").asText()).isEqualTo("APPLIED");
    assertThat(stopped.path("actualRuntimeState").asText()).isEqualTo("INACTIVE");
    assertThat(stopped.path("runtimeRevision").asInt()).isGreaterThan(applied.path("runtimeRevision").asInt());
    assertThat(restartedRuntime.deactivations).containsExactly(created.path("connectionId").asText());
  }

  @Test
  void restartRejoinsTheDeploymentAstmListenerWithTheSavedConnectionAndPinnedProfile() {
    ObjectNode profile = profiles.require("genexpert-astm", 1).profile();
    AstmConnectionListeners firstListeners = mock(AstmConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime firstRuntime = new BridgeAnalyzerConnectionRuntime(
      new org.itech.ahb.connection.AnalyzerRuntimeRegistry(),
      null,
      firstListeners,
      mock(SerialConnectionListeners.class)
    );
    AnalyzerConnectionCatalog catalog = catalog(
      () -> UUID.fromString("00000000-0000-0000-0000-000000000056"),
      firstRuntime
    );
    ObjectNode create = createRequest(profile, "create-astm-runtime-1", "oe-56");
    create.withObject("values").put("port", 9_102);
    ObjectNode created = catalog.create(create);
    catalog.applyRuntimeCommand(runtimeCommand(created, "activate-astm-1", "ACTIVATE"));

    AstmConnectionListeners restartedListeners = mock(AstmConnectionListeners.class);
    BridgeAnalyzerConnectionRuntime restartedRuntime = new BridgeAnalyzerConnectionRuntime(
      new org.itech.ahb.connection.AnalyzerRuntimeRegistry(),
      null,
      restartedListeners,
      mock(SerialConnectionListeners.class)
    );
    AnalyzerConnectionCatalog reopened = catalog(UUID::randomUUID, restartedRuntime);

    assertThat(reopened.require(created.path("connectionId").asText()).path("actualRuntimeState").asText()).isEqualTo(
      "ACTIVE"
    );
    verify(restartedListeners).start(created.path("connectionId").asText(), "oe-56", 12_001, "LIS01_A");
  }

  @Test
  void genexpertRevisionFiveOffersHostAndSenderIdForAServerConnectionWithoutRequiringThem() {
    ObjectNode profile = profiles.require("genexpert-astm", 5).profile();
    ObjectNode request = createRequest(profile, "create-gx-v5", "oe-gx-v5");
    request.withObject("values").put("transport", "TCP/IP").put("connectionRole", "SERVER").put("port", 12_001);

    ObjectNode created = catalog(UUID::randomUUID).create(request);

    assertThat(created.path("readiness").path("ready").asBoolean())
      .as("host and senderId are optional for a SERVER connection")
      .isTrue();
    assertThat(field(created, "host").path("required").asBoolean()).isFalse();
    assertThat(field(created, "host").path("visibleWhen").path("fieldKey").asText()).isEqualTo("transport");
    assertThat(field(created, "senderId").path("labelKey").asText()).isEqualTo("analyzer.connection.field.senderId");
  }

  @Test
  void genexpertRevisionFiveConnectionActivatesWithHostAndSenderIdMaterialised() {
    ObjectNode profile = profiles.require("genexpert-astm", 5).profile();
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerConnectionCatalog catalog = catalog(
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(
        registry,
        null,
        org.mockito.Mockito.mock(AstmConnectionListeners.class),
        org.mockito.Mockito.mock(SerialConnectionListeners.class)
      )
    );
    ObjectNode request = createRequest(profile, "create-gx-v5-named", "oe-gx-v5-named");
    request
      .withObject("values")
      .put("transport", "TCP/IP")
      .put("connectionRole", "SERVER")
      .put("port", 12_001)
      .put("host", "10.0.0.21")
      .put("senderId", "GX-LAB-A");
    ObjectNode created = catalog.create(request);

    ObjectNode activated = catalog.applyRuntimeCommand(runtimeCommand(created, "activate-gx-v5", "ACTIVATE"));

    assertThat(activated.path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    var entry = registry.findAnalyzerEntryByConnectionId(created.path("connectionId").asText()).orElseThrow();
    assertThat(entry.getListenerPort()).isEqualTo(12_001);
    assertThat(entry.getInboundAddress()).isEqualTo("10.0.0.21");
    assertThat(entry.getSenderId()).isEqualTo("GX-LAB-A");
    assertThat(entry.getProfileRevision()).isEqualTo(5);
  }

  @Test
  void aConnectionPinnedToRevisionFourKeepsItsRevisionAndStillActivates() {
    ObjectNode profile = profiles.require("genexpert-astm", 4).profile();
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerConnectionCatalog catalog = catalog(
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(
        registry,
        null,
        org.mockito.Mockito.mock(AstmConnectionListeners.class),
        org.mockito.Mockito.mock(SerialConnectionListeners.class)
      )
    );
    ObjectNode request = createRequest(profile, "create-gx-v4", "oe-gx-v4");
    request.withObject("values").put("transport", "TCP/IP").put("connectionRole", "SERVER").put("port", 9_600);
    ObjectNode created = catalog.create(request);

    catalog.applyRuntimeCommand(runtimeCommand(created, "activate-gx-v4", "ACTIVATE"));

    var entry = registry.findAnalyzerEntryByConnectionId(created.path("connectionId").asText()).orElseThrow();
    assertThat(entry.getProfileRevision()).isEqualTo(4);
    assertThat(entry.getListenerPort()).isEqualTo(12_001);
    assertThat(entry.getInboundAddress()).isNull();
  }

  /**
   * A regression pin for what OpenELIS activation relies on: readiness comes from the saved values
   * alone, so a connection check that fails is recorded and never becomes a readiness blocker.
   */
  @Test
  void aFailedConnectionCheckIsRecordedButNeverBlocksReadiness() throws Exception {
    ObjectNode profile = profiles.require("genexpert-astm", 5).profile();
    AnalyzerConnectionCatalog catalog = catalog(UUID::randomUUID);
    try (java.net.ServerSocket foreign = new java.net.ServerSocket(0)) {
      ObjectNode request = createRequest(profile, "create-gx-probe", "oe-gx-probe");
      request
        .withObject("values")
        .put("transport", "TCP/IP")
        .put("connectionRole", "SERVER")
        .put("port", foreign.getLocalPort());
      String connectionId = catalog.create(request).path("connectionId").asText();
      ObjectNode probeRequest = objectMapper.createObjectNode();
      probeRequest.put("schemaVersion", "1.0");
      probeRequest.put("requestId", "probe-gx");
      probeRequest.put("connectionId", connectionId);
      probeRequest.put("expectedConfigRevision", 1);
      var listenerConfig = new org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties();
      listenerConfig.setPort(foreign.getLocalPort());
      AnalyzerConnectionProbe probe = new AnalyzerConnectionProbe(
        objectMapper,
        CLOCK,
        new org.itech.ahb.connectivity.DefaultConnectionProbeExecutor(),
        (protocol, port) -> false,
        new AnalyzerListenerPorts(
          listenerConfig,
          new org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties(),
          new org.itech.ahb.mllp.MLLPConfig()
        )
      );

      assertThat(catalog.probe(probeRequest, probe).path("status").asText()).isEqualTo("FAILED");

      ObjectNode stored = catalog.require(connectionId);
      assertThat(stored.path("latestProbe").path("status").asText()).isEqualTo("FAILED");
      assertThat(stored.path("readiness").path("ready").asBoolean()).isTrue();
      assertThat(stored.path("readiness").path("blockers")).isEmpty();
    }
  }

  private AnalyzerConnectionCatalog catalog(java.util.function.Supplier<UUID> ids) {
    return catalog(profiles, ids);
  }

  private AnalyzerConnectionCatalog catalog(
    AnalyzerProfileCatalog selectedProfiles,
    java.util.function.Supplier<UUID> ids
  ) {
    return new AnalyzerConnectionCatalog(
      temporaryDirectory.resolve("connections"),
      selectedProfiles,
      objectMapper,
      CLOCK,
      ids
    );
  }

  private AnalyzerConnectionCatalog catalog(java.util.function.Supplier<UUID> ids, AnalyzerConnectionRuntime runtime) {
    return new AnalyzerConnectionCatalog(
      temporaryDirectory.resolve("connections"),
      profiles,
      objectMapper,
      CLOCK,
      ids,
      runtime
    );
  }

  private ObjectNode runtimeCommand(ObjectNode connection, String commandId, String action) {
    ObjectNode command = objectMapper.createObjectNode();
    command.put("schemaVersion", "1.0");
    command.put("commandId", commandId);
    command.put("connectionId", connection.path("connectionId").asText());
    command.put("action", action);
    command.put("expectedConfigRevision", connection.path("configRevision").asInt());
    return command;
  }

  private static final class RecordingRuntime implements AnalyzerConnectionRuntime {

    private final List<String> activations = new ArrayList<>();
    private final List<String> deactivations = new ArrayList<>();
    private final List<String> restorations = new ArrayList<>();

    @Override
    public void activate(ObjectNode connection, ObjectNode profile) {
      activations.add(connection.path("connectionId").asText());
    }

    @Override
    public void deactivate(ObjectNode connection, ObjectNode profile) {
      deactivations.add(connection.path("connectionId").asText());
    }

    @Override
    public void restore(ObjectNode connection, ObjectNode profile) {
      restorations.add(connection.path("connectionId").asText());
    }
  }

  private ObjectNode createRequest(ObjectNode profile, String requestId, String clientAnalyzerId) {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("schemaVersion", "1.0");
    request.put("requestId", requestId);
    request.put("clientAnalyzerId", clientAnalyzerId);
    ObjectNode profileRef = request.putObject("profileRef");
    profileRef.put("profileId", profile.path("profileMeta").path("id").asText());
    profileRef.put("revision", profile.path("catalog").path("revision").asInt());
    profileRef.put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    request.put("displayName", profile.path("profileMeta").path("displayName").asText());
    request.putObject("values");
    return request;
  }

  private AnalyzerProfileCatalog profiles(ObjectNode profile) throws Exception {
    Resource resource = new ByteArrayResource(objectMapper.writeValueAsBytes(profile), "synthetic profile");
    return new AnalyzerProfileCatalog(
      temporaryDirectory.resolve("synthetic-profiles"),
      List.of(resource),
      objectMapper,
      CLOCK
    );
  }

  private static void refreshFingerprint(ObjectNode profile) {
    ((ObjectNode) profile.path("catalog")).put(
        "revisionFingerprint",
        new ProfileFingerprintService().revisionFingerprint(profile)
      );
  }

  private static JsonNode currentValue(ObjectNode connection, String key) {
    return field(connection, key).path("currentValue");
  }

  private static JsonNode field(ObjectNode connection, String key) {
    for (JsonNode field : connection.path("fields")) {
      if (key.equals(field.path("key").asText())) {
        return field;
      }
    }
    throw new AssertionError("Missing connection field " + key);
  }

  private static List<String> fieldKeys(ObjectNode connection) {
    List<String> keys = new ArrayList<>();
    connection.path("fields").forEach(field -> keys.add(field.path("key").asText()));
    return keys;
  }

  private static List<String> textValues(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.asText()));
    return result;
  }
}
