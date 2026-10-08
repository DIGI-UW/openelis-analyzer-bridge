package org.itech.ahb.pairing;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * TLS for calls to the paired OpenELIS: present the Bridge certificate, trust OpenELIS by its
 * pinned fingerprint. Absent until the Bridge is paired, so callers keep their unpaired behaviour.
 */
@Component
public class OpenElisClients {

  private final PairingState pairing;
  private final Supplier<BridgeIdentity> identity;
  private SSLContext context;
  private int contextVersion = -1;

  public OpenElisClients(PairingState pairing, Supplier<BridgeIdentity> identity) {
    this.pairing = pairing;
    this.identity = identity;
  }

  @Autowired
  public OpenElisClients(PairingState pairing, ObjectProvider<BridgeIdentity> identity) {
    this(pairing, identity::getIfAvailable);
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
      factory.init((KeyStore) null);
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
}
