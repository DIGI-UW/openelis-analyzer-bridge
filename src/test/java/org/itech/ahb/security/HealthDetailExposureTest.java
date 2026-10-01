package org.itech.ahb.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Health components name connection IDs, outbox counts and serial device paths, so only an
 * authenticated caller may see them. Runs the {@code dev} profile because deployments use it.
 */
@SpringBootTest(
  properties = {
    "spring.profiles.active=dev",
    "server.ssl.enabled=false",
    "bridge.security.username=testuser",
    "bridge.security.password=testpass",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false"
  }
)
@AutoConfigureMockMvc
class HealthDetailExposureTest {

  @DynamicPropertySource
  static void stateDirectories(DynamicPropertyRegistry registry) throws IOException {
    Path directory = Files.createTempDirectory("bridge-health-detail-test");
    registry.add("bridge.outbox.db-path", () -> directory.resolve("outbox.db").toString());
    registry.add("bridge.connection-catalog.directory", () -> directory.resolve("connections").toString());
    registry.add("bridge.profile-catalog.directory", () -> directory.resolve("profile-catalog").toString());
  }

  @Autowired
  private MockMvc mockMvc;

  @Test
  void anonymousHealthHidesComponents() throws Exception {
    mockMvc
      .perform(get("/actuator/health"))
      .andExpect(jsonPath("$.status").exists())
      .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void authenticatedHealthShowsComponents() throws Exception {
    mockMvc
      .perform(get("/actuator/health").with(httpBasic("testuser", "testpass")))
      .andExpect(jsonPath("$.components.outbox").exists());
  }
}
