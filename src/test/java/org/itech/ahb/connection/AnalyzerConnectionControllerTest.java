package org.itech.ahb.connection;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.connectivity.ProbeCheck;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.profile.ProfileCatalogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalyzerConnectionControllerTest {

  @TempDir
  Path temporaryDirectory;

  private ObjectMapper objectMapper;
  private AnalyzerProfileCatalog profiles;
  private MockMvc mockMvc;
  private AnalyzerConnectionCatalog connections;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    ProfileCatalogProperties properties = new ProfileCatalogProperties();
    Resource[] shipped = new PathMatchingResourcePatternResolver().getResources(properties.getShippedPattern());
    Clock clock = Clock.fixed(Instant.parse("2026-08-24T20:00:00Z"), ZoneOffset.UTC);
    profiles = new AnalyzerProfileCatalog(
      temporaryDirectory.resolve("profiles"),
      Arrays.stream(shipped).toList(),
      objectMapper,
      clock
    );
    connections = new AnalyzerConnectionCatalog(
      temporaryDirectory.resolve("connections"),
      profiles,
      objectMapper,
      clock,
      () -> UUID.fromString("00000000-0000-0000-0000-000000000042")
    );
    ConnectionProbeExecutor executor = mock(ConnectionProbeExecutor.class);
    when(executor.probeDirectory(anyString()))
      .thenReturn(new ProbeCheck("DIRECTORY", "PASSED", "directory.ready", 7, Map.of()));
    mockMvc = MockMvcBuilders.standaloneSetup(
      new AnalyzerConnectionController(
        connections,
        new AnalyzerConnectionContractValidator(objectMapper),
        new AnalyzerConnectionProbe(objectMapper, clock, executor)
      )
    ).build();
  }

  @Test
  void createsAndReadsAContractConformantConnection() throws Exception {
    ObjectNode request = createRequest();

    mockMvc
      .perform(
        post("/api/connections")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsBytes(request))
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.connectionId").value("00000000-0000-0000-0000-000000000042"))
      .andExpect(jsonPath("$.profileRef.profileId").value("fluorocycler-xt"))
      .andExpect(jsonPath("$.configRevision").value(1))
      .andExpect(jsonPath("$.actualRuntimeState").value("INACTIVE"));

    mockMvc
      .perform(get("/api/connections/00000000-0000-0000-0000-000000000042"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.clientAnalyzerId").value("oe-42"));
  }

  @Test
  void rejectsFieldsOutsideTheVersionedCreateContract() throws Exception {
    ObjectNode request = createRequest();
    request.put("protocol", "FILE");

    mockMvc
      .perform(
        post("/api/connections")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsBytes(request))
      )
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void returnsConflictForAStaleUpdateRevision() throws Exception {
    ObjectNode create = createRequest();
    mockMvc.perform(
      post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(create))
    );

    ObjectNode update = objectMapper.createObjectNode();
    update.put("schemaVersion", "1.0");
    update.put("requestId", "update-1");
    update.put("connectionId", "00000000-0000-0000-0000-000000000042");
    update.put("expectedConfigRevision", 2);
    update.set("profileRef", create.path("profileRef").deepCopy());
    update.put("displayName", "FluoroCycler bench 1");
    update.set("values", create.path("values").deepCopy());

    mockMvc
      .perform(
        put("/api/connections/00000000-0000-0000-0000-000000000042")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsBytes(update))
      )
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("configuration revision")));
  }

  @Test
  void probesTheExactSavedRevisionWithoutChangingItsConfiguration() throws Exception {
    ObjectNode create = createRequest();
    create.withObject("values").put("directory", temporaryDirectory.toString());
    mockMvc.perform(
      post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(create))
    );

    ObjectNode probe = objectMapper.createObjectNode();
    probe.put("schemaVersion", "1.0");
    probe.put("requestId", "probe-1");
    probe.put("connectionId", "00000000-0000-0000-0000-000000000042");
    probe.put("expectedConfigRevision", 1);

    mockMvc
      .perform(
        post("/api/connections/00000000-0000-0000-0000-000000000042/probe")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsBytes(probe))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.requestId").value("probe-1"))
      .andExpect(jsonPath("$.connectionId").value("00000000-0000-0000-0000-000000000042"))
      .andExpect(jsonPath("$.configRevision").value(1))
      .andExpect(jsonPath("$.nonMutating").value(true))
      .andExpect(jsonPath("$.status").value("SUCCEEDED"))
      .andExpect(jsonPath("$.checks[0].key").value("directory"))
      .andExpect(jsonPath("$.checks[0].status").value("PASSED"));

    ObjectNode after = connections.require("00000000-0000-0000-0000-000000000042");
    org.assertj.core.api.Assertions.assertThat(after.path("configRevision").asInt()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(after.path("configFingerprint").asText()).isNotBlank();
    org.assertj.core.api.Assertions.assertThat(after.path("desiredRuntimeState").asText()).isEqualTo("INACTIVE");
    org.assertj.core.api.Assertions.assertThat(after.path("latestProbe").path("requestId").asText())
      .isEqualTo("probe-1");
  }

  @Test
  void rejectsAProbeForAStaleConfigurationRevision() throws Exception {
    ObjectNode create = createRequest();
    mockMvc.perform(
      post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(create))
    );

    ObjectNode probe = objectMapper.createObjectNode();
    probe.put("schemaVersion", "1.0");
    probe.put("requestId", "probe-stale");
    probe.put("connectionId", "00000000-0000-0000-0000-000000000042");
    probe.put("expectedConfigRevision", 2);

    mockMvc
      .perform(
        post("/api/connections/00000000-0000-0000-0000-000000000042/probe")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsBytes(probe))
      )
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("configuration revision")));
  }

  @Test
  void activatesTheExactSavedConnectionThroughTheRuntimeContract() throws Exception {
    ObjectNode create = createRequest();
    mockMvc.perform(
      post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(create))
    );
    ObjectNode command = objectMapper.createObjectNode();
    command.put("schemaVersion", "1.0");
    command.put("commandId", "activate-1");
    command.put("connectionId", "00000000-0000-0000-0000-000000000042");
    command.put("action", "ACTIVATE");
    command.put("expectedConfigRevision", 1);

    mockMvc
      .perform(
        post("/api/connections/00000000-0000-0000-0000-000000000042/runtime")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsBytes(command))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.commandId").value("activate-1"))
      .andExpect(jsonPath("$.outcome").value("APPLIED"))
      .andExpect(jsonPath("$.profileRef.profileId").value("fluorocycler-xt"))
      .andExpect(jsonPath("$.configRevision").value(1))
      .andExpect(jsonPath("$.desiredRuntimeState").value("ACTIVE"))
      .andExpect(jsonPath("$.actualRuntimeState").value("ACTIVE"))
      .andExpect(jsonPath("$.blockers").isEmpty());
  }

  private ObjectNode createRequest() {
    ObjectNode profile = profiles.require("fluorocycler-xt", 1).profile();
    ObjectNode request = objectMapper.createObjectNode();
    request.put("schemaVersion", "1.0");
    request.put("requestId", "create-1");
    request.put("clientAnalyzerId", "oe-42");
    ObjectNode profileRef = request.putObject("profileRef");
    profileRef.put("profileId", "fluorocycler-xt");
    profileRef.put("revision", 1);
    profileRef.put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    request.put("displayName", "FluoroCycler bench 1");
    request.putObject("values").put("directory", "/data/instruments/fluoro-1");
    return request;
  }
}
