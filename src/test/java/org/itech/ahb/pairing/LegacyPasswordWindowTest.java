package org.itech.ahb.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.util.FileSystemUtils;

/**
 * A Bridge upgraded in place keeps accepting its configured password, so an OpenELIS that has not
 * paired yet keeps working; the first pairing ends password access for good.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "server.ssl.enabled=true",
    "bridge.identity.directory=target/legacy-password-it/identity",
    "bridge.pairing.code=legacy-pairing-code-1",
    "bridge.security.username=bridge",
    "bridge.security.password=testpass",
    "bridge.outbox.db-path=target/legacy-password-it/outbox.db",
    "bridge.connection-catalog.directory=target/legacy-password-it/connections",
    "bridge.profile-catalog.directory=target/legacy-password-it/profile-catalog",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false",
  }
)
class LegacyPasswordWindowTest {

  static {
    FileSystemUtils.deleteRecursively(Path.of("target/legacy-password-it").toFile());
  }

  @LocalServerPort
  private int port;

  @Test
  void thePasswordWorksUntilTheFirstPairing() throws Exception {
    BridgeIdentity openElis = PairingTestClients.newIdentity(Path.of("target/legacy-password-it/openelis"));
    HttpClient asOpenElis = PairingTestClients.presenting(openElis);
    HttpClient anonymous = PairingTestClients.presenting(null);
    String credentials = "bridge:testpass";

    assertThat(PairingTestClients.get(anonymous, port, "/api/profiles", credentials).statusCode()).isEqualTo(200);
    assertThat(PairingTestClients.get(anonymous, port, "/api/profiles", "bridge:wrong").statusCode()).isEqualTo(401);

    assertThat(
      PairingTestClients.post(asOpenElis, port, "/pairing", "{\"code\":\"legacy-pairing-code-1\"}").statusCode()
    ).isEqualTo(200);

    assertThat(PairingTestClients.get(anonymous, port, "/api/profiles", credentials).statusCode()).isEqualTo(401);
    assertThat(PairingTestClients.get(asOpenElis, port, "/api/profiles", null).statusCode()).isEqualTo(200);
  }
}
