package org.itech.ahb.pairing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TLS for calls to the paired OpenELIS: present the Bridge certificate, trust OpenELIS by its
 * pinned fingerprint. Absent until the Bridge is paired, so callers keep their unpaired behaviour.
 */
@Component
@Slf4j
public class OpenElisClients {

  /**
   * The store that also trusts OpenELIS when its certificate is not the pinned one, such as after
   * OpenELIS regenerates it. Read from Bridge settings so its password never reaches the JVM
   * command line, which the JVM prints at startup.
   */
  public record TrustStore(Path path, String type, char[] password) {}

  private final PairingState pairing;
  private final Supplier<BridgeIdentity> identity;
  private final TrustStore trustStore;
  private SSLContext context;
  private int contextVersion = -1;

  public OpenElisClients(PairingState pairing, Supplier<BridgeIdentity> identity) {
    this(pairing, identity, null);
  }

  public OpenElisClients(PairingState pairing, Supplier<BridgeIdentity> identity, TrustStore trustStore) {
    this.pairing = pairing;
    this.identity = identity;
    this.trustStore = trustStore;
  }

  @Autowired
  public OpenElisClients(
    PairingState pairing,
    ObjectProvider<BridgeIdentity> identity,
    @Value("${bridge.truststore.path:}") String trustStorePath,
    @Value("${bridge.truststore.type:PKCS12}") String trustStoreType,
    @Value("${bridge.truststore.password:}") String trustStorePassword
  ) {
    this(
      pairing,
      identity::getIfAvailable,
      trustStorePath.isBlank()
        ? null
        : new TrustStore(Path.of(trustStorePath.trim()), trustStoreType, trustStorePassword.toCharArray())
    );
  }

  /** The TLS context for the current pairing; the same instance until OpenELIS pairs again. */
  public synchronized Optional<SSLContext> sslContext() {
    BridgeIdentity bridge = identity.get();
    if (!pairing.isPaired() || bridge == null) {
      return Optional.empty();
    }
    if (context == null || contextVersion != pairing.version()) {
      context = build(bridge);
      contextVersion = pairing.version();
    }
    return Optional.of(context);
  }

  private SSLContext build(BridgeIdentity bridge) {
    try {
      TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(trusted());
      X509ExtendedTrustManager system = null;
      for (TrustManager manager : factory.getTrustManagers()) {
        if (manager instanceof X509ExtendedTrustManager extended) system = extended;
      }
      if (system == null) throw new IllegalStateException("No system X.509 trust manager");
      SSLContext built = SSLContext.getInstance("TLS");
      built.init(bridge.keyManagers(), new TrustManager[] { new PinnedServerTrustManager(pairing, system) }, null);
      return built;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot build TLS for the paired OpenELIS", e);
    }
  }

  /** The configured store, or null for the JVM's own trust when none is configured or it cannot be read. */
  private KeyStore trusted() {
    if (trustStore == null) {
      return null;
    }
    try (InputStream in = Files.newInputStream(trustStore.path())) {
      KeyStore store = KeyStore.getInstance(trustStore.type());
      store.load(in, trustStore.password());
      return store;
    } catch (IOException | GeneralSecurityException e) {
      log.error(
        "Cannot read the trust store {}; OpenELIS is trusted only by its pinned certificate and the JVM's own trust",
        trustStore.path(),
        e
      );
      return null;
    }
  }
}
