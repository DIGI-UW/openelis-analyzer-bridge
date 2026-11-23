package org.itech.ahb;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Checks the servlet filters installed in a running Bridge. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test",
        "bridge.security.password=test-context-password"
    })
class AstmHttpBridgeApplicationTests {

  @Autowired
  private ServletContext servletContext;

  @Test
  void doesNotRegisterTheFormContentFilter() {
    assertThat(servletContext.getFilterRegistrations())
        .containsKey("springSecurityFilterChain")
        .doesNotContainKey("formContentFilter");
  }
}
