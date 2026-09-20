package org.itech.ahb.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable record of every result the bridge has received and not yet delivered.
 *
 * <p>The contract this store exists to keep: once a message is persisted here it stays here, with
 * its complete payload, until OpenELIS durably accepts it or an operator can see why it could not be
 * delivered. Nothing in this interface deletes an undelivered entry.
 */
public interface OutboxStore {
  /**
   * Persist a message on receipt, before the source is identified or anything is parsed. Returns a
   * receipt naming the RECEIVED row. Re-receiving identical content from the same source returns the
   * existing row rather than storing a second copy.
   */
  Receipt receive(ReceivedMessage message);

  /**
   * Replace a RECEIVED row with one PENDING row per rendered delivery, in a single transaction, so a
   * crash mid-render leaves either the recoverable original or the complete deliverables.
   */
  void markRendered(String receiptId, List<RenderedDelivery> deliveries);

  /**
   * Move an entry to the dead-message queue with its payload intact, without counting a delivery
   * attempt. For failures that happen before any attempt: an unidentifiable source, an unrenderable
   * message.
   */
  void markDeadLettered(String id, FailureReason reason, String error);

  /**
   * Move an entry to the dead-message queue as the outcome of a delivery attempt, counting that
   * attempt. For OpenELIS rejecting the delivery, and for exhausting the retry budget.
   */
  void markRejected(String id, FailureReason reason, Integer httpStatus, String error, String responseExcerpt);

  /**
   * Claim the next entry whose attempt is due and whose lease is free, extending a lease on it.
   * Claiming and releasing are what make restart recovery unambiguous: a lease that outlives the
   * process is proof the attempt was interrupted.
   */
  Optional<OutboxEntry> claimNextDue(Instant now, Duration lease, String owner);

  /** Record a successful delivery, storing OpenELIS's receipt reference for operator confirmation. */
  void markDelivered(String id, int httpStatus, String oeReceipt, String responseExcerpt);

  /** Schedule another attempt after a failure that can still succeed. */
  void markRetrying(String id, Instant nextAttemptAt, Integer httpStatus, String error, String responseExcerpt);

  /** Release a lease without counting an attempt, used when the bridge is shutting down mid-attempt. */
  void releaseLease(String id);

  /**
   * Return every entry left leased by a previous process to the queue, due immediately. Called once
   * at startup; the outcome of an interrupted attempt is unknown, and redelivering is safe because
   * OpenELIS deduplicates on the delivery identity.
   *
   * @return how many entries were recovered
   */
  int recoverInterrupted(Instant now);

  /** Record an attempt against an entry. */
  void recordAttempt(
    String outboxId,
    OutboxAttempt.Kind kind,
    String actor,
    Instant startedAt,
    Instant finishedAt,
    String outcome,
    Integer httpStatus,
    String error,
    String responseExcerpt
  );

  /** Make an entry due now, for an operator retry. */
  void requestRetry(String id, String actor, Instant now);

  /** Replace the stored bundle, for an operator retry that re-renders against current configuration. */
  void replaceRenderedPayload(String id, String fhirJson, String profileId, Integer profileRevision);

  /** Hide a dead-lettered entry from the default view. Never deletes it. */
  void dismiss(String id, String actor, Instant now);

  Optional<OutboxEntry> get(String id);

  List<OutboxEntry> list(OutboxQuery query);

  List<OutboxAttempt> attempts(String outboxId);

  /** Counts by state, for the health indicator, metrics and the operator dashboard. */
  Map<OutboxState, Integer> countsByState();

  /** The received time of the oldest entry still awaiting delivery, if any. */
  Optional<Instant> oldestUndeliveredReceivedAt();

  /** The received message as stored. Callers must audit access: this is clinical content. */
  Optional<String> rawPayload(String id);

  /** The rendered bundle as it will be, or was, POSTed. Callers must audit access. */
  Optional<String> fhirPayload(String id);

  /**
   * Delete delivered and dismissed entries past their retention window, and any raw payload no entry
   * references. Entries still in the dead-message queue are never purged.
   *
   * @return how many entries were removed
   */
  int purgeExpired(Instant now, Duration deliveredRetention, Duration dismissedRetention);

  /** Whether the store had to recover from a corrupt database on open, which means data was lost. */
  boolean recoveredFromCorruption();

  void close();
}
