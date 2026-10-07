package org.itech.ahb.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.itech.ahb.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

/** Settings that would leave the HTTP API unprotected stop the Bridge from starting. */
class SecurityConfigStartupTest {

  private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
    .withConfiguration(
      AutoConfigurations.of(
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class
      )
    )
    .withUserConfiguration(SecurityConfig.class)
    .withBean(
      org.itech.ahb.pairing.PairingState.class,
      () -> new org.itech.ahb.pairing.PairingState(java.nio.file.Path.of("target/startup-test-pairing"), "")
    );

  private final String passwordSetting = "bridge.security.password=" + UUID.randomUUID();

  @Test
  void startsWithConfiguredCredentials() {
    runner
      .withPropertyValues(passwordSetting)
      .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(SecurityFilterChain.class);
      });
  }

  @Test
  void refusesToStartWithAuthenticationDisabled() {
    runner
      .withPropertyValues(passwordSetting, "bridge.security.enabled=false")
      .run(context -> {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("bridge.security.enabled=false");
      });
  }

  @Test
  void startsWithoutAPasswordBecausePairingAuthenticates() {
    runner.run(context -> assertThat(context).hasNotFailed());
    runner.withPropertyValues("bridge.security.password=").run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void refusesTheShippedDefaultPasswordOutsideDevAndTest() {
    runner
      .withPropertyValues("bridge.security.password=changeme")
      .run(context -> {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("bridge.security.password");
      });
  }
}
