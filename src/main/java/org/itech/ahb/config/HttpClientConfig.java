package org.itech.ahb.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP client configuration for the bridge.
 * <p>
 * Configures RestTemplate beans for HTTP communication with OpenELIS and other services.
 * </p>
 */
@Configuration
public class HttpClientConfig {

    /**
     * RestTemplate bean for file watcher HTTP forwarding.
     * <p>
     * Only created when file watcher is enabled.
     * Configured with timeouts from OpenELISConfig.
     * </p>
     *
     * @param builder Spring-provided RestTemplate builder
     * @param openelisConfig OpenELIS configuration with timeout settings
     * @return configured RestTemplate instance
     */
    @Bean
    @ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true")
    public RestTemplate fileWatcherRestTemplate(RestTemplateBuilder builder, OpenELISConfig openelisConfig) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(openelisConfig.getConnectTimeoutSeconds()))
                .setReadTimeout(Duration.ofSeconds(openelisConfig.getReadTimeoutSeconds()))
                .build();
    }

    /**
     * General-purpose RestTemplate bean for bridge HTTP operations.
     * <p>
     * Used by components that need HTTP client but aren't file-watcher-specific.
     * </p>
     *
     * @param builder Spring-provided RestTemplate builder
     * @return configured RestTemplate instance
     */
    @Bean
    public RestTemplate bridgeRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }
}
