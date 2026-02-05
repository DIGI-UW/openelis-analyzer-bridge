package org.itech.ahb.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for OpenELIS integration.
 * <p>
 * Configures HTTP communication with OpenELIS including base URL, timeouts,
 * and retry settings.
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "bridge.openelis")
@ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true")
@Data
public class OpenELISConfig {

    /**
     * Base URL for OpenELIS instance
     * Default: http://localhost:8443
     */
    private String url = "http://localhost:8443";

    /**
     * HTTP connection timeout in seconds
     * Default: 30 seconds
     */
    private int connectTimeoutSeconds = 30;

    /**
     * HTTP read timeout in seconds
     * Default: 30 seconds
     */
    private int readTimeoutSeconds = 30;

    /**
     * Retry configuration for failed HTTP requests
     */
    private RetryConfig retry = new RetryConfig();

    /**
     * Retry settings for OpenELIS HTTP communication
     */
    @Data
    public static class RetryConfig {
        /**
         * Maximum number of retry attempts
         * Default: 3
         */
        private int maxAttempts = 3;

        /**
         * Initial backoff delay in milliseconds (exponential backoff)
         * Default: 1000ms (1 second)
         */
        private long backoffMs = 1000;
    }
}
