package org.itech.ahb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test that verifies the Spring Boot application context loads successfully.
 * Test configuration disables analyzer-facing listeners so this generic
 * application-context test does not bind external transport ports.
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
    // Context loads successfully without starting analyzer-facing listeners.
  }
}
