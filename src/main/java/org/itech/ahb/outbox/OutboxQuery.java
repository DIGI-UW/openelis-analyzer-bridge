package org.itech.ahb.outbox;

/**
 * Filter for listing outbox entries.
 *
 * @param state only entries in this state, or null for all
 * @param connectionId only entries from this analyzer connection, or null for all
 * @param failureReason only dead-lettered entries with this reason, or null for all
 * @param includeDismissed whether entries an operator has hidden are included
 */
public record OutboxQuery(
  OutboxState state,
  String connectionId,
  FailureReason failureReason,
  boolean includeDismissed,
  int limit,
  int offset
) {
  public static final int MAX_LIMIT = 1000;

  public OutboxQuery {
    if (limit < 1) {
      limit = 100;
    }
    if (limit > MAX_LIMIT) {
      limit = MAX_LIMIT;
    }
    if (offset < 0) {
      offset = 0;
    }
  }

  public static OutboxQuery all(int limit) {
    return new OutboxQuery(null, null, null, false, limit, 0);
  }

  public static OutboxQuery inState(OutboxState state, int limit) {
    return new OutboxQuery(state, null, null, false, limit, 0);
  }
}
