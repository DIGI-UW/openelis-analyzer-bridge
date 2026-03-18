package org.itech.ahb.config;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
 *
 * @see org.itech.ahb.controller.AnalyzerInputController
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "bridge.security.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class SecurityConfig {

    @Value("${bridge.security.username:bridge}")
    private String username;

    @Value("${bridge.security.password:changeme}")
    private String password;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("Configuring bridge security: HTTP Basic auth for /input endpoint");

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Actuator endpoints: health and info are public, others require auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()
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
        return new BCryptPasswordEncoder();
    }
}
