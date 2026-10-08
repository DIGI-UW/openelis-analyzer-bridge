package org.itech.ahb.pairing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** TLS clients for pairing tests: each may present a certificate, and none checks the Bridge's. */
final class PairingTestClients {

  private PairingTestClients() {}

  static HttpClient presenting(BridgeIdentity identity) throws Exception {
    return client(identity == null ? null : identity.keyManagers());
  }

  static BridgeIdentity newIdentity(Path directory) {
    return BridgeIdentity.load(BridgeIdentity.ensureGenerated(directory));
  }

  private static HttpClient client(KeyManager[] keyManagers) throws Exception {
    TrustManager[] trustAll = new TrustManager[] {
      new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      },
    };
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers, trustAll, new SecureRandom());
    SSLParameters parameters = new SSLParameters();
    parameters.setEndpointIdentificationAlgorithm(null);
    return HttpClient.newBuilder()
      .sslContext(context)
      .sslParameters(parameters)
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  }

  static HttpResponse<String> get(HttpClient client, int port, String path, String basic) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path)).GET();
    if (basic != null) request.header("Authorization", "Basic " + encode(basic));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  static HttpResponse<String> post(HttpClient client, int port, String path, String json) throws Exception {
    return client.send(
      HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  private static String encode(String credentials) {
    return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
