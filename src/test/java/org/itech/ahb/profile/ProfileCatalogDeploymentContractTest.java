package org.itech.ahb.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@DisplayName("OGC-1054 profile catalog deployment contract")
class ProfileCatalogDeploymentContractTest {

  private static final Path COMPOSE_FILE = Path.of("docker-compose.yml");
  private static final String CATALOG_PATH = "/data/openelis-analyzer-bridge/profiles";

  @Test
  @DisplayName("compose persists the profile catalog without global stack identifiers")
  void composePersistsCatalogPerProject() throws IOException {
    Map<String, Object> compose = new Yaml().load(Files.readString(COMPOSE_FILE));
    Map<String, Object> services = map(compose.get("services"));
    Map<String, Object> bridge = map(services.get("openelis-analyzer-bridge"));

    assertFalse(bridge.containsKey("container_name"), "container_name prevents parallel Compose projects");

    Map<String, Object> environment = map(bridge.get("environment"));
    assertEquals(CATALOG_PATH, environment.get("BRIDGE_PROFILE_CATALOG_DIR"));

    List<String> serviceVolumes = list(bridge.get("volumes"));
    assertTrue(serviceVolumes.contains("bridge-profile-catalog:" + CATALOG_PATH));

    Map<String, Object> volumes = map(compose.get("volumes"));
    assertTrue(volumes.containsKey("bridge-profile-catalog"));
    assertEquals(Map.of(), map(volumes.get("bridge-profile-catalog")));
  }

  @Test
  @DisplayName("compose host ports can be allocated independently for parallel projects")
  void composePortsAreProjectConfigurable() throws IOException {
    Map<String, Object> compose = new Yaml().load(Files.readString(COMPOSE_FILE));
    Map<String, Object> bridge = map(map(compose.get("services")).get("openelis-analyzer-bridge"));

    assertEquals(
      List.of(
        "${BRIDGE_HTTPS_PORT:-8442}:8443",
        "${BRIDGE_ASTM_LIS1_PORT:-12000}:12001",
        "${BRIDGE_ASTM_E1381_PORT:-12010}:12011",
        "${BRIDGE_MLLP_PORT:-2575}:2575"
      ),
      list(bridge.get("ports"))
    );
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<String> list(Object value) {
    return (List<String>) value;
  }
}
