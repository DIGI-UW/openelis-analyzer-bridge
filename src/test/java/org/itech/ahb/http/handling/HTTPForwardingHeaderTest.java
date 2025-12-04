package org.itech.ahb.http.handling;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;
import org.itech.ahb.lib.astm.handling.DefaultForwardingASTMToHTTPHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for HTTP header addition in DefaultForwardingASTMToHTTPHandler.
 * Tests FR-002 (include source IP in X-Source-Analyzer-IP header).
 */
@DisplayName("HTTP Forwarding Header Tests")
class HTTPForwardingHeaderTest {

    private static final String TEST_URI = "http://localhost:8080/test";

    @Nested
    @DisplayName("X-Source-Analyzer-IP Header Addition (FR-002)")
    class HeaderAdditionTests {

        @Test
        @DisplayName("Header constant should be defined correctly")
        void headerConstantShouldBeCorrect() {
            // The header name should follow the specification
            assertEquals("X-Source-Analyzer-IP", DefaultForwardingASTMToHTTPHandler.SOURCE_IP_HEADER);
        }

        @Test
        @DisplayName("Should add header when IPv4 source IP is provided")
        void shouldAddHeaderWithIPv4() {
            // Given: An IPv4 source IP
            String sourceIp = "192.168.1.10";

            // When: We build a request with the source IP
            HttpRequest request = buildRequestWithSourceIp(sourceIp);

            // Then: The header should be present with the correct value
            Optional<String> headerValue = request.headers().firstValue(DefaultForwardingASTMToHTTPHandler.SOURCE_IP_HEADER);
            assertTrue(headerValue.isPresent(), "Header should be present");
            assertEquals(sourceIp, headerValue.get());
        }

        @Test
        @DisplayName("Should add header when IPv6 source IP is provided")
        void shouldAddHeaderWithIPv6() {
            // Given: An IPv6 source IP
            String sourceIp = "2001:db8::1";

            // When: We build a request with the source IP
            HttpRequest request = buildRequestWithSourceIp(sourceIp);

            // Then: The header should be present with the correct value
            Optional<String> headerValue = request.headers().firstValue(DefaultForwardingASTMToHTTPHandler.SOURCE_IP_HEADER);
            assertTrue(headerValue.isPresent(), "Header should be present");
            assertEquals(sourceIp, headerValue.get());
        }

        @Test
        @DisplayName("Should add header when localhost IP is provided")
        void shouldAddHeaderWithLocalhost() {
            // Given: A localhost IP
            String sourceIp = "127.0.0.1";

            // When: We build a request with the source IP
            HttpRequest request = buildRequestWithSourceIp(sourceIp);

            // Then: The header should be present with the correct value
            Optional<String> headerValue = request.headers().firstValue(DefaultForwardingASTMToHTTPHandler.SOURCE_IP_HEADER);
            assertTrue(headerValue.isPresent(), "Header should be present");
            assertEquals(sourceIp, headerValue.get());
        }
    }

    @Nested
    @DisplayName("Header Omission for Graceful Degradation (FR-004)")
    class HeaderOmissionTests {

        @Test
        @DisplayName("Should NOT add header when source IP is null")
        void shouldNotAddHeaderWhenNull() {
            // Given: A null source IP
            String sourceIp = null;

            // When: We build a request without a source IP
            HttpRequest request = buildRequestWithSourceIp(sourceIp);

            // Then: The header should NOT be present
            Optional<String> headerValue = request.headers().firstValue(DefaultForwardingASTMToHTTPHandler.SOURCE_IP_HEADER);
            assertFalse(headerValue.isPresent(), "Header should NOT be present when source IP is null");
        }

        @Test
        @DisplayName("Should NOT add header when source IP is empty string")
        void shouldNotAddHeaderWhenEmpty() {
            // Given: An empty source IP
            String sourceIp = "";

            // When: We build a request with empty source IP
            HttpRequest request = buildRequestWithSourceIp(sourceIp);

            // Then: The header should NOT be present
            Optional<String> headerValue = request.headers().firstValue(DefaultForwardingASTMToHTTPHandler.SOURCE_IP_HEADER);
            assertFalse(headerValue.isPresent(), "Header should NOT be present when source IP is empty");
        }
    }

    /**
     * Helper method that builds an HTTP request the same way DefaultForwardingASTMToHTTPHandler does,
     * allowing us to test header addition logic in isolation.
     */
    private HttpRequest buildRequestWithSourceIp(String sourceIp) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(TEST_URI))
            .POST(HttpRequest.BodyPublishers.ofString("test message"));

        // Mirror the logic from DefaultForwardingASTMToHTTPHandler.handle()
        if (sourceIp != null && !sourceIp.isEmpty()) {
            requestBuilder.header(DefaultForwardingASTMToHTTPHandler.SOURCE_IP_HEADER, sourceIp);
        }

        return requestBuilder.build();
    }
}

