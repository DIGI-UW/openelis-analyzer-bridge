package org.itech.ahb.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Shared HTTP client for OpenELIS REST API calls from the bridge.
 * Reuses the same base URL, auth, and TLS config as the forwarding router.
 */
@Component
@Slf4j
public class OeApiClient {

    private final HTTPForwardServerConfigurationProperties httpConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OeApiClient(HTTPForwardServerConfigurationProperties httpConfig) {
        this.httpConfig = httpConfig;
    }

    /**
     * POST JSON to an OE REST API path. Returns the response body as a Map,
     * or null on failure.
     */
    public Map<String, Object> post(String restPath, Map<String, String> body) {
        String baseUrl = deriveOeBaseUrl();
        if (baseUrl == null) {
            log.warn("No OE base URL — cannot call {}", restPath);
            return null;
        }

        String url = baseUrl + restPath;
        try {
            HttpClient client = HttpClientFactory.create(
                    httpConfig.getConnectTimeoutSeconds(),
                    httpConfig.isInsecureTls(),
                    "oe-api-client");

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofSeconds(httpConfig.getReadTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");

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

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(
                        response.body(), Map.class);
                return result;
            } else {
                log.warn("OE returned {} for POST {}: {}", response.statusCode(), url, response.body());
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to POST to OE {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String deriveOeBaseUrl() {
        URI uri = httpConfig.getUri();
        if (uri == null) {
            return null;
        }
        return uri.toString().replaceAll("/+$", "").replaceAll("/analyzer$", "");
    }
}
