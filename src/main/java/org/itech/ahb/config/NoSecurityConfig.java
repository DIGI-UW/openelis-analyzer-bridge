package org.itech.ahb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Fallback security configuration when bridge security is explicitly disabled.
 * <p>
 * Activated when {@code bridge.security.enabled=false}. Permits all requests
 * without authentication. Not recommended for production use.
 * </p>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "bridge.security.enabled", havingValue = "false")
@Slf4j
public class NoSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.warn("Bridge security is DISABLED — all endpoints are unauthenticated");

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
