package org.itech.ahb.util;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating {@link HttpClient} instances with optional insecure TLS support.
 * <p>
 * When {@code insecureTls} is {@code true}, certificate validation and hostname verification
 * are disabled. This is intended only for development environments with self-signed certificates.
 * </p>
 */
@Slf4j
public final class HttpClientFactory {

    private HttpClientFactory() {
    }

    /**
     * Creates an {@link HttpClient} with the given connect timeout.
     * When {@code insecureTls} is {@code true}, TLS certificate and hostname
     * verification are disabled (development only).
     *
     * @param connectTimeoutSeconds the connect timeout in seconds
     * @param insecureTls           if {@code true}, disables TLS verification
     * @param logContext            a short label used in log messages (e.g. "forwarding", "healthcheck")
     * @return a configured {@link HttpClient}
     */
    public static HttpClient create(int connectTimeoutSeconds, boolean insecureTls, String logContext) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds));

        if (insecureTls) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(
                                java.security.cert.X509Certificate[] chain, String authType) {
                        }
                    }
                };
                sslContext.init(null, trustAllCerts, new SecureRandom());
                SSLParameters sslParameters = new SSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm(null);
                builder.sslContext(sslContext);
                builder.sslParameters(sslParameters);
                log.warn("HTTP {} TLS verification disabled (insecureTls=true)", logContext);
            } catch (Exception e) {
                log.error("Failed to initialize insecure TLS HTTP client for {}; using default TLS validation",
                    logContext, e);
            }
        }
        return builder.build();
    }
}
