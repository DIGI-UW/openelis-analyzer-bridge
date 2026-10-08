package org.itech.ahb.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.pairing.PairedPeerFilter;
import org.itech.ahb.pairing.PairingState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Spring Security configuration for the analyzer bridge.
 * <p>
 * OpenELIS authenticates with the TLS client certificate it pinned at pairing. Anonymous callers
 * reach only {@code GET /actuator/health} (overall status), {@code /pairing}, and {@code POST /input},
 * the HTTP transport for analyzers, which accepts only senders of an active HTTP connection. Every
 * other endpoint, including one added later, requires authentication.
 * </p>
 * <p>
 * A configured {@code bridge.security.password} keeps HTTP Basic working for a Bridge that has not
 * been paired yet, so an OpenELIS that still uses a password is not cut off by an upgrade. The first
 * pairing ends password access. Without a password, only the paired certificate authenticates; a
 * blank password is never a credential. The shipped default {@code changeme} stops startup outside
 * the dev and test profiles, and authentication cannot be switched off.
 * </p>
 * <p>
 * <strong>Password semantics:</strong> plaintext is encoded at startup; a value already in the
 * delegating-encoder form {@code {id}encoded} (e.g. {@code {bcrypt}$2a$10$...}) is stored as-is.
 * </p>
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

  private static final String DEFAULT_PASSWORD = "changeme";

  @Value("${bridge.security.enabled:true}")
  private boolean securityEnabled;

  @Value("${bridge.security.username:bridge}")
  private String username;

  @Value("${bridge.security.password:}")
  private String password;

  private final Environment environment;

  public SecurityConfig(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  void failFastOnUnsafeSettings() {
    if (!securityEnabled) {
      throw new IllegalStateException(
        "bridge.security.enabled=false is no longer supported: every HTTP endpoint except health, pairing " +
        "and analyzer input requires authentication. Remove the setting."
      );
    }
    if (password == null || password.isBlank()) {
      log.info("No bridge.security.password is set: only the paired OpenELIS certificate authenticates");
      return;
    }
    if (!DEFAULT_PASSWORD.equals(password)) {
      log.info("bridge.security.password is accepted until this Bridge is paired with OpenELIS");
      return;
    }
    boolean isDevOrTest = Arrays.stream(environment.getActiveProfiles())
      .anyMatch(p -> "dev".equals(p) || "test".equals(p));
    if (isDevOrTest) {
      log.warn("Bridge security using default password 'changeme' — acceptable for dev/test only");
      return;
    }
    log.error(
      "SECURITY: bridge.security.password must not be the shipped default outside dev/test. " +
      "Pair OpenELIS by certificate and remove the password, or set a unique one."
    );
    throw new IllegalStateException(
      "bridge.security.password must not be 'changeme' in production. " +
      "Unset it to rely on pairing, or set BRIDGE_AUTH_PASSWORD to a unique value."
    );
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, PairingState pairing) throws Exception {
    log.info(
      "Configuring bridge security: health, pairing and analyzer input are open; " +
      "every other endpoint requires the paired OpenELIS"
    );

    http
      .csrf(csrf -> csrf.disable())
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth ->
        auth
          // Container and OpenELIS healthchecks read only the overall status.
          .requestMatchers(HttpMethod.GET, "/actuator/health")
          .permitAll()
          // The pairing code, not a credential, authorizes pairing.
          .requestMatchers("/pairing")
          .permitAll()
          // An analyzer transport: the controller accepts only senders of an active HTTP connection.
          .requestMatchers(HttpMethod.POST, "/input")
          .permitAll()
          // The container's error dispatch renders the body of a response whose
          // status is already decided, such as a 401; a direct GET /error is a
          // REQUEST dispatch and still needs credentials.
          .dispatcherTypeMatchers(DispatcherType.ERROR)
          .permitAll()
          .anyRequest()
          .authenticated()
      )
      .addFilterBefore(new PairedPeerFilter(pairing), BasicAuthenticationFilter.class)
      .httpBasic(Customizer.withDefaults());

    return http.build();
  }

  /** HTTP Basic for the configured password, refused once the Bridge is paired. */
  @Bean
  public AuthenticationProvider passwordUntilPaired(PasswordEncoder passwordEncoder, PairingState pairing) {
    InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
    if (password != null && !password.isBlank()) {
      users.createUser(
        User.builder()
          .username(username)
          .password(encodePasswordIfPlaintext(password, passwordEncoder))
          .roles("BRIDGE")
          .build()
      );
      log.info("Configured bridge security user: {}", username);
    }
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider() {
      @Override
      protected void additionalAuthenticationChecks(
        UserDetails user,
        UsernamePasswordAuthenticationToken authentication
      ) {
        if (pairing.isPaired()) {
          throw new DisabledException("Passwords are not accepted once the Bridge is paired with OpenELIS");
        }
        super.additionalAuthenticationChecks(user, authentication);
      }
    };
    provider.setPasswordEncoder(passwordEncoder);
    provider.setUserDetailsService(users);
    return provider;
  }

  /**
   * Delegating-password values ({@code {bcrypt}$2a$...}, etc.) must be kept as-is.
   * Plaintext is encoded once at startup. A blank value is refused: it must never authenticate.
   */
  static String encodePasswordIfPlaintext(String rawPassword, PasswordEncoder passwordEncoder) {
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new IllegalArgumentException("A blank bridge.security.password is not a credential");
    }
    if (isDelegatingEncodedPassword(rawPassword)) {
      return rawPassword;
    }
    return passwordEncoder.encode(rawPassword);
  }

  private static boolean isDelegatingEncodedPassword(String value) {
    if (!value.startsWith("{")) {
      return false;
    }
    int close = value.indexOf('}');
    return close > 1;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
