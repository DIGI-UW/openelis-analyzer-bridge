package org.itech.ahb.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeIdentityTest {

  @TempDir
  Path directory;

  @Test
  void aBridgeWithoutAKeystoreGeneratesItsOwnIdentityOnceAndKeepsIt() throws Exception {
    BridgeIdentity.Keystore generated = BridgeIdentity.ensureGenerated(directory);

    assertThat(generated.path()).exists();
    assertThat(
      Files.getPosixFilePermissions(directory.resolve(BridgeIdentity.PASSWORD_FILE))
    ).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    BridgeIdentity identity = BridgeIdentity.load(generated);
    assertThat(identity.certificate().getSubjectX500Principal().getName()).contains("openelis-analyzer-bridge");
    Path shared = directory.resolve("bridge.crt");
    Files.writeString(shared, "old certificate");
    identity.exportCertificate(shared);
    assertThat(Files.readString(shared)).isEqualTo(identity.certificatePem());
    assertThat(Files.getPosixFilePermissions(shared)).contains(PosixFilePermission.OTHERS_READ);
    assertThat(identity.fingerprint()).matches("[0-9a-f]{64}");
    assertThat(identity.certificatePem()).startsWith("-----BEGIN CERTIFICATE-----");

    BridgeIdentity again = BridgeIdentity.load(BridgeIdentity.ensureGenerated(directory));
    assertThat(again.fingerprint()).isEqualTo(identity.fingerprint());
  }
}
