package org.itech.ahb;

import org.itech.ahb.controller.ASTMServerRunnerTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Test that verifies the Spring Boot application context loads successfully.
 * Uses MockBean to prevent ASTM servers from actually starting during tests,
 * which avoids port binding and shutdown timing issues.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AstmHttpBridgeApplicationTests {

  /**
   * Mock the server trigger to prevent actual ASTM servers from starting.
   */
  @MockBean
  private ASTMServerRunnerTrigger serverRunnerTrigger;

  @Test
  void contextLoads() {
    // Context loads successfully without starting ASTM servers
  }
}
