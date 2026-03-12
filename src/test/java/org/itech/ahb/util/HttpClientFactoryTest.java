package org.itech.ahb.util;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpClientFactory}.
 * <p>
 * Verifies that the factory correctly creates an {@link HttpClient} that:
 * <ul>
 *   <li>Rejects self-signed certificates when {@code insecureTls=false} (default)</li>
 *   <li>Accepts self-signed certificates when {@code insecureTls=true}</li>
 * </ul>
 * </p>
 * <p>
 * Uses a pre-generated self-signed keystore ({@code test-server.p12}) to back a local
 * {@code HttpsServer}, eliminating any dependency on third-party certificate libraries.
 * </p>
 */
@DisplayName("HttpClientFactory Tests")
class HttpClientFactoryTest {

    private static HttpsServer httpsServer;
    private static int serverPort;

    @BeforeAll
    static void startSelfSignedServer() throws Exception {
        // Load the pre-generated self-signed keystore from test resources
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = HttpClientFactoryTest.class.getResourceAsStream("/test-server.p12")) {
            assertNotNull(is, "test-server.p12 must exist in test resources");
            ks.load(is, "changeit".toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());

        SSLContext serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, new SecureRandom());

        // Start an HTTPS server backed by the self-signed certificate
        httpsServer = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSslContext));
        httpsServer.createContext("/test", exchange -> {
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpsServer.start();
        serverPort = httpsServer.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
    }

    @Nested
    @DisplayName("Default TLS (insecureTls=false)")
    class DefaultTlsTests {

        @Test
        @DisplayName("Should reject self-signed certificate when insecureTls is false")
        void shouldRejectSelfSignedCert() {
            HttpClient client = HttpClientFactory.create(5, false, "test");
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + serverPort + "/test"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            IOException exception = assertThrows(IOException.class,
                () -> client.send(request, HttpResponse.BodyHandlers.ofString()),
                "Default HttpClient should reject a self-signed certificate");
            assertTrue(hasCauseOfType(exception, SSLException.class),
                "Failure should be TLS-related (SSLException in cause chain)");
        }
    }

    @Nested
    @DisplayName("Insecure TLS (insecureTls=true)")
    class InsecureTlsTests {

        @Test
        @DisplayName("Should accept self-signed certificate when insecureTls is true")
        void shouldAcceptSelfSignedCert() throws Exception {
            HttpClient client = HttpClientFactory.create(5, true, "test");
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + serverPort + "/test"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(),
                "Insecure TLS client should connect successfully to a self-signed endpoint");
        }
    }

    private static boolean hasCauseOfType(Throwable throwable, Class<? extends Throwable> expectedType) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
