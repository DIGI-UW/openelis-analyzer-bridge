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
 *
 * <p>Used by {@link org.itech.ahb.normalizer.MessageNormalizer} for discovered-source
 * reporting and by {@link org.itech.ahb.startup.AnalyzerRegistryBootstrap} for
 * registry pull on startup.
 */
@Component
@Slf4j
public class OeApiClient {

    private final HTTPForwardServerConfigurationProperties httpConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public OeApiClient(HTTPForwardServerConfigurationProperties httpConfig) {
        this.httpConfig = httpConfig;
        this.httpClient = HttpClientFactory.create(
                httpConfig.getConnectTimeoutSeconds(),
                httpConfig.isInsecureTls(),
                "oe-api-client");
    }

    /** Returns true if the OE base URL is configured. */
    public boolean isConfigured() {
        return httpConfig.getUri() != null;
    }

    /**
     * GET an OE REST API path. Returns the raw response body as a String,
     * or null on non-2xx / failure.
     */
    public String getString(String restPath) {
        String baseUrl = deriveOeBaseUrl();
        if (baseUrl == null) {
            log.warn("No OE base URL — cannot GET {}", restPath);
            return null;
        }
        String url = baseUrl + restPath;
        try {
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            log.warn("OE returned {} for GET {}", response.statusCode(), url);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during GET {}", url);
            return null;
        } catch (Exception e) {
            log.warn("Failed to GET OE {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * POST JSON to an OE REST API path. Returns the response body as a Map,
     * or null on failure.
     */
    public Map<String, Object> post(String restPath, Map<String, String> body) {
        String baseUrl = deriveOeBaseUrl();
        if (baseUrl == null) {
            log.warn("No OE base URL — cannot POST {}", restPath);
            return null;
        }
        String url = baseUrl + restPath;
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = newRequestBuilder(url)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                return result;
            }
            log.warn("OE returned {} for POST {}: {}", response.statusCode(), url, response.body());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during POST {}", url);
            return null;
        } catch (Exception e) {
            log.warn("Failed to POST to OE {}: {}", url, e.getMessage());
            return null;
        }
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(httpConfig.getReadTimeoutSeconds()))
                .header("Accept", "application/json");

        if (httpConfig.getUsername() != null && httpConfig.getPassword() != null) {
            String credentials = httpConfig.getUsername() + ":" + new String(httpConfig.getPassword());
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }
        return builder;
    }

    private String deriveOeBaseUrl() {
        URI uri = httpConfig.getUri();
        if (uri == null) {
            return null;
        }
        return uri.toString().replaceAll("/+$", "").replaceAll("/analyzer$", "");
    }
}
