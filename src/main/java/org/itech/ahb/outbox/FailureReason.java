package org.itech.ahb.outbox;

/** Why an entry is in the dead-message queue. Shown to operators and used to scope bulk retries. */
public enum FailureReason {
  /** The source is not a saved, active analyzer connection. Register it, then retry. */
  UNREGISTERED_SOURCE,
  /** The source is known but sent over a transport its connection does not accept. */
  CONNECTION_TRANSPORT_MISMATCH,
  /** The connection has no pinned profile, so results cannot be classified. */
  UNPINNED_PROFILE,
  /** The message parsed but yielded no results to deliver. */
  PARSE_NO_RESULTS,
  /** The message could not be rendered into the OpenELIS contract. */
  RENDER_ERROR,
  /** Every retry was used up while OpenELIS stayed unreachable. The payload is intact; retry after fixing the outage. */
  RETRY_EXHAUSTED,
  /** OpenELIS returned 422: its own configuration does not yet accept this delivery. Fix in OpenELIS, then retry. */
  OE_CONFIG_STATE,
  /** OpenELIS rejected the delivery outright (4xx other than 422). */
  OE_REJECTED,
  /** OpenELIS answered with a redirect, which a result POST must never follow. */
  OE_UNEXPECTED_REDIRECT
}
