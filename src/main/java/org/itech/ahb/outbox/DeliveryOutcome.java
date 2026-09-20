package org.itech.ahb.outbox;

/**
 * What happened on one delivery attempt: either OpenELIS answered, or the attempt never reached it.
 *
 * @param httpStatus the response status, or null when nothing was received
 * @param body the response body, or null
 * @param failure the exception that prevented a response, or null
 */
public record DeliveryOutcome(Integer httpStatus, String body, Exception failure) {
  public static DeliveryOutcome responded(int httpStatus, String body) {
    return new DeliveryOutcome(httpStatus, body, null);
  }

  public static DeliveryOutcome failed(Exception failure) {
    return new DeliveryOutcome(null, null, failure);
  }

  public boolean reachedOpenElis() {
    return httpStatus != null;
  }

  /** A short description for the operator, naming the exception type rather than a stack trace. */
  public String describeFailure() {
    if (failure == null) {
      return null;
    }
    String message = failure.getMessage();
    return failure.getClass().getName() + (message == null ? "" : ": " + message);
  }
}
