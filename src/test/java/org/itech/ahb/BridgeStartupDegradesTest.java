package org.itech.ahb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One bad profile, draft or saved connection is set aside with its reason; the Bridge starts and
 * serves everything else.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = { "spring.profiles.active=test" }
)
class BridgeStartupDegradesTest {

  private static final String RUN_LOGIN = java.util.UUID.randomUUID().toString();
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String GOOD = "00000000-0000-4000-8000-000000000001";
  private static final String MISSING_REVISION = "00000000-0000-4000-8000-000000000002";
  private static final String CHANGED_FINGERPRINT = "00000000-0000-4000-8000-000000000003";
  private static final String UNREADABLE = "00000000-0000-4000-8000-000000000004";
  private static final String ACTIVE_WITHOUT_CONFIGURATION = "00000000-0000-4000-8000-000000000005";
  private static final String ACTIVE_MISSING_REVISION = "00000000-0000-4000-8000-000000000006";
  private static final String SAME_ANALYZER = "00000000-0000-4000-8000-000000000007";

  @Autowired
  private TestRestTemplate rest;

  @DynamicPropertySource
  static void startupState(DynamicPropertyRegistry registry) throws IOException {
    Path root = Files.createTempDirectory("bridge-startup-degrades");
    Path shipped = Files.createDirectories(root.resolve("shipped"));
    copyShipped("cepheid-genexpert-astm.json", shipped);
    copyShipped("hain-fluorocycler-xt.json", shipped);
    Files.writeString(shipped.resolve("broken.json"), "{ not json");

    Path catalog = Files.createDirectories(root.resolve("catalog"));
    Files.createDirectories(catalog.resolve(".drafts"));
    Files.writeString(catalog.resolve(".drafts").resolve("bad-draft.json"), "{}");
    Files.createDirectories(catalog.resolve("broken-profile"));
    Files.writeString(catalog.resolve("broken-profile").resolve("1.json"), "{\"profile\":{}}");

    ObjectNode genexpert = (ObjectNode) JSON.readTree(shippedProfile("cepheid-genexpert-astm.json"));
    String fingerprint = genexpert.path("catalog").path("revisionFingerprint").asText();
    Path connections = Files.createDirectories(root.resolve("connections"));
    write(connections, connection(GOOD, "oe-good", 1, fingerprint, "INACTIVE"));
    write(connections, connection(MISSING_REVISION, "oe-missing-revision", 99, fingerprint, "INACTIVE"));
    write(connections, connection(CHANGED_FINGERPRINT, "oe-changed", 1, "sha256:" + "0".repeat(64), "INACTIVE"));
    Files.writeString(connections.resolve(UNREADABLE + ".json"), "{ not json");
    write(connections, connection(ACTIVE_WITHOUT_CONFIGURATION, "oe-active-unconfigured", 1, fingerprint, "ACTIVE"));
    write(connections, connection(ACTIVE_MISSING_REVISION, "oe-active-missing", 99, fingerprint, "ACTIVE"));
    write(connections, connection(SAME_ANALYZER, "oe-good", 1, fingerprint, "INACTIVE"));

    registry.add("bridge.security.password", () -> RUN_LOGIN);
    registry.add("bridge.profile-catalog.shipped-pattern", () -> "file:" + shipped + "/*.json");
    registry.add("bridge.profile-catalog.directory", catalog::toString);
    registry.add("bridge.connection-catalog.directory", connections::toString);
  }

  @Test
  void theBridgeStartsAndReportsWhatItSetAside() {
    ResponseEntity<JsonNode> health = rest.getForEntity("/actuator/health", JsonNode.class);
    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody().path("status").asText()).as("a set-aside item is its own issue").isEqualTo("UP");

    JsonNode catalog = get("/api/profiles").getBody();
    List<String> profileIds = new ArrayList<>();
    catalog
      .path("profiles")
      .forEach(entry -> profileIds.add(entry.path("profile").path("profileMeta").path("id").asText()));
    assertThat(profileIds).contains("cepheid-genexpert-astm", "hain-fluorocycler-xt");
    assertThat(texts(catalog.path("issues"), "source"))
      .anyMatch(source -> source.contains("broken.json"))
      .anyMatch(source -> source.contains("bad-draft.json"))
      .anyMatch(source -> source.contains("broken-profile"));
    assertThat(texts(catalog.path("issues"), "reason")).allMatch(reason -> !reason.isBlank());

