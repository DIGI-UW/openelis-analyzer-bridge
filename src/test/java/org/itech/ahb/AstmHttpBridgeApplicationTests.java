package org.itech.ahb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test that verifies the Spring Boot application context loads successfully.
 * The shared test profile disables external listener startup while retaining
 * the production beans needed for context validation.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test",
        "bridge.security.password=test-context-password"
    })
class AstmHttpBridgeApplicationTests {
  @Test
  void contextLoads() {
    // Context loads successfully without starting ASTM servers
  }
}
