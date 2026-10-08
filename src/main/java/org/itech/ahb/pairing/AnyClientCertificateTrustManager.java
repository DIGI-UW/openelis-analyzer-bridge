package org.itech.ahb.pairing;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Lets the server's TLS handshake accept any client certificate. The handshake still proves the
 * client holds the certificate's key; whether that certificate is the paired OpenELIS is decided by
 * its fingerprint in {@link PairedPeerFilter}. Tomcat instantiates this class by name.
 */
public class AnyClientCertificateTrustManager extends X509ExtendedTrustManager {

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType) {}

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    throw new CertificateException("This trust manager only answers for client certificates");
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
    throws CertificateException {
    checkServerTrusted(chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
    throws CertificateException {
    checkServerTrusted(chain, authType);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[0];
  }
}