    assertThat(get("/api/connections/" + GOOD).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(blockerKeys(connectionView(MISSING_REVISION))).contains("profile-unavailable");
    assertThat(blockerKeys(connectionView(CHANGED_FINGERPRINT))).contains("profile-unavailable");

    JsonNode unconfigured = connectionView(ACTIVE_WITHOUT_CONFIGURATION);
    assertThat(unconfigured.path("actualRuntimeState").asText()).isEqualTo("ERROR");
    assertThat(blockers(unconfigured)).anyMatch(
      blocker ->
        "runtime-restore-failed".equals(blocker.path("key").asText()) && !blocker.path("detail").asText().isBlank()
    );

    JsonNode activeMissing = connectionView(ACTIVE_MISSING_REVISION);
    assertThat(activeMissing.path("actualRuntimeState").asText()).isEqualTo("ERROR");
    assertThat(blockerKeys(activeMissing)).contains("profile-unavailable");

    ResponseEntity<JsonNode> unreadable = get("/api/connections/" + UNREADABLE);
    assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(unreadable.getBody().path("error").asText()).contains("cannot be read");
    ResponseEntity<JsonNode> sameAnalyzer = get("/api/connections/" + SAME_ANALYZER);
    assertThat(sameAnalyzer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(sameAnalyzer.getBody().path("error").asText()).contains("oe-good");
  }

  private ResponseEntity<JsonNode> get(String path) {
    return rest.withBasicAuth("bridge", RUN_LOGIN).getForEntity(path, JsonNode.class);
  }

  private JsonNode connectionView(String connectionId) {
    ResponseEntity<JsonNode> response = get("/api/connections/" + connectionId);
    assertThat(response.getStatusCode()).as(connectionId + ": " + response.getBody()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private static List<JsonNode> blockers(JsonNode view) {
    List<JsonNode> blockers = new ArrayList<>();
    view.path("readiness").path("blockers").forEach(blockers::add);
    return blockers;
  }

  private static List<String> blockerKeys(JsonNode view) {
    return blockers(view).stream().map(blocker -> blocker.path("key").asText()).toList();
  }

  private static List<String> texts(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    array.forEach(item -> values.add(item.path(field).asText()));
    return values;
  }

  private static ObjectNode connection(String id, String analyzerId, int revision, String fingerprint, String state) {
    ObjectNode record = JSON.createObjectNode();
    record.put("connectionId", id);
    record.put("clientAnalyzerId", analyzerId);
    record.put("createRequestId", "startup-" + id);
    record.put("displayName", analyzerId);
    record
      .putObject("profileRef")
      .put("profileId", "cepheid-genexpert-astm")
      .put("revision", revision)
      .put("fingerprint", fingerprint);
    record.put("configRevision", 1);
    record.putObject("values");
    record.put("configFingerprint", "sha256:" + "1".repeat(64));
    record.putNull("latestProbe");
    record.put("desiredRuntimeState", state);
    record.put("actualRuntimeState", state);
    record.putNull("activeRuntimeRef");
    record.put("runtimeRevision", 1);
    record.put("runtimeFingerprint", "sha256:" + "2".repeat(64));
    record.putObject("runtimeCommandAcks");
    record.put("updatedAt", "2026-10-06T00:00:00Z");
    return record;
  }

  private static void write(Path directory, ObjectNode record) throws IOException {
    Files.writeString(directory.resolve(record.path("connectionId").asText() + ".json"), record.toString());
  }

  private static void copyShipped(String name, Path directory) throws IOException {
    Files.writeString(directory.resolve(name), shippedProfile(name));
  }

  private static String shippedProfile(String name) {
    try (InputStream profile = BridgeStartupDegradesTest.class.getResourceAsStream("/analyzer-profiles/" + name)) {
      return new String(profile.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
