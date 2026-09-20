package org.itech.ahb.outbox;

/**
 * Decides what one delivery attempt means for the entry.
 *
 * <p>The distinction that matters is not success versus failure but "could this succeed later". A
 * result that cannot be delivered now is held with its payload either way; what changes is whether
 * the bridge keeps trying on its own or waits for a person.
 */
public final class DeliveryOutcomeClassifier {

  private DeliveryOutcomeClassifier() {}

  public static DeliveryDecision classify(DeliveryOutcome outcome) {
    if (!outcome.reachedOpenElis()) {
      // DNS failures, refused connections, timeouts and TLS errors all arrive here. None of them say
      // anything about the result itself, so all of them are worth retrying.
      return DeliveryDecision.RETRY;
    }
    int status = outcome.httpStatus();
    if (status >= 200 && status < 300) {
      // Includes OpenELIS answering 200 to a delivery it already recorded, which is exactly what
      // makes an at-least-once retry safe.
      return DeliveryDecision.DELIVERED;
    }
    if (status >= 300 && status < 400) {
      // A result POST must never follow a redirect: a proxy bouncing us to a login page would look
      // like a delivery to somewhere that is not OpenELIS.
      return DeliveryDecision.deadLetter(FailureReason.OE_UNEXPECTED_REDIRECT);
    }
    if (status == 408 || status == 429) {
      // Explicitly "ask again": a timeout OpenELIS noticed, or its own rate limit.
      return DeliveryDecision.RETRY;
    }
    if (status == 422) {
      // OpenELIS understood the delivery and declined it because of its own configuration: an
      // unknown connection, a missing site binding, a profile that no longer matches. An operator
      // fixes that in OpenELIS and retries; repeating on a timer would only waste the budget.
      return DeliveryDecision.deadLetter(FailureReason.OE_CONFIG_STATE);
    }
    if (status >= 400 && status < 500) {
      return DeliveryDecision.deadLetter(FailureReason.OE_REJECTED);
    }
    return DeliveryDecision.RETRY;
  }
}
