package org.itech.ahb.pairing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates a request whose TLS client certificate is the paired OpenELIS. */
public class PairedPeerFilter extends OncePerRequestFilter {

  static final String PRINCIPAL = "openelis";
  private static final String CERTIFICATES = "jakarta.servlet.request.X509Certificate";

  private final PairingState pairing;

  public PairedPeerFilter(PairingState pairing) {
    this.pairing = pairing;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
    throws ServletException, IOException {
    if (request.getAttribute(CERTIFICATES) instanceof X509Certificate[] certificates && certificates.length > 0) {
      if (pairing.isPeer(BridgeIdentity.fingerprint(certificates[0]))) {
        SecurityContextHolder.getContext()
          .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
              PRINCIPAL,
              null,
              List.of(new SimpleGrantedAuthority("ROLE_BRIDGE"))
            )
          );
      }
    }
    chain.doFilter(request, response);
  }

  /** The client certificate of this request, if one was presented. */
  static X509Certificate clientCertificate(HttpServletRequest request) {
    return request.getAttribute(CERTIFICATES) instanceof X509Certificate[] certificates && certificates.length > 0
      ? certificates[0]
      : null;
  }
}
