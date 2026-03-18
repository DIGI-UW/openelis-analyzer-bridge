package org.itech.ahb.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;

/**
 * Spring Security configuration for the analyzer bridge.
 * <p>
 * Protects the {@code /input} HTTP endpoint with HTTP Basic authentication.
 * Non-HTTP transports (ASTM/TCP, MLLP, Serial, File) are unaffected since they
 * don't go through the servlet filter chain.
 * </p>
 * <p>
 * Enabled by default via {@code bridge.security.enabled=true}. Set to {@code false}
 * to disable authentication (not recommended for production).
 * </p>
 * <p>
 * <strong>Password semantics:</strong> The {@code bridge.security.password} property
 * accepts either plaintext (hashed at runtime with BCrypt) or a pre-hashed value with
 * {@code {bcrypt}} prefix (e.g. {@code {bcrypt}$2a$10$...}). Use plaintext for simple
 * deployments; use pre-hashed for secret managers or when rotating without restarts.
 * </p>
 *
 * @see org.itech.ahb.controller.AnalyzerInputController
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "bridge.security.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class SecurityConfig {

    private static final String DEFAULT_PASSWORD = "changeme";

    @Value("${bridge.security.username:bridge}")
    private String username;

    @Value("${bridge.security.password:changeme}")
    private String password;

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void failFastOnDefaultPasswordInProduction() {
        if (!DEFAULT_PASSWORD.equals(password)) {
            return;
        }
        boolean isDevOrTest = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equals(p) || "test".equals(p));
        if (isDevOrTest) {
            log.warn("Bridge security using default password 'changeme' — acceptable for dev/test only");
            return;
        }
        log.error("SECURITY: bridge.security.password must be set explicitly in production. "
                + "Default 'changeme' is not allowed when spring.profiles.active is not dev/test.");
        throw new IllegalStateException(
                "bridge.security.password must be set explicitly in production. "
                        + "Set BRIDGE_AUTH_PASSWORD env var or bridge.security.password in configuration.");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("Configuring bridge security: HTTP Basic auth for /input endpoint");

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Actuator: health, info, prometheus, metrics are public for monitoring
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()
                // All other actuator endpoints require authentication
                .requestMatchers("/actuator/**").authenticated()
                // The /input endpoint requires authentication
                .requestMatchers("/input/**").authenticated()
                // All other endpoints (ASTM query forwarding, etc.) are permitted
                .anyRequest().permitAll()
            )
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        var user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .roles("BRIDGE")
                .build();

        log.info("Configured bridge security user: {}", username);
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
