package org.itech.ahb.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.util.FileSystemUtils;

/**
 * A Bridge without a mounted keystore or a password: it generates its identity, serves only health
 * and pairing until OpenELIS pairs with the one-time code, and then accepts that OpenELIS's
 * certificate and nothing else.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "server.ssl.enabled=true",
    "bridge.identity.directory=target/pairing-security-it/identity",
    "bridge.pairing.code=it-pairing-code-1234",
    "bridge.outbox.db-path=target/pairing-security-it/outbox.db",
    "bridge.connection-catalog.directory=target/pairing-security-it/connections",
    "bridge.profile-catalog.directory=target/pairing-security-it/profile-catalog",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false",
  }
)
class PairingSecurityTest {

  static {
    FileSystemUtils.deleteRecursively(Path.of("target/pairing-security-it").toFile());
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort
  private int port;

  @Test
  void onlyThePairedOpenElisIsAcceptedAndOnlyAfterPairing() throws Exception {
    BridgeIdentity openElis = PairingTestClients.newIdentity(Path.of("target/pairing-security-it/openelis"));
    BridgeIdentity stranger = PairingTestClients.newIdentity(Path.of("target/pairing-security-it/stranger"));
    HttpClient asOpenElis = PairingTestClients.presenting(openElis);
    HttpClient asStranger = PairingTestClients.presenting(stranger);
    HttpClient anonymous = PairingTestClients.presenting(null);

    assertThat(PairingTestClients.get(anonymous, port, "/actuator/health", null).statusCode()).isEqualTo(200);
    assertThat(PairingTestClients.get(asOpenElis, port, "/api/profiles", null).statusCode()).isEqualTo(401);
    assertThat(PairingTestClients.get(anonymous, port, "/api/profiles", "bridge:").statusCode()).isEqualTo(401);
    JsonNode status = JSON.readTree(PairingTestClients.get(anonymous, port, "/pairing", null).body());
    assertThat(status.path("paired").asBoolean()).isFalse();
    assertThat(status.path("open").asBoolean()).isTrue();

    String request = "{\"code\":\"it-pairing-code-1234\"}";
    assertThat(PairingTestClients.post(anonymous, port, "/pairing", request).statusCode()).isEqualTo(400);
    assertThat(PairingTestClients.post(asOpenElis, port, "/pairing", "{\"code\":\"wrong\"}").statusCode())
      .isEqualTo(403);

    HttpResponse<String> paired = PairingTestClients.post(asOpenElis, port, "/pairing", request);
    assertThat(paired.statusCode()).isEqualTo(200);
    X509Certificate served = (X509Certificate) paired.sslSession().orElseThrow().getPeerCertificates()[0];
    assertThat(JSON.readTree(paired.body()).path("bridgeCertificateSha256").asText())
      .isEqualTo(BridgeIdentity.fingerprint(served));

    assertThat(PairingTestClients.get(asOpenElis, port, "/api/profiles", null).statusCode()).isEqualTo(200);
    assertThat(PairingTestClients.get(asStranger, port, "/api/profiles", null).statusCode()).isEqualTo(401);
    assertThat(PairingTestClients.get(anonymous, port, "/api/profiles", null).statusCode()).isEqualTo(401);
    assertThat(PairingTestClients.post(asStranger, port, "/pairing", request).statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(PairingTestClients.get(anonymous, port, "/pairing", null).body()).path("paired").asBoolean())
      .isTrue();
  }
}
