package org.itech.ahb.startup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.config.OpenELISConfig;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.util.HttpClientFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pulls the current analyzer registry from OpenELIS on bridge startup.
 *
 * <p>This ensures the bridge has a complete analyzer registry even after a
 * restart, without requiring OE to push or periodic polling. OE is the
 * authoritative source (PostgreSQL). The bridge pulls once on startup.
 *
 * <p>Three sync scenarios:
 * <ol>
 *   <li>OE startup → OE pushes to bridge (existing AnalyzerBridgeStartupRegistrar)</li>
 *   <li>Analyzer CRUD → OE pushes immediately (existing registerWithBridgeAsync)</li>
 *   <li>Bridge restart → bridge pulls from OE (this component)</li>
 * </ol>
 */
@Component
@Slf4j
public class AnalyzerRegistryBootstrap {

    private final AnalyzerRegistryConfig registry;
    private final HTTPForwardServerConfigurationProperties httpConfig;
    private final OpenELISConfig openelisConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyzerRegistryBootstrap(
            AnalyzerRegistryConfig registry,
            HTTPForwardServerConfigurationProperties httpConfig,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            OpenELISConfig openelisConfig) {
        this.registry = registry;
        this.httpConfig = httpConfig;
        this.openelisConfig = openelisConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pullAnalyzersFromOE() {
        URI oeBaseUri = httpConfig.getUri();
        if (oeBaseUri == null) {
            log.warn("No OpenELIS URI configured — skipping analyzer registry bootstrap");
            return;
        }

        // Build the analyzers API URL from the OE base URI
        // OE base: https://oe:8443/OpenELIS-Global
        // API:     https://oe:8443/OpenELIS-Global/rest/analyzer/analyzers (via /api/ prefix removed)
        String baseUrl = oeBaseUri.toString().replaceAll("/+$", "");
        String analyzersUrl = baseUrl + "/rest/analyzer/analyzers";

        log.info("Pulling analyzer registry from OE: {}", analyzersUrl);

        try {
            int connectTimeout = openelisConfig != null
                    ? openelisConfig.getConnectTimeoutSeconds() : 30;
            int readTimeout = openelisConfig != null
                    ? openelisConfig.getReadTimeoutSeconds() : 30;

            HttpClient client = HttpClientFactory.create(
                    connectTimeout,
                    httpConfig.isInsecureTls(),
                    "registry-bootstrap");

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(analyzersUrl))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(readTimeout))
                    .header("Accept", "application/json");

            // Add Basic auth
            if (httpConfig.getUsername() != null && httpConfig.getPassword() != null) {
                String credentials = httpConfig.getUsername() + ":"
                        + new String(httpConfig.getPassword());
                String encoded = Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + encoded);
            }

            HttpResponse<String> response = client.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("OE returned {} for analyzer pull — registry not bootstrapped",
                        response.statusCode());
                return;
            }

            // Parse response: {"analyzers": [...]}
            Map<String, Object> body = objectMapper.readValue(
                    response.body(), new TypeReference<>() {
                    });

            Object analyzersObj = body.get("analyzers");
            if (!(analyzersObj instanceof List)) {
                log.warn("Unexpected response format — no 'analyzers' array");
                return;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> analyzers = (List<Map<String, Object>>) analyzersObj;

            java.util.LinkedHashMap<String, AnalyzerEntry> newRegistry = new java.util.LinkedHashMap<>();
            for (Map<String, Object> analyzer : analyzers) {
                String id = String.valueOf(analyzer.get("id"));
                String name = (String) analyzer.get("name");
                String ip = (String) analyzer.get("ipAddress");
                String protocol = (String) analyzer.get("protocolVersion");

                if (ip != null && !ip.isBlank()) {
                    AnalyzerEntry entry = new AnalyzerEntry();
                    entry.setId(id);
                    entry.setName(name);
                    entry.setExpectedProtocol(
                            protocol != null && protocol.contains("HL7") ? "HL7" : "ASTM");
                    newRegistry.put(ip, entry);
                }
            }

            if (!newRegistry.isEmpty()) {
                AnalyzerRegistryConfig.SyncResult result = registry.syncAll(newRegistry);
                log.info("Bootstrap complete: pulled {} analyzers from OE ({} added, {} updated)",
                        result.total(), result.added(), result.updated());
            } else {
                log.info("Bootstrap: no TCP analyzers found in OE — registry empty");
            }

        } catch (java.net.ConnectException e) {
            log.warn("Cannot reach OE at {} — bridge starting without analyzer registry. "
                    + "OE will push registrations when it starts.", analyzersUrl);
        } catch (Exception e) {
            log.warn("Failed to pull analyzers from OE: {} — bridge starting without registry. "
                    + "OE will push registrations on next CRUD operation.", e.getMessage());
        }
    }
}
