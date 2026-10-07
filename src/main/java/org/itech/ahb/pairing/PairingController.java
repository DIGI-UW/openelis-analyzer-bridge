package org.itech.ahb.pairing;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pairing with one OpenELIS. OpenELIS sends the pairing code over TLS while presenting its client
 * certificate; the Bridge pins that certificate, and the certificate OpenELIS serves, and answers
 * with its own so OpenELIS can pin it in turn.
 */
@RestController
@RequestMapping("/pairing")
@Slf4j
public class PairingController {

  private final PairingState pairing;
  private final ObjectProvider<BridgeIdentity> identity;

  public PairingController(PairingState pairing, ObjectProvider<BridgeIdentity> identity) {
    this.pairing = pairing;
    this.identity = identity;
  }

  @GetMapping
  public Map<String, Object> status() {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("paired", pairing.isPaired());
    status.put("open", pairing.isOpen());
    return status;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> pair(@RequestBody JsonNode body, HttpServletRequest request) {
    BridgeIdentity bridge = identity.getIfAvailable();
    if (bridge == null) {
      return error(503, "tls_disabled", "Pairing needs HTTPS; enable server.ssl");
    }
    X509Certificate client = PairedPeerFilter.clientCertificate(request);
    if (client == null) {
      return error(400, "client_certificate_required", "Present the OpenELIS client certificate to pair");
    }
    String clientFingerprint = BridgeIdentity.fingerprint(client);
    String serverFingerprint = body.path("serverCertificateSha256").asText(clientFingerprint);
    if (!serverFingerprint.matches("[0-9a-fA-F]{64}")) {
      return error(400, "invalid_server_certificate", "serverCertificateSha256 must be 64 hexadecimal characters");
    }
    return switch (pairing.pair(body.path("code").asText(""), clientFingerprint, serverFingerprint)) {
      case PAIRED -> {
        log.info(
          "Paired with OpenELIS: client certificate {}, server certificate {}",
          clientFingerprint,
          serverFingerprint
        );
        Map<String, Object> paired = new LinkedHashMap<>();
        paired.put("paired", true);
        paired.put("bridgeCertificateSha256", bridge.fingerprint());
        paired.put("bridgeCertificatePem", bridge.certificatePem());
        yield ResponseEntity.ok(paired);
      }
      case WRONG_CODE -> {
        log.warn("Rejected a pairing attempt with a wrong code from {}", request.getRemoteAddr());
        yield error(403, "wrong_code", "The pairing code does not match");
      }
      case CLOSED -> error(409, "pairing_closed", "Pairing is closed; configure a new pairing code to pair again");
    };
  }

  private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    return ResponseEntity.status(status).body(body);
  }
}
