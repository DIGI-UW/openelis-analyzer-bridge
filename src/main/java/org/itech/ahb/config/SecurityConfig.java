package org.itech.ahb.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * The password can be provided in plaintext or pre-hashed with a
 * {@code {bcrypt}} prefix (e.g. {@code {bcrypt}$2a$10$...}). Plaintext passwords
 * are hashed in-memory at startup and never stored in cleartext.
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

    @Value("${bridge.security.password:" + DEFAULT_PASSWORD + "}")
    private String password;

    @PostConstruct
    void warnIfDefaultPassword() {
        if (DEFAULT_PASSWORD.equals(password)) {
            log.warn("**********************************************************************");
            log.warn("* WARNING: Bridge security is using the DEFAULT password 'changeme'. *");
            log.warn("* This is insecure. Set bridge.security.password or                  *");
            log.warn("* BRIDGE_AUTH_PASSWORD environment variable before deploying.         *");
            log.warn("**********************************************************************");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("Configuring bridge security: HTTP Basic auth for /input endpoint");

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Actuator endpoints: health, info, prometheus, and metrics are public
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()
                // All other actuator endpoints require authentication
                .requestMatchers("/actuator/**").authenticated()
                // The /input endpoint requires authentication
                .requestMatchers("/input/**").authenticated()
                // All other endpoints are permitted (ASTM query endpoints, etc.)
                .anyRequest().permitAll()
            )
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        // If password already has a known encoding prefix (e.g. {bcrypt}), use it as-is.
        // Otherwise, encode the plaintext password with bcrypt.
        String encodedPassword;
        if (password.matches("^\\{(bcrypt|scrypt|argon2|pbkdf2|noop|sha256)}.*")) {
            encodedPassword = password;
        } else {
            encodedPassword = "{bcrypt}" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                    .encode(password);
        }

        var user = User.builder()
                .username(username)
                .password(encodedPassword)
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
