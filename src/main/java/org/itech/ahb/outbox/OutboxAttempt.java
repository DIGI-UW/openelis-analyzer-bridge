package org.itech.ahb.outbox;

import java.time.Instant;

/** One recorded delivery attempt, kept so an operator can see what OpenELIS actually said and when. */
public record OutboxAttempt(
  long id,
  String outboxId,
  int attemptNo,
  Kind kind,
  String actor,
  Instant startedAt,
  Instant finishedAt,
  String outcome,
  Integer httpStatus,
  String error,
  String responseExcerpt
) {
  /** What caused the attempt. */
  public enum Kind {
    /** The dispatcher, following the retry schedule. */
    AUTO,
    /** An operator asked for this entry to be retried. */
    MANUAL,
    /** An operator retry that re-rendered the bundle from the raw message first. */
    MANUAL_RERENDER,
    /** The bridge restarted while this entry was in flight and returned it to the queue. */
    STARTUP_RECOVERY
  }
}
