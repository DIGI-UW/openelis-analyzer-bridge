package org.itech.ahb.pairing;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Trusts the paired OpenELIS's server certificate by its pinned fingerprint, and otherwise any
 * certificate the system trust store validates for the host, so a site that serves OpenELIS with a
 * publicly trusted certificate keeps working when it renews it.
 */
final class PinnedServerTrustManager extends X509ExtendedTrustManager {

  private final PairingState pairing;
  private final X509ExtendedTrustManager system;

  PinnedServerTrustManager(PairingState pairing, X509ExtendedTrustManager system) {
    this.pairing = pairing;
    this.system = system;
  }

  private boolean pinned(X509Certificate[] chain) {
    return chain != null && chain.length > 0 && pairing.trustsServer(BridgeIdentity.fingerprint(chain[0]));
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
    throws CertificateException {
    if (!pinned(chain)) system.checkServerTrusted(chain, authType, engine);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
    throws CertificateException {
    if (!pinned(chain)) system.checkServerTrusted(chain, authType, socket);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    if (!pinned(chain)) system.checkServerTrusted(chain, authType);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
    throws CertificateException {
    throw new CertificateException("Only server certificates are checked here");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
    throws CertificateException {
    throw new CertificateException("Only server certificates are checked here");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    throw new CertificateException("Only server certificates are checked here");
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return system.getAcceptedIssuers();
  }
}
