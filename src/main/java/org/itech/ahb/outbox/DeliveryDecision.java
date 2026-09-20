package org.itech.ahb.outbox;

/**
 * What to do with an entry after an attempt.
 *
 * @param disposition whether to finish, retry, or give up
 * @param reason why it is being dead-lettered, when it is
 */
public record DeliveryDecision(Disposition disposition, FailureReason reason) {
  public enum Disposition {
    /** OpenELIS has it. */
    DELIVERED,
    /** Could still succeed later; schedule another attempt. */
    RETRY,
    /** Cannot succeed without a human; hold it in the dead-message queue with its payload. */
    DEAD_LETTER
  }

  public static final DeliveryDecision DELIVERED = new DeliveryDecision(Disposition.DELIVERED, null);
  public static final DeliveryDecision RETRY = new DeliveryDecision(Disposition.RETRY, null);

  public static DeliveryDecision deadLetter(FailureReason reason) {
    return new DeliveryDecision(Disposition.DEAD_LETTER, reason);
  }
}
