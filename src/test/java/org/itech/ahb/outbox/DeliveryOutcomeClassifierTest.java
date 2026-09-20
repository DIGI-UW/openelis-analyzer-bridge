package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.itech.ahb.outbox.DeliveryDecision.Disposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule these tests pin is not success versus failure but "could this succeed later". Either way
 * the result is kept; what changes is whether the bridge keeps trying by itself or waits for a
 * person. Getting this wrong in the retryable direction wastes a retry budget; getting it wrong in
 * the other direction is what let a three-second DNS failure lose results.
 */
class DeliveryOutcomeClassifierTest {

  private static Disposition of(DeliveryOutcome outcome) {
    return DeliveryOutcomeClassifier.classify(outcome).disposition();
  }

  @Test
  @DisplayName("an unreachable OpenELIS is always worth retrying")
  void unreachableOpenElisRetries() {
    assertEquals(Disposition.RETRY, of(DeliveryOutcome.failed(new UnknownHostException("oe.openelis.org"))));
    assertEquals(Disposition.RETRY, of(DeliveryOutcome.failed(new ConnectException("connection refused"))));
    assertEquals(Disposition.RETRY, of(DeliveryOutcome.failed(new HttpTimeoutException("read timed out"))));
    assertEquals(Disposition.RETRY, of(DeliveryOutcome.failed(new SSLHandshakeException("handshake failed"))));
    assertEquals(Disposition.RETRY, of(DeliveryOutcome.failed(new IOException("broken pipe"))));
  }

  @ParameterizedTest
  @ValueSource(ints = { 200, 201, 202, 204 })
  @DisplayName("any 2xx means OpenELIS has it, including its answer to a delivery it already recorded")
  void successIsDelivered(int status) {
    assertEquals(Disposition.DELIVERED, of(DeliveryOutcome.responded(status, "{\"success\":true}")));
  }

  @ParameterizedTest
  @ValueSource(ints = { 500, 502, 503, 504 })
  @DisplayName("a server error is transient by definition")
  void serverErrorRetries(int status) {
    assertEquals(Disposition.RETRY, of(DeliveryOutcome.responded(status, "boom")));
  }

  @ParameterizedTest
  @ValueSource(ints = { 408, 429 })
  @DisplayName("OpenELIS asking us to try again is not a rejection")
  void askAgainRetries(int status) {
    assertEquals(Disposition.RETRY, of(DeliveryOutcome.responded(status, "slow down")));
  }

  @Test
  @DisplayName("a 422 names OpenELIS configuration, so an operator fixes it rather than a timer")
  void configurationStateIsHeldForAnOperator() {
    DeliveryDecision decision = DeliveryOutcomeClassifier.classify(
      DeliveryOutcome.responded(422, "{\"errorKey\":\"analyzer.fhirImport.error.missingSiteBinding\"}")
    );
    assertEquals(Disposition.DEAD_LETTER, decision.disposition());
    assertEquals(FailureReason.OE_CONFIG_STATE, decision.reason());
  }

  @ParameterizedTest
  @ValueSource(ints = { 400, 401, 403, 404, 405, 409, 413, 415 })
  @DisplayName("a rejection will not become an acceptance on its own")
  void rejectionIsHeldForAnOperator(int status) {
    DeliveryDecision decision = DeliveryOutcomeClassifier.classify(DeliveryOutcome.responded(status, "no"));
    assertEquals(Disposition.DEAD_LETTER, decision.disposition());
    assertEquals(FailureReason.OE_REJECTED, decision.reason());
  }

  @ParameterizedTest
  @ValueSource(ints = { 301, 302, 307 })
  @DisplayName("a redirect is never followed, because the destination is not known to be OpenELIS")
  void redirectIsHeldForAnOperator(int status) {
    DeliveryDecision decision = DeliveryOutcomeClassifier.classify(DeliveryOutcome.responded(status, ""));
    assertEquals(Disposition.DEAD_LETTER, decision.disposition());
    assertEquals(FailureReason.OE_UNEXPECTED_REDIRECT, decision.reason());
  }
}
