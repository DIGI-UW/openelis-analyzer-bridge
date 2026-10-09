package org.itech.ahb.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.outbox.DeliveryOutcome;
import org.itech.ahb.outbox.FhirDeliveryClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Once paired, delivery presents the Bridge certificate and trusts OpenELIS by its pinned fingerprint. */
class PairedDeliveryTest {

  @TempDir
  Path directory;

  private HttpsServer openElis;
  private final AtomicReference<String> presented = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();

  @AfterEach
  void stop() {
    if (openElis != null) openElis.stop(0);
  }

  private BridgeIdentity startOpenElis() throws Exception {
    BridgeIdentity server = PairingTestClients.newIdentity(directory.resolve("openelis-server"));
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(server.keyManagers(), new TrustManager[] { new AnyClientCertificateTrustManager() }, new SecureRandom());
    openElis = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    openElis.setHttpsConfigurator(
      new HttpsConfigurator(context) {
        @Override
        public void configure(HttpsParameters parameters) {
          javax.net.ssl.SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
          ssl.setNeedClientAuth(true);
          parameters.setSSLParameters(ssl);
        }
      }
    );
    openElis.createContext("/analyzer/fhir", exchange -> {
      X509Certificate client = (X509Certificate) ((HttpsExchange) exchange).getSSLSession().getPeerCertificates()[0];
      presented.set(BridgeIdentity.fingerprint(client));
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getRequestBody().readAllBytes();
      byte[] answer = "{\"receiptId\":\"r-1\"}".getBytes();
      exchange.sendResponseHeaders(200, answer.length);
      exchange.getResponseBody().write(answer);
      exchange.close();
    });
    openElis.start();
    return server;
  }

  private FhirDeliveryClient client(PairingState pairing, BridgeIdentity bridge) {
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("https://localhost:" + openElis.getAddress().getPort()));
    config.setUsername("legacy");
    config.setPassword("testpass".toCharArray());
    config.setConnectTimeoutSeconds(2);
    config.setReadTimeoutSeconds(5);
    return new FhirDeliveryClient(config, new OpenElisClients(pairing, () -> bridge));
  }

  @Test
  void aPairedBridgeDeliversWithItsCertificateToThePinnedOpenElis() throws Exception {
    BridgeIdentity server = startOpenElis();
    BridgeIdentity bridge = PairingTestClients.newIdentity(directory.resolve("bridge"));
    PairingState pairing = new PairingState(directory.resolve("pairing"), "code-for-delivery");
    pairing.pair("code-for-delivery", "c".repeat(64), server.fingerprint());

    DeliveryOutcome outcome = client(pairing, bridge).deliver("{}");

    assertThat(outcome.httpStatus()).as(String.valueOf(outcome.describeFailure())).isEqualTo(200);
    assertThat(presented.get()).isEqualTo(bridge.fingerprint());
    assertThat(authorization.get()).as("no password once paired").isNull();
  }

  @Test
  void aServerThatIsNotThePinnedOpenElisIsRefused() throws Exception {
    startOpenElis();
    BridgeIdentity bridge = PairingTestClients.newIdentity(directory.resolve("bridge"));
    PairingState pairing = new PairingState(directory.resolve("pairing"), "code-for-delivery");
    pairing.pair("code-for-delivery", "c".repeat(64), "d".repeat(64));

    DeliveryOutcome outcome = client(pairing, bridge).deliver("{}");

    assertThat(outcome.reachedOpenElis()).isFalse();
    assertThat(presented.get()).isNull();
  }

  @Test
  void theOpenElisHealthProbeUsesThePairedIdentityToo() throws Exception {
    BridgeIdentity server = startOpenElis();
    openElis.createContext("/health", exchange -> {
      ((HttpsExchange) exchange).getSSLSession().getPeerCertificates();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    BridgeIdentity bridge = PairingTestClients.newIdentity(directory.resolve("bridge"));
    PairingState pairing = new PairingState(directory.resolve("pairing"), "code-for-health");
    pairing.pair("code-for-health", "c".repeat(64), server.fingerprint());
    HTTPForwardServerConfigurationProperties config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("https://localhost:" + openElis.getAddress().getPort()));

    var health = new org.itech.ahb.health.HTTPForwardServerHealthIndicator(
      config,
      new OpenElisClients(pairing, () -> bridge)
    ).health();

    assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
  }
}
