package org.itech.ahb.mllp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Component that triggers the MLLP server startup when the application starts.
 * <p>
 * This component is only activated when MLLP is enabled via configuration:
 * {@code org.itech.ahb.mllp.enabled=true}
 * </p>
 */
@Component
@ConditionalOnProperty(name = "org.itech.ahb.mllp.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(MLLPConfig.class)
@Slf4j
public class MLLPServerTrigger {

    private final MLLPServerRunner serverRunner;
    private final MLLPConfig mllpConfig;
    private final HTTPForwardServerConfigurationProperties httpConfig;
    private MLLPServer mllpServer;

    /**
     * Constructor for MLLPServerTrigger.
     *
     * @param serverRunner the MLLP server runner for async execution
     * @param mllpConfig the MLLP configuration properties
     * @param httpConfig the HTTP forward server configuration
     */
    public MLLPServerTrigger(
            MLLPServerRunner serverRunner,
            MLLPConfig mllpConfig,
            HTTPForwardServerConfigurationProperties httpConfig) {
        this.serverRunner = serverRunner;
        this.mllpConfig = mllpConfig;
        this.httpConfig = httpConfig;
    }

    /**
     * Starts the MLLP server after the component is initialized.
     */
    @PostConstruct
    public void startServer() {
        log.info("Starting MLLP server on port {} (enabled={})", mllpConfig.getPort(), mllpConfig.isEnabled());

        // Build forwarding URI for HL7 endpoint
        URI forwardUri = buildHL7ForwardingUri(httpConfig.getUri());

        // Create handler with HTTP forwarding configuration
        MLLPHandler handler;
        if (httpConfig.getUsername() != null && !httpConfig.getUsername().isEmpty()) {
            handler = new MLLPHandler(forwardUri, httpConfig.getUsername(), httpConfig.getPassword());
        } else {
            handler = new MLLPHandler(forwardUri);
        }

        // Create and start server
        mllpServer = new MLLPServer(mllpConfig, handler);
        serverRunner.run(mllpServer);
    }

    /**
     * Stops the MLLP server when the application shuts down.
     */
    @PreDestroy
    public void stopServer() {
        if (mllpServer != null) {
            log.info("Shutting down MLLP server...");
            mllpServer.stop();
            log.info("MLLP server stopped");
        }
    }

    /**
     * Builds the HL7 forwarding URI by replacing the path with /analyzer/hl7.
     * <p>
     * For example, if the base URI is {@code https://openelis:8443/api/OpenELIS-Global/analyzer},
     * this returns {@code https://openelis:8443/api/OpenELIS-Global/analyzer/hl7}.
     * </p>
     *
     * @param baseUri the base forwarding URI
     * @return the HL7 endpoint URI
     */
    private URI buildHL7ForwardingUri(URI baseUri) {
        String basePath = baseUri.getPath();
        if (basePath == null || basePath.isEmpty()) {
            basePath = "/analyzer";
        }
        // Ensure path ends without trailing slash, then append /hl7
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String hl7Path = basePath + "/hl7";

        try {
            return new URI(
                baseUri.getScheme(),
                baseUri.getUserInfo(),
                baseUri.getHost(),
                baseUri.getPort(),
                hl7Path,
                baseUri.getQuery(),
                baseUri.getFragment()
            );
        } catch (Exception e) {
            log.warn("Failed to build HL7 forwarding URI, using default path", e);
            return baseUri;
        }
    }
}
