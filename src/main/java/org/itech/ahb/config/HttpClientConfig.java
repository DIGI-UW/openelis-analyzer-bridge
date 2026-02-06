package org.itech.ahb.config;

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
 * <p>
 * NOTE: After M7 (Message Normalizer) refactoring, file watcher no longer uses RestTemplate
 * (delegates to MessageNormalizer → HttpForwardingRouter which uses java.net.http.HttpClient).
 * The fileWatcherRestTemplate bean was removed as it has no consumers.
 * </p>
 */
@Configuration
public class HttpClientConfig {

    /**
     * General-purpose RestTemplate bean for bridge HTTP operations.
     * <p>
     * Used by components that need HTTP client but aren't file-watcher-specific.
     * May be used in future for non-analyzer HTTP operations.
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
